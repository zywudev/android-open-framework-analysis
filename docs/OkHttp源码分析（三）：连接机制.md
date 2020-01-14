# OkHttp 源码分析（三）：连接机制

前面两篇文章分别介绍了 OkHttp 的请求流程和缓存机制，最后这篇文章介绍 OkHttp 的连接机制，作为 OkHttp 源码分析的收尾。

建议将 OkHttp 的源码下载下来，使用 IDEA 编辑器可以直接打开阅读。我这边也将最新版的源码下载下来，进行了注释说明，有需要的可以直接从 [Android open framework analysis](https://github.com/zywudev/android-open-framework-analysis) 下载查看。

## 创建连接

OkHttp 连接的创建是通过 StreamAllocation 对象统筹完成。

它主要用来管理两个角色：

- RealConnection：真正建立连接的对象，利用 Socket 建立连接。
- ConnectionPool：连接池，用来管理和复用连接。

StreamAllocation 是在 RetryAndFollowUpInterceptor 中被创建，此时并未发起连接。

```java
// RetryAndFollowUpInterceptor .intercept()

StreamAllocation streamAllocation = new StreamAllocation(client.connectionPool(),
        createAddress(request.url()), call, eventListener, callStackTrace);
```

真正的连接是在处理完 Header 和缓存之后，调用 ConnectInterceptor 进行的。

```java
// ConnectInterceptor.intercept()
    
StreamAllocation streamAllocation = realChain.streamAllocation();

// We need the network to satisfy this request. Possibly for validating a conditional GET.
boolean doExtensiveHealthChecks = !request.method().equals("GET");
HttpCodec httpCodec = streamAllocation.newStream(client, chain, doExtensiveHealthChecks);
RealConnection connection = streamAllocation.connection();
```

这里创建了两个对象：

- HttpCodec：用来编码 http request 和解码 http response
- RealConnection：上文介绍了。

调用 streamAllocation 的 `newStream` 方法经过一系列判断最终会走到 `findConnection` 方法

```java
private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout, int pingIntervalMillis, boolean connectionRetryEnabled) throws IOException {
    boolean foundPooledConnection = false;
    RealConnection result = null;
    Route selectedRoute = null;
    Connection releasedConnection;
    Socket toClose;
    synchronized (connectionPool) {
      if (released) throw new IllegalStateException("released");
      if (codec != null) throw new IllegalStateException("codec != null");
      if (canceled) throw new IOException("Canceled");


      // 1、尝试使用已分配的连接
      releasedConnection = this.connection;
      toClose = releaseIfNoNewStreams();
      if (this.connection != null) {
        // 当前连接可用.
        result = this.connection;
        releasedConnection = null;
      }
      if (!reportedAcquired) {
        // If the connection was never reported acquired, don't report it as released!
        releasedConnection = null;
      }

      // 2、尝试从连接池中获取一个连接
      if (result == null) {
        // Attempt to get a connection from the pool.
        Internal.instance.acquire(connectionPool, address, this, null);
        if (connection != null) {
          foundPooledConnection = true;
          result = connection;
        } else {
          selectedRoute = route;
        }
      }
    }
    closeQuietly(toClose);

    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
    }
    if (result != null) {
      // 如果从连接池中获取到了一个连接，就将其返回.
      return result;
    }

    // If we need a route selection, make one. This is a blocking operation.
    boolean newRouteSelection = false;
    if (selectedRoute == null && (routeSelection == null || !routeSelection.hasNext())) {
      newRouteSelection = true;
      routeSelection = routeSelector.next();
    }


    synchronized (connectionPool) {
      if (canceled) throw new IOException("Canceled");

      if (newRouteSelection) {
        // Now that we have a set of IP addresses, make another attempt at getting a connection from
        // the pool. This could match due to connection coalescing.
        // 根据一系列的 IP地址从连接池中获取一个链接
        List<Route> routes = routeSelection.getAll();
        for (int i = 0, size = routes.size(); i < size; i++) {
          Route route = routes.get(i);
          // 从连接池中获取一个连接
          Internal.instance.acquire(connectionPool, address, this, route);
          if (connection != null) {
            foundPooledConnection = true;
            result = connection;
            this.route = route;
            break;
          }
        }
      }

      // 3、如果连接池中没有可用连接，则创建一个
      if (!foundPooledConnection) {
        if (selectedRoute == null) {
          selectedRoute = routeSelection.next();
        }

        // Create a connection and assign it to this allocation immediately. This makes it possible
        // for an asynchronous cancel() to interrupt the handshake we're about to do.
        route = selectedRoute;
        refusedStreamCount = 0;
        result = new RealConnection(connectionPool, selectedRoute);
        acquire(result, false);
      }
    }

    // If we found a pooled connection on the 2nd time around, we're done.
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
      return result;
    }

    //4、 开始TCP以及TLS握手操作
    result.connect(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis,
        connectionRetryEnabled, call, eventListener);
    routeDatabase().connected(result.route());

    Socket socket = null;
    synchronized (connectionPool) {
      reportedAcquired = true;

      // 5、将新创建的连接，放在连接池中.
      Internal.instance.put(connectionPool, result);

      // If another multiplexed connection to the same address was created concurrently, then
      // release this connection and acquire that one.
      if (result.isMultiplexed()) {
        socket = Internal.instance.deduplicate(connectionPool, address, this);
        result = connection;
      }
    }
    closeQuietly(socket);

    eventListener.connectionAcquired(call, result);
    return result;
  }
```

整个流程是：

- 1、判断当前的连接是否可以使用：输入输出流没有关闭，Socket 未关闭等
- 2、如果当前连接不可用，尝试从连接池中获取一个可用连接
- 3、如果连接池中没有可用连接，则创建一个连接
- 4、开始 TCP 连接以及 TLS 握手操作
- 5、将新创建的连接加入到连接池中

## 连接池

网络请求时频繁地进行 Socket 连接和断开 Socket 非常消耗网络资源和浪费时间，连接复用可以提升网络访问的效率。这里就引入了连接池的概念。

OKHttp 的连接池由 ConnectionPool 实现。

ConnetionPool 内部维护了一个线程池，负责清理无效的连接。

```java
public final class ConnectionPool {
   private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
      Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS, new SynchronousQueue<>(), Util.threadFactory("OkHttp ConnectionPool", true));
    
   void put(RealConnection connection) {
    assert (Thread.holdsLock(this));

    if (!cleanupRunning) {
      cleanupRunning = true;
      // 使用线程池执行清理任务
      executor.execute(cleanupRunnable);
    }
    // 将新建的连接插入到双端队列中
    connections.add(connection);
   }
    
   private final Runnable cleanupRunnable = () -> {
    while (true) {
      // 清理操作，返回下次需要清理的时间
      long waitNanos = cleanup(System.nanoTime());
      if (waitNanos == -1) return;
      if (waitNanos > 0) {
        long waitMillis = waitNanos / 1000000L;
        waitNanos -= (waitMillis * 1000000L);
        synchronized (ConnectionPool.this) {
          try {
            ConnectionPool.this.wait(waitMillis, (int) waitNanos);
          } catch (InterruptedException ignored) {
          }
        }
      }
    }
  };
}
```

ConnectionPool 维护一个线程池用于清理无效的连接，清理任务由 `cleanup`方法完成，首先执行清理，返回下次需要清理的间隔时间，然后调用 `wait` 方法释放锁。等到了时间，再次进行清理操作，返回下一次清理的时间，循环往复下去。

具体看一下 `cleanup` 方法：

```java
long cleanup(long now) {
    int inUseConnectionCount = 0;
    int idleConnectionCount = 0;
    RealConnection longestIdleConnection = null;
    long longestIdleDurationNs = Long.MIN_VALUE;

    // Find either a connection to evict, or the time that the next eviction is due.
    synchronized (this) {
        // 遍历所有的连接
        for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
            RealConnection connection = i.next();

            // 1、连接正在使用，即StreanAllocation的引用数量大于0
            if (pruneAndGetAllocationCount(connection, now) > 0) {
                inUseConnectionCount++;
                continue;
            }

            idleConnectionCount++;

            // If the connection is ready to be evicted, we're done.
            // 2、如果找到了一个可以被清理的连接，会尝试去寻找闲置时间最久的连接来释放
            long idleDurationNs = now - connection.idleAtNanos;
            if (idleDurationNs > longestIdleDurationNs) {
                longestIdleDurationNs = idleDurationNs;
                longestIdleConnection = connection;
            }
        }

        // maxIdleConnections 表示最大允许的闲置的连接的数量,keepAliveDurationNs表示连接允许存活的最长的时间。
        // 默认空闲连接最大数目为5个，keepalive 时间最长为5分钟
        // 3、如果空闲连接超过5个或者keepalive时间大于5分钟，则将该连接清理
        if (longestIdleDurationNs >= this.keepAliveDurationNs
            || idleConnectionCount > this.maxIdleConnections) {
            connections.remove(longestIdleConnection);
        } else if (idleConnectionCount > 0) {
            // 4、闲置的连接的数量大于0，停顿指定的时间（等会儿会将其清理掉，现在还不是时候）
            return keepAliveDurationNs - longestIdleDurationNs;
        } else if (inUseConnectionCount > 0) {
            // All connections are in use. It'll be at least the keep alive duration 'til we run again.
            ///5、所有的连接都在使用中，5分钟后再清理
            return keepAliveDurationNs;
        } else {
            // No connections, idle or in use.
            //6、没有连接
            cleanupRunning = false;
            return -1;
        }
    }

    closeQuietly(longestIdleConnection.socket());

    // Cleanup again immediately.
    return 0;
}
```

整体流程如下：

- 1、遍历所有连接，查询每个连接的内部的 StreamAllocation 的引用数量，如果大于 0，表示连接正在使用，无需清理，执行下一次循环。
- 2、如果找到了一个可以被清理的连接，会尝试去寻找闲置时间最久的连接来释放。
- 3、如果空闲连接超过 5 个或者 keepalive 时间大于 5 分钟，则将该连接清理。
- 4、闲置的连接的数量大于 0，返回该连接的到期时间（等会儿会将其清理掉，现在还不是时候）。
- 5、全部都是活跃连接，5 分钟后再进行清理。
- 6、没有任何连接，跳出循环。

RealConnection 内有一个 SteamAllocation 虚引用列表，每次创建的 StreamAllocation，都会被添加到这个列表中，如果流关闭后就将 SteamAllocation 对象从该列表中移出去，也正是利用这种计数方式判定一个连接是否为空闲连接。

查询引用计数是在 `pruneAndGetAllocationCount` 方法中实现。

```java
private int pruneAndGetAllocationCount(RealConnection connection, long now) {
    // 虚引用列表
    List<Reference<StreamAllocation>> references = connection.allocations;
    // 遍历虚引用列表
    for (int i = 0; i < references.size(); ) {
        Reference<StreamAllocation> reference = references.get(i);
        //如果虚引用StreamAllocation正在被使用，则跳过进行下一次循环
        if (reference.get() != null) {
            i++;
            continue;
        }

        // We've discovered a leaked allocation. This is an application bug.
        StreamAllocation.StreamAllocationReference streamAllocRef =
            (StreamAllocation.StreamAllocationReference) reference;
        String message = "A connection to " + connection.route().address().url()
            + " was leaked. Did you forget to close a response body?";
        Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);

        // 移除引用
        references.remove(i);
        connection.noNewStreams = true;

        // If this was the last allocation, the connection is eligible for immediate eviction.
        if (references.isEmpty()) {
            connection.idleAtNanos = now - keepAliveDurationNs;
            return 0;
        }
    }

    return references.size();
}
```

## 参考

[https://juejin.im/post/5a704ed05188255a8817f4c9](https://juejin.im/post/5a704ed05188255a8817f4c9)