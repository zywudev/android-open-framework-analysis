# OkHttp 源码分析（二）：缓存机制

这篇文章讲解一下 OkHttp 的缓存机制。

建议将 OkHttp 的源码下载下来，使用 IDEA 编辑器可以直接打开阅读。我这边也将最新版的源码下载下来，进行了注释说明，有需要的可以直接从 [Android open framework analysis](https://github.com/zywudev/android-open-framework-analysis) 下载查看。

在网络请求的过程中，一般都会使用到缓存，缓存的意义在于，对于客户端来说，使用缓存数据能够缩短页面展示数据的时间，优化用户体验，同时降低请求网络数据的频率，避免流量浪费。对于服务端来说，使用缓存能够分解一部分服务端的压力。

在讲解 OkHttp 的缓存机制之前，先了解下 Http  的缓存理论知识，这是实现 OkHttp 缓存的基础。

## Http 缓存

Http 的缓存机制如下图：

![http-cache](../assets/okhttp/http-cache.png)

Http 的缓存分为两种：强制缓存和对比缓存。强制缓存优先于对比缓存。

### 强制缓存

客户端第一次请求数据时，服务端返回缓存的过期时间（通过字段 Expires 与 Cache-Control 标识），后续如果缓存没有过期就直接使用缓存，无需请求服务端；否则向服务端请求数据。

**Expires**

服务端返回的到期时间。下一次请求时，请求时间小于 Expires 的值，直接使用缓存数据。

由于到期时间是服务端生成，客户端和服务端的时间可能存在误差，导致缓存命中的误差。

**Cache-Control**

Http1.1 中采用了 Cache-Control 代替了 Expires，常见 Cache-Control 的取值有：

- private: 客户端可以缓存 
- public:  客户端和代理服务器都可缓存
- max-age=xxx:  缓存的内容将在 xxx 秒后失效
- no-cache:  需要使用对比缓存来验证缓存数据，并不是字面意思
- no-store:  所有内容都不会缓存，强制缓存，对比缓存都不会触发

### 对比缓存

对比缓存每次请求都需要与服务器交互，由服务端判断是否可以使用缓存。

客户端第一次请求数据时,服务器会将缓存标识（Last-Modified/If-Modified-Since 与 Etag/If-None-Match）与数据一起返回给客户端，客户端将两者备份到缓存数据库中。

当再次请求数据时，客户端将备份的缓存标识发送给服务器，服务器根据缓存标识进行判断，返回 304 状态码，通知客户端可以使用缓存数据，服务端不需要将报文主体返回给客户端。

**Last-Modified/If-Modified-Since**

Last-Modified 表示资源上次修改的时间，在第一次请求时服务端返回给客户端。

客户端再次请求时，会在 header 里携带 If-Modified-Since ，将资源修改时间传给服务端。

服务端发现有  If-Modified-Since 字段，则与被请求资源的最后修改时间对比，如果资源的最后修改时间大于  If-Modified-Since，说明资源被改动了，则响应所有资源内容，返回状态码 200；否则说明资源无更新修改，则响应状态码 304，告知客户端继续使用所保存的缓存。

**Etag/If-None-Match **

优先于 Last-Modified/If-Modified-Since。

Etag 是当前资源在服务器的唯一标识，生成规则由服务器决定。当客户端第一次请求时，服务端会返回该标识。

当客户端再次请求数据时，在 header 中添加 If-None-Match 标识。

服务端发现有 If-None-Match 标识，则会与被请求资源对比，如果不同，说明资源被修改，返回 200；如果相同，说明资源无更新，响应 304，告知客户端继续使用缓存。

## OkHttp 缓存

为了节省流量和提高响应速度，OkHttp 有自己的一套缓存机制，CacheInterceptor 就是用来负责读取缓存以及更新缓存的。

我们来看 CacheInterceptor 的关键代码：

```java
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

    // 3、不进行网络请求，而且没有缓存数据，则返回网络请求错误的结果
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

    // 4、如果不进行网络请求，缓存数据可用，则直接返回缓存数据.
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
```

整个方法的流程如下：

- 1、读取候选缓存。

- 2、根据候选缓存创建缓存策略。

- 3、根据缓存策略，如果不进行网络请求，而且没有缓存数据时，报错返回错误码 504。

- 4、根据缓存策略，如果不进行网络请求，缓存数据可用，则直接返回缓存数据。

- 5、缓存无效，则继续执行网络请求。

- 6、通过服务端校验后，缓存数据可以使用（返回 304），则直接返回缓存数据，并且更新缓存。

- 7、读取网络结果，构造 response，对数据进行缓存。

OkHttp 通过 CacheStrategy 获取缓存策略，CacheStrategy 根据之前缓存结果与当前将要发生的 request 的Header 计算缓存策略。规则如下：

| networkRequest | cacheResponse | CacheStrategy                                                |
| -------------- | ------------- | ------------------------------------------------------------ |
| null           | null          | only-if-cached(表明不进行网络请求，且缓存不存在或者过期，一定会返回 503 错误) |
| null           | non-null      | 不进行网络请求，而且缓存可以使用，直接返回缓存，不用请求网络 |
| non-null       | null          | 需要进行网络请求，而且缓存不存在或者过期，直接访问网络。     |
| non-null       | not-null      | Header 中含有 ETag/Last-Modified 标识，需要在条件请求下使用，还是需要访问网络。 |

CacheStrategy 通过工厂模式构造，CacheStrategy.Factory 对象构建以后，调用它的 `get` 方法即可获得具体的CacheStrategy，CacheStrategy.Factory 的 `get`方法内部调用的是 CacheStrategy.Factory 的 `getCandidate` 方法，它是核心的实现。

```java
private CacheStrategy getCandidate() {
    // 1、没有缓存，直接返回包含网络请求的策略结果
    if (cacheResponse == null) {
        return new CacheStrategy(request, null);
    }

    // 2、如果握手信息丢失，则返返回包含网络请求的策略结果
    if (request.isHttps() && cacheResponse.handshake() == null) {
        return new CacheStrategy(request, null);
    }

    // 3、如果根据CacheControl参数有no-store，则不适用缓存，直接返回包含网络请求的策略结果
    if (!isCacheable(cacheResponse, request)) {
        return new CacheStrategy(request, null);
    }

    // 4、如果缓存数据的CacheControl有no-cache指令或者需要向服务器端校验后决定是否使用缓存，则返回只包含网络请求的策略结果
    CacheControl requestCaching = request.cacheControl();
    if (requestCaching.noCache() || hasConditions(request)) {
        return new CacheStrategy(request, null);
    }

    CacheControl responseCaching = cacheResponse.cacheControl();

    long ageMillis = cacheResponseAge();
    long freshMillis = computeFreshnessLifetime();

    if (requestCaching.maxAgeSeconds() != -1) {
        freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
    }

    long minFreshMillis = 0;
    if (requestCaching.minFreshSeconds() != -1) {
        minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
    }

    long maxStaleMillis = 0;
    if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
        maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
    }
    // 5. 如果缓存在过期时间内则可以直接使用，则直接返回上次缓存
    if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
        Response.Builder builder = cacheResponse.newBuilder();
        if (ageMillis + minFreshMillis >= freshMillis) {
            builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
        }
        long oneDayMillis = 24 * 60 * 60 * 1000L;
        if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
            builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
        }
        return new CacheStrategy(null, builder.build());
    }

    //6. 如果缓存过期，且有ETag等信息，则发送If-None-Match、If-Modified-Since等条件请求
    String conditionName;
    String conditionValue;
    if (etag != null) {
        conditionName = "If-None-Match";
        conditionValue = etag;
    } else if (lastModified != null) {
        conditionName = "If-Modified-Since";
        conditionValue = lastModifiedString;
    } else if (servedDate != null) {
        conditionName = "If-Modified-Since";
        conditionValue = servedDateString;
    } else {
        return new CacheStrategy(request, null); // No condition! Make a regular request.
    }

    Headers.Builder conditionalRequestHeaders = request.headers().newBuilder();
    Internal.instance.addLenient(conditionalRequestHeaders, conditionName, conditionValue);

    Request conditionalRequest = request.newBuilder()
        .headers(conditionalRequestHeaders.build())
        .build();
    return new CacheStrategy(conditionalRequest, cacheResponse);
}
```

整个函数的逻辑就是按照上面的 Http 缓存策略流程图来实现的，这里不再赘述。

我们再简单看下 OkHttp 是如何缓存数据的。

OkHttp 具体的缓存数据是利用 DiskLruCache 实现，用磁盘上的有限大小空间进行缓存，按照 LRU 算法进行缓存淘汰。

 Cache 类封装了缓存的实现，缓存操作封装在  InternalCache 接口中。

```java
public interface InternalCache {
    // 获取缓存
    @Nullable
    Response get(Request request) throws IOException;

    // 存入缓存
    @Nullable
    CacheRequest put(Response response) throws IOException;

    // 移除缓存
    void remove(Request request) throws IOException;

    // 更新缓存
    void update(Response cached, Response network);

    // 跟踪一个满足缓存条件的GET请求
    void trackConditionalCacheHit();

    // 跟踪满足缓存策略CacheStrategy的响应
    void trackResponse(CacheStrategy cacheStrategy);
}
```

Cache 类在其内部实现了 InternalCache 的匿名内部类，内部类的方法调用 Cache 对应的方法。

```java
public final class Cache implements Closeable, Flushable {
    
  final InternalCache internalCache = new InternalCache() {
    @Override public @Nullable Response get(Request request) throws IOException {
      return Cache.this.get(request);
    }

    @Override public @Nullable CacheRequest put(Response response) throws IOException {
      return Cache.this.put(response);
    }

    @Override public void remove(Request request) throws IOException {
      Cache.this.remove(request);
    }

    @Override public void update(Response cached, Response network) {
      Cache.this.update(cached, network);
    }

    @Override public void trackConditionalCacheHit() {
      Cache.this.trackConditionalCacheHit();
    }

    @Override public void trackResponse(CacheStrategy cacheStrategy) {
      Cache.this.trackResponse(cacheStrategy);
    }
  };
}
```

## 总结

* OkHttp 的缓存机制是按照 Http 的缓存机制实现。
* OkHttp 具体的数据缓存逻辑封装在 Cache 类中，它利用 DiskLruCache 实现。
* 默认情况下，OkHttp 不进行缓存数据。
* 可以在构造 OkHttpClient 时设置 Cache 对象，在其构造函数中指定缓存目录和缓存大小。

- 如果对 OkHttp 内置的 Cache  类不满意，可以自行实现  InternalCache 接口，在构造 OkHttpClient  时进行设置，这样就可以使用自定义的缓存策略了。


## 参考

[https://www.cnblogs.com/chenqf/p/6386163.html](https://www.cnblogs.com/chenqf/p/6386163.html)

[https://juejin.im/post/5a704ed05188255a8817f4c9](https://juejin.im/post/5a704ed05188255a8817f4c9)