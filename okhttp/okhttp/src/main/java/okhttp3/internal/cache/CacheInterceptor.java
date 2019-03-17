/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.cache;

import java.io.IOException;
import javax.annotation.Nullable;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.RealResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.discard;

/**
 * Serves requests from the cache and writes responses to the cache.
 */
public final class CacheInterceptor implements Interceptor {
    final @Nullable
    InternalCache cache;

    public CacheInterceptor(@Nullable InternalCache cache) {
        this.cache = cache;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // 1、如果此次网络请求有缓存数据，取出缓存数据作为候选
        Response cacheCandidate = cache != null
                ? cache.get(chain.request())
                : null;

        long now = System.currentTimeMillis();

        // 2、根据cache获取缓存策略
        CacheStrategy strategy = new CacheStrategy.Factory(now, chain.request(), cacheCandidate).get();
        // 通过缓存策略计算的网络请求
        Request networkRequest = strategy.networkRequest;
        // 通过缓存策略处理得到的缓存响应数据
        Response cacheResponse = strategy.cacheResponse;

        if (cache != null) {
            cache.trackResponse(strategy);
        }

        // 缓存数据不能使用，清理此缓存数据
        if (cacheCandidate != null && cacheResponse == null) {
            closeQuietly(cacheCandidate.body());
        }

        // 3、如果当前网络不能使用（在CacheStrategy中详细解释网络不能用的情况），
        // 而且没有缓存数据，则返回网络请求错误的结果
        if (networkRequest == null && cacheResponse == null) {
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(504)
                    .message("Unsatisfiable Request (only-if-cached)")
                    .body(Util.EMPTY_RESPONSE)
                    .sentRequestAtMillis(-1L)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();
        }

        // 4、如果不需要网络请求，缓存数据可用，则直接返回缓存数据.
        if (networkRequest == null) {
            return cacheResponse.newBuilder()
                    .cacheResponse(stripBody(cacheResponse))
                    .build();
        }

        // 5、缓存无效，则继续执行网络请求。
        Response networkResponse = null;
        try {
            networkResponse = chain.proceed(networkRequest);
        } finally {
            // If we're crashing on I/O or otherwise, don't leak the cache body.
            if (networkResponse == null && cacheCandidate != null) {
                closeQuietly(cacheCandidate.body());
            }
        }

        if (cacheResponse != null) {
            // 6、通过服务端校验后，缓存数据可以使用（返回304），则直接返回缓存数据，并且更新缓存
            if (networkResponse.code() == HTTP_NOT_MODIFIED) {
                Response response = cacheResponse.newBuilder()
                        .headers(combine(cacheResponse.headers(), networkResponse.headers()))
                        .sentRequestAtMillis(networkResponse.sentRequestAtMillis())
                        .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis())
                        .cacheResponse(stripBody(cacheResponse))
                        .networkResponse(stripBody(networkResponse))
                        .build();
                networkResponse.body().close();

                // Update the cache after combining headers but before stripping the
                // Content-Encoding header (as performed by initContentStream()).
                cache.trackConditionalCacheHit();
                cache.update(cacheResponse, response);
                return response;
            } else {
                closeQuietly(cacheResponse.body());
            }
        }

        // 7、读取网络结果，构造response
        Response response = networkResponse.newBuilder()
                .cacheResponse(stripBody(cacheResponse))
                .networkResponse(stripBody(networkResponse))
                .build();

        // 对数据进行缓存
        if (cache != null) {
            if (HttpHeaders.hasBody(response) && CacheStrategy.isCacheable(response, networkRequest)) {
                // Offer this request to the cache.
                CacheRequest cacheRequest = cache.put(response);
                return cacheWritingResponse(cacheRequest, response);
            }

            if (HttpMethod.invalidatesCache(networkRequest.method())) {
                try {
                    cache.remove(networkRequest);
                } catch (IOException ignored) {
                    // The cache cannot be written.
                }
            }
        }

        return response;
    }

    private static Response stripBody(Response response) {
        return response != null && response.body() != null
                ? response.newBuilder().body(null).build()
                : response;
    }

    /**
     * Returns a new source that writes bytes to {@code cacheRequest} as they are read by the source
     * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
     * may never exhaust the source stream and therefore not complete the cached response.
     */
    private Response cacheWritingResponse(final CacheRequest cacheRequest, Response response)
            throws IOException {
        // Some apps return a null body; for compatibility we treat that like a null cache request.
        if (cacheRequest == null) return response;
        Sink cacheBodyUnbuffered = cacheRequest.body();
        if (cacheBodyUnbuffered == null) return response;

        final BufferedSource source = response.body().source();
        final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);

        Source cacheWritingSource = new Source() {
            boolean cacheRequestClosed;

            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                long bytesRead;
                try {
                    bytesRead = source.read(sink, byteCount);
                } catch (IOException e) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true;
                        cacheRequest.abort(); // Failed to write a complete cache response.
                    }
                    throw e;
                }

                if (bytesRead == -1) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true;
                        cacheBody.close(); // The cache response is complete!
                    }
                    return -1;
                }

                sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
                cacheBody.emitCompleteSegments();
                return bytesRead;
            }

            @Override
            public Timeout timeout() {
                return source.timeout();
            }

            @Override
            public void close() throws IOException {
                if (!cacheRequestClosed
                        && !discard(this, HttpCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                    cacheRequestClosed = true;
                    cacheRequest.abort();
                }
                source.close();
            }
        };

        String contentType = response.header("Content-Type");
        long contentLength = response.body().contentLength();
        return response.newBuilder()
                .body(new RealResponseBody(contentType, contentLength, Okio.buffer(cacheWritingSource)))
                .build();
    }

    /**
     * Combines cached headers with a network headers as defined by RFC 7234, 4.3.4.
     */
    private static Headers combine(Headers cachedHeaders, Headers networkHeaders) {
        Headers.Builder result = new Headers.Builder();

        for (int i = 0, size = cachedHeaders.size(); i < size; i++) {
            String fieldName = cachedHeaders.name(i);
            String value = cachedHeaders.value(i);
            if ("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) {
                continue; // Drop 100-level freshness warnings.
            }
            if (isContentSpecificHeader(fieldName) || !isEndToEnd(fieldName)
                    || networkHeaders.get(fieldName) == null) {
                Internal.instance.addLenient(result, fieldName, value);
            }
        }

        for (int i = 0, size = networkHeaders.size(); i < size; i++) {
            String fieldName = networkHeaders.name(i);
            if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
                Internal.instance.addLenient(result, fieldName, networkHeaders.value(i));
            }
        }

        return result.build();
    }

    /**
     * Returns true if {@code fieldName} is an end-to-end HTTP header, as defined by RFC 2616,
     * 13.5.1.
     */
    static boolean isEndToEnd(String fieldName) {
        return !"Connection".equalsIgnoreCase(fieldName)
                && !"Keep-Alive".equalsIgnoreCase(fieldName)
                && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
                && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
                && !"TE".equalsIgnoreCase(fieldName)
                && !"Trailers".equalsIgnoreCase(fieldName)
                && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
                && !"Upgrade".equalsIgnoreCase(fieldName);
    }

    /**
     * Returns true if {@code fieldName} is content specific and therefore should always be used
     * from cached headers.
     */
    static boolean isContentSpecificHeader(String fieldName) {
        return "Content-Length".equalsIgnoreCase(fieldName)
                || "Content-Encoding".equalsIgnoreCase(fieldName)
                || "Content-Type".equalsIgnoreCase(fieldName);
    }
}
