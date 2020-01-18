前面的文章我们分析了 OkHttp 的核心源码，而 Retrofit 与 OkHttp 的结合使用，也是目前主流的方式，这篇文章主要分析下目前 Android 最优秀的网络封装框架 Retrofit。

在分析 Retrofit 源码之前，先看下 Retrofit 的简单使用。

## 基本使用

一般情况，Retrofit 的使用流程按照以下三步：

1、将 HTTP API 定义成接口形式

```java
public interface GitHubService {
  @GET("users/{user}/repos")
  Call<List<Repo>> listRepos(@Path("user") String user);
}
```

2、构建 Retrofit 实例，生成 GitHubService 接口的实现。

```java
// Retrofit 构建过程
Retrofit retrofit = new Retrofit.Builder()
    .baseUrl("https://api.github.com/")
    .build();

GitHubService service = retrofit.create(GitHubService.class);
```

3、发起网络请求，可以做同步或异步请求。

```java
Call<List<Repo>> repos = service.listRepos("octocat");

call.execute() 或者 call.enqueue()
```

这里，Retrofit 用注解标识不同的网络请求类型，极大的简化了 OkHttp 的使用方式。

这篇文章主要关注的几个问题：

- Retrofit 实例是如何创建的，它初始化了哪些东西？

- GitHubService 实例是如何创建的，这些注解是如何映射到每种网络请求的 ？

- 网络请求的流程是怎样的？

## Retrofit 创建过程

```java
Retrofit retrofit = new Retrofit.Builder()
    .baseUrl("https://api.github.com/")
    .build();
```

这里可以看出，Retrofit 实例是使用建造者模式通过 Builder 类进行创建。

> 建造者模式简言之：将一个复杂对象的构建与表示分离，使得用户在不知道对象的创建细节情况下可以直接创建复杂的对象。

### Retrofit

Retrofit 包含 7 个成员变量：

```java
public final class Retrofit {
  // 网络请求配置对象，存储网络请求相关的配置，如网络请求的方法、数据转换器、网络请求适配器、网络请求工厂、基地址等
  private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();

  // 网络请求器的工厂：生产网络请求器
  final okhttp3.Call.Factory callFactory;

  // 网络请求的 URL 地址
  final HttpUrl baseUrl;

  // 数据转换器工厂的集合
  final List<Converter.Factory> converterFactories;

  // 网络请求适配器工厂的集合
  final List<CallAdapter.Factory> callAdapterFactories;

  // 回调方法执行器
  final @Nullable Executor callbackExecutor;

  // 是否缓存创建的 ServiceMethod
  final boolean validateEagerly;
}
```

再看 Retrofit 构造函数，除了 serviceMethodCache, 其他成员变量都在这里进行赋值。

```java
Retrofit(okhttp3.Call.Factory callFactory, HttpUrl baseUrl,
         List<Converter.Factory> converterFactories, List<CallAdapter.Factory> callAdapterFactories,
         @Nullable Executor callbackExecutor, boolean validateEagerly) {
    this.callFactory = callFactory;
    this.baseUrl = baseUrl;
    this.converterFactories = converterFactories; // Copy+unmodifiable at call site.
    this.callAdapterFactories = callAdapterFactories; // Copy+unmodifiable at call site.
    this.callbackExecutor = callbackExecutor;
    this.validateEagerly = validateEagerly;
}
```

### Retrofit.Builder

```java
 public static final class Builder {
    private final Platform platform;     // 平台
    private @Nullable okhttp3.Call.Factory callFactory;      // 网络请求工厂，默认使用OkHttpCall（工厂方法模式）
    private @Nullable HttpUrl baseUrl;  // 网络请求URL地址
    private final List<Converter.Factory> converterFactories = new ArrayList<>();     // 数据转换器工厂的集合
    private final List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>(); // 网络请求适配器工厂的集合
    private @Nullable Executor callbackExecutor;    // 回调方法执行器
    private boolean validateEagerly;

    Builder(Platform platform) {
      this.platform = platform;
    }

    public Builder() {
      this(Platform.get());
    }
     ... 
 }
```

这里主要关注 Platform，在 Builder 构造函数中调用了 `Platform.get()` ，然后赋值给自己的 platform 变量，我们来看看 Platform 类。

```java
class Platform {
  private static final Platform PLATFORM = findPlatform();

  static Platform get() {
    return PLATFORM;
  }

  private static Platform findPlatform() {
    try {
      // 判断是否是 Android 平台
      Class.forName("android.os.Build");
      if (Build.VERSION.SDK_INT != 0) {
        return new Android();
      }
    } catch (ClassNotFoundException ignored) {
    }
    try {
      // Java 平台
      Class.forName("java.util.Optional");
      return new Java8();
    } catch (ClassNotFoundException ignored) {
    }
    return new Platform();
  }
}
```

`Platform.get()` 方法会调用 `findPlatform()` 方法，这里主要是判断是 Android 平台还是 Java 平台，如果是 Android 平台会返回一个 Android 对象。

```java
static class Android extends Platform {
    @IgnoreJRERequirement // Guarded by API check.
    @Override boolean isDefaultMethod(Method method) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        return method.isDefault();
    }

    @Override public Executor defaultCallbackExecutor() {
        return new MainThreadExecutor();
    }

    @Override List<? extends CallAdapter.Factory> defaultCallAdapterFactories(
        @Nullable Executor callbackExecutor) {
        if (callbackExecutor == null) throw new AssertionError();
        DefaultCallAdapterFactory executorFactory = new DefaultCallAdapterFactory(callbackExecutor);
        return Build.VERSION.SDK_INT >= 24
            ? asList(CompletableFutureCallAdapterFactory.INSTANCE, executorFactory)
            : singletonList(executorFactory);
    }

    @Override int defaultCallAdapterFactoriesSize() {
        return Build.VERSION.SDK_INT >= 24 ? 2 : 1;
    }

    @Override List<? extends Converter.Factory> defaultConverterFactories() {
        return Build.VERSION.SDK_INT >= 24
            ? singletonList(OptionalConverterFactory.INSTANCE)
            : Collections.<Converter.Factory>emptyList();
    }

    @Override int defaultConverterFactoriesSize() {
        return Build.VERSION.SDK_INT >= 24 ? 1 : 0;
    }

    static class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override public void execute(Runnable r) {
            handler.post(r);
        }
    }
}
```

关注三个重要的方法：

- `defaultCallbackExecutor`： 返回默认的 Executor 对象，正是 Retrofit 的成员变量回调执行器，它的内部采用 Handler 负责子线程到主线程的切换工作。

- `defaultCallAdapterFactories`：返回的是默认的 CallAdpter.Factory 的集合，也就是 Retrofit 的成员变量网络请求适配器工厂集合，如果是 Android 7.0 以上或者 Java 8，使用并发包中的 CompletableFuture 保证了回调的同步。

- `defaultConverterFactories`：返回的是默认的 Converter.Factory 的集合，也就是 Retrofit 的成员变量数据转换器工厂集合。

### build 过程

接着看一下 `Builder.build()` 方法。

```java
public Retrofit build() {
    if (baseUrl == null) {
        throw new IllegalStateException("Base URL required.");
    }

    okhttp3.Call.Factory callFactory = this.callFactory;
    if (callFactory == null) {
        // 默认使用 OkHttp
        callFactory = new OkHttpClient();
    }

    Executor callbackExecutor = this.callbackExecutor;
    if (callbackExecutor == null) {
        // 默认的 callbackExecutor
        callbackExecutor = platform.defaultCallbackExecutor();
    }

    // 添加你配置的 CallAdapter.Factory 到 List,然后把 Platform 默认的 defaultCallAdapterFactories 添加到 List
    // Make a defensive copy of the adapters and add the default Call adapter.
    List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>(this.callAdapterFactories);
    callAdapterFactories.addAll(platform.defaultCallAdapterFactories(callbackExecutor));

    // 添加 BuiltInConverters 和手动配置的 Converter.Factory 到 List,然后把 Platform 默认的 defaultConverterFactories 添加到 List
    // Make a defensive copy of the converters.
    List<Converter.Factory> converterFactories = new ArrayList<>(
        1 + this.converterFactories.size() + platform.defaultConverterFactoriesSize());

    // Add the built-in converter factory first. This prevents overriding its behavior but also
    // ensures correct behavior when using converters that consume all types.
    converterFactories.add(new BuiltInConverters());
    converterFactories.addAll(this.converterFactories);
    converterFactories.addAll(platform.defaultConverterFactories());

    // 返回一个 Retrofit 对象
    return new Retrofit(callFactory, baseUrl, unmodifiableList(converterFactories),
                        unmodifiableList(callAdapterFactories), callbackExecutor, validateEagerly);
}

```

至此，Retrofit 的创建流程就完成了，它的成员变量的值如下：

- serviceMethodService：暂时为空的 ConcurrentHashMap

- callFactory：默认OkHttpClient 对象

- baseUrl：根据配置的 baseUrl，构建 HttpUrl 对象

- callAdapterFactories：配置的和默认的网络请求适配器工厂集合

- converterFactories：配置的和默认的数据转换器工厂集合

- callbackExecutor：MainThreadExecutor 对象

- validateEagerly：默认 false

## 创建网络请求接口实例

接着来看 GitHubService 实例是如何创建的。

```java
GitHubService service = retrofit.create(GitHubService.class);
```

### Service 创建

`retrofit.create()` 使用了外观模式和代理模式创建了网络请求接口实例。

> 外观模式：定义一个统一接口，外部与通过该统一的接口对子系统里的其他接口进行访问。
>
> 代理模式：通过访问代理对象的方式来间接访问目标对象。

```java
public <T> T create(final Class<T> service) {

    // 对参数 service 进行校验， service 必须是一个接口，而且没有继承别的接口
    Utils.validateServiceInterface(service);
    // 判断是否需要提前验证
    if (validateEagerly) {
        eagerlyValidateMethods(service);
    }
    // 利用动态代理技术，自动生成 Service 接口的实现类，将 Service 接口方法中的参数交给 InvocationHandler 处理
    return (T) Proxy.newProxyInstance(service.getClassLoader(), new Class<?>[] { service },
                                      new InvocationHandler() {
                                          private final Platform platform = Platform.get();
                                          private final Object[] emptyArgs = new Object[0];

                                          @Override public @Nullable Object invoke(Object proxy, Method method,
                                                                                   @Nullable Object[] args) throws Throwable {
                                              // Object 类的方法直接调用
                                              if (method.getDeclaringClass() == Object.class) {
                                                  return method.invoke(this, args);
                                              }
                                              // 如果是对应平台本身类就有的方法，直接调用
                                              if (platform.isDefaultMethod(method)) {
                                                  return platform.invokeDefaultMethod(method, service, proxy, args);
                                              }
                                              // 否则通过 loadServiceMethod 方法获取到对应 ServiceMethod 并 invoke
                                              return loadServiceMethod(method).invoke(args != null ? args : emptyArgs);
                                          }
                                      });
}
```

`retrofit.create()` 返回的是代理对象 Proxy，并转换为 T 类型，即 GitHubService。这里利用了动态代理技术，自动生成 Service 接口的实现类，将 Service 接口方法中的参数交给 InvocationHandler 处理。

对于 Object 类本身独有以及对应平台本身存在的方法，就直接调用，否则通过 `loadServiceMethod()` 对 Service  接口中对应的 method 进行解析处理，之后对其调用 `invoke()` 方法。

可以看出，Retrofit 不是在创建 Service 接口实例时就立即对所有接口中的方法进行注解解析，而是采用了在方法被调用时才进行注解的解析，也就是懒加载。

### validateEagerly 的作用

我们看看 validateEagerly 这个变量，看看它控制着什么。validateEagerly 为 true 会进入 `eagerlyValidateMethods()` 方法。

```java
private void eagerlyValidateMethods(Class<?> service) {
    Platform platform = Platform.get();
    for (Method method : service.getDeclaredMethods()) {
        if (!platform.isDefaultMethod(method) && !Modifier.isStatic(method.getModifiers())) {
            loadServiceMethod(method);
        }
    }
}
```

这里循环取出接口中的 Method，调用 `loadServiceMethod() `，  `loadServiceMethod() ` 先从 serviceMethodCache 获取 Method 对应的 ServiceMethod，如果有直接返回，否则对 Method 进行解析得到一个 ServiceMethod 对象，存入缓存中。

所以 validateEagerly 变量是用于判断是否需要提前验证解析的，默认为 false，如果在 Retrofit 创建时设置为 true，会对 Service 接口中所有方法进行提前解析处理。

### ServiceMethod 创建过程

`loadServiceMethod()` 方法的具体实现如下，这里采用了 Double Check 的方式尝试从 serviceMethodCache 中获取 ServiceMethod 对象，如果获取不到则通过 `ServiceMethod.parseAnnotations()` 方法对该 method 的注解进行处理并将得到的 ServiceMethod 对象加入缓存。

```java
ServiceMethod<?> loadServiceMethod(Method method) {
    // 1. 先从缓存中获取，如果有则直接返回
    ServiceMethod<?> result = serviceMethodCache.get(method);
    if (result != null) return result;
    synchronized (serviceMethodCache) {
        // 2. 这里又获取一次，原因是网络请求一般是多线程环境下，ServiceMethod 可能创建完成了
        result = serviceMethodCache.get(method);
        if (result == null) {
            // 3. 解析方法注解，创建 ServiceMethod
            result = ServiceMethod.parseAnnotations(this, method);
            // 存入缓存
            serviceMethodCache.put(method, result);
        }
    }
    return result;
}
```

我们详细看一下 ServiceMethod 创建过程。 `ServiceMethod.parseAnnotations()` 方法具体实现：

```java
static <T> ServiceMethod<T> parseAnnotations(Retrofit retrofit, Method method) {
    // 通过 RequestFactory 解析注解配置（工厂模式、内部使用了建造者模式）
    RequestFactory requestFactory = RequestFactory.parseAnnotations(retrofit, method);

    Type returnType = method.getGenericReturnType();
    if (Utils.hasUnresolvableType(returnType)) {
        throw methodError(method,
                          "Method return type must not include a type variable or wildcard: %s", returnType);
    }
    if (returnType == void.class) {
        throw methodError(method, "Service methods cannot return void.");
    }
    // HttpServiceMethod 解析注解的方法
    return HttpServiceMethod.parseAnnotations(retrofit, method, requestFactory);
}
```

**1、通过 RequestFactory 解析注解配置**

通过工厂模式和建造者模式创建 RequestFactory，解析封装注解配置。

```java
static RequestFactory parseAnnotations(Retrofit retrofit, Method method) {
    return new Builder(retrofit, method).build();
}

Builder(Retrofit retrofit, Method method) {
    this.retrofit = retrofit;
    this.method = method;
    // 获取网络请求接口方法里的注解
    this.methodAnnotations = method.getAnnotations();
    // 获取网络请求接口方法里的参数类型
    this.parameterTypes = method.getGenericParameterTypes();
    // 获取网络请求接口方法里的注解内容
    this.parameterAnnotationsArray = method.getParameterAnnotations();
}
```

**2、ServiceMethod 的创建**

ServiceMethod 的创建在 HttpServiceMethod 的 `parseAnnotations()` 方法中。 

```java
static <ResponseT, ReturnT> HttpServiceMethod<ResponseT, ReturnT> parseAnnotations(
    Retrofit retrofit, Method method, RequestFactory requestFactory) {
    boolean isKotlinSuspendFunction = requestFactory.isKotlinSuspendFunction;
    boolean continuationWantsResponse = false;
    boolean continuationBodyNullable = false;

    Annotation[] annotations = method.getAnnotations();
    Type adapterType;
    // 如果方法是 kotlin 中的 suspend 方法
    if (isKotlinSuspendFunction) {
        // 获取 Continuation 的范型参数，它就是 suspend 方法的返回值类型
        Type[] parameterTypes = method.getGenericParameterTypes();
        Type responseType = Utils.getParameterLowerBound(0,
                                                         (ParameterizedType) parameterTypes[parameterTypes.length - 1]);
        // 如果 Continuation 的范型参数是 Response，则说明它需要的是 Response，那么将 continuationWantsResponse 置为 true;
        if (getRawType(responseType) == Response.class && responseType instanceof ParameterizedType) {
            // Unwrap the actual body type from Response<T>.
            responseType = Utils.getParameterUpperBound(0, (ParameterizedType) responseType);
            continuationWantsResponse = true;
        } else {
            // TODO figure out if type is nullable or not
            // Metadata metadata = method.getDeclaringClass().getAnnotation(Metadata.class)
            // Find the entry for method
            // Determine if return type is nullable or not
        }

        adapterType = new Utils.ParameterizedTypeImpl(null, Call.class, responseType);
        annotations = SkipCallbackExecutorImpl.ensurePresent(annotations);
    } else {
        // 否则获取方法返回值的范型参数，即为请求需要的返回值的类型
        adapterType = method.getGenericReturnType();
    }

    // 根据网络请求接口方法的返回值和注解类型
    // 从 Retrofit 对象中获取对于的网络请求适配器
    CallAdapter<ResponseT, ReturnT> callAdapter =
        createCallAdapter(retrofit, method, adapterType, annotations);

    // 得到响应类型
    Type responseType = callAdapter.responseType();
    if (responseType == okhttp3.Response.class) {
        throw methodError(method, "'"
                          + getRawType(responseType).getName()
                          + "' is not a valid response body type. Did you mean ResponseBody?");
    }
    if (responseType == Response.class) {
        throw methodError(method, "Response must include generic type (e.g., Response<String>)");
    }
    // TODO support Unit for Kotlin?
    if (requestFactory.httpMethod.equals("HEAD") && !Void.class.equals(responseType)) {
        throw methodError(method, "HEAD method must use Void as response type.");
    }

    // 根据网络请求接口方法的返回值和注解类型从Retrofit对象中获取对应的数据转换器
    Converter<ResponseBody, ResponseT> responseConverter =
        createResponseConverter(retrofit, method, responseType);

    okhttp3.Call.Factory callFactory = retrofit.callFactory;
    if (!isKotlinSuspendFunction) {
        // 不是 suspend 方法的话则直接创建并返回一个 CallAdapted 对象
        return new CallAdapted<>(requestFactory, callFactory, responseConverter, callAdapter);
    } else if (continuationWantsResponse) {
        //noinspection unchecked Kotlin compiler guarantees ReturnT to be Object.
        return (HttpServiceMethod<ResponseT, ReturnT>) new SuspendForResponse<>(requestFactory,
                                                                                callFactory, responseConverter, (CallAdapter<ResponseT, Call<ResponseT>>) callAdapter);
    } else {
        //noinspection unchecked Kotlin compiler guarantees ReturnT to be Object.
        return (HttpServiceMethod<ResponseT, ReturnT>) new SuspendForBody<>(requestFactory,
                                                                            callFactory, responseConverter, (CallAdapter<ResponseT, Call<ResponseT>>) callAdapter,
                                                                            continuationBodyNullable);
    }
}
```

`HttpServiceMethod.parseAnnotations()` 的主要作用就是获取 CallAdapter 以及 Converter 对象，并构建对应 `HttpServiceMethod`。

- CallAdapter ：根据网络接口方法的返回值类型来选择具体要用哪种 CallAdapterFactory，然后获取具体的 CallAdapter。

```java
private static <ResponseT, ReturnT> CallAdapter<ResponseT, ReturnT> createCallAdapter(
      Retrofit retrofit, Method method, Type returnType, Annotation[] annotations) {
    try {
      //noinspection unchecked
      return (CallAdapter<ResponseT, ReturnT>) retrofit.callAdapter(returnType, annotations);
    } catch (RuntimeException e) { // Wide exception range because factories are user code.
      throw methodError(method, e, "Unable to create call adapter for %s", returnType);
    }
  }

public CallAdapter<?, ?> callAdapter(Type returnType, Annotation[] annotations) {
    return nextCallAdapter(null, returnType, annotations);
}

public CallAdapter<?, ?> nextCallAdapter(@Nullable CallAdapter.Factory skipPast, Type returnType,
                                         Annotation[] annotations) {

    int start = callAdapterFactories.indexOf(skipPast) + 1;

    // 遍历 CallAdapter.Factory 集合寻找合适的工厂(该工厂集合在第一步构造 Retrofit 对象时进行添加)
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
        CallAdapter<?, ?> adapter = callAdapterFactories.get(i).get(returnType, annotations, this);
        if (adapter != null) {
            return adapter;
        }
    }
    ...
}
```

- 获取 Converter：根据网络请求接口方法的返回值和注解类型从 Retrofit 对象中获取对应的数据转换器，和创建 CallAdapter 基本一致，遍历 Converter.Factory 集合并寻找具体的 Converter。

```java
private static <ResponseT> Converter<ResponseBody, ResponseT> createResponseConverter(
    Retrofit retrofit, Method method, Type responseType) {
    Annotation[] annotations = method.getAnnotations();
    try {
        return retrofit.responseBodyConverter(responseType, annotations);
    } catch (RuntimeException e) { // Wide exception range because factories are user code.
        throw methodError(method, e, "Unable to create converter for %s", responseType);
    }
}

public <T> Converter<ResponseBody, T> responseBodyConverter(Type type, Annotation[] annotations) {
    return nextResponseBodyConverter(null, type, annotations);
}

public <T> Converter<ResponseBody, T> nextResponseBodyConverter(
      @Nullable Converter.Factory skipPast, Type type, Annotation[] annotations) {

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter<ResponseBody, ?> converter =
          converterFactories.get(i).responseBodyConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<ResponseBody, T>) converter;
      }
    }
    ...
}
```

- 构建 HttpServiceMethod：根据是否是 kotlin suspend 方法分别返回不同类型的 HttpServiceMethod。如果不是 suspend 方法的话则直接创建并返回一个 CallAdapted 对象，否则根据 suspend 方法需要的是 Response 还是具体的类型，分别返回 SuspendForResponse 和 SuspendForBody 对象。

### ServiceMethod.invoke()

ServiceMethod 是一个抽象类，`invoke()` 是一个抽象方法，具体实现在子类中。

```java
abstract class ServiceMethod<T> {
  abstract @Nullable T invoke(Object[] args);
}
```

它的子类是 HttpServiceMethod，HttpServiceMethod 的 `invoke()` 方法中，首先构造一个 OkHttpCall，然后通过 `adapt()` 方法实现对 Call 的转换。

```java
abstract class HttpServiceMethod<ResponseT, ReturnT> extends ServiceMethod<ReturnT> {
    @Override
    final @Nullable ReturnT invoke(Object[] args) {
        Call<ResponseT> call = new OkHttpCall<>(requestFactory, args, callFactory, responseConverter);
        return adapt(call, args);
    }

    protected abstract @Nullable ReturnT adapt(Call<ResponseT> call, Object[] args);
}
```

`adapt()` 是一个 抽象方法，所以具体实现在 HttpServiceMethod 的子类中。

HttpServiceMethod 有三个子类，非协程的情况是 CallAdapted，另外两个子类则是在使用协程的情况下为了配合协程的 SuspendForResponse 以及 SuspendForBody 类。

- CallAdapted：通过传递进来的 CallAdapter 对 Call 进行转换。

- SuspendForResponse：首先根据传递进来的 Call 构造了一个参数为 Response 的 Continuation 对象然后通过 Kotlin 实现的 `awaitResponse()` 方法将 call 的 `enqueue` 异步回调过程封装成 一个 suspend 的函数。

- SuspendForBody：SuspendForBody 则是根据传递进来的 Call 构造了一个 Continuation 对象然后通过 Kotlin 实现的 `await()` 或 `awaitNullable()` 方法将 call 的 `enqueue` 异步回调过程封装为了一个 suspend 的函数。

## 发起网络请求

### 创建 Call

```java
Call<List<Repo>> repos = service.listRepos("octocat");
```

从前面的分析了解到，Service 对象是动态代理对象，当调用 `listRepos()` 方法时会调用到 InvocationHandler的 `invoke()` 方法，得到最终的 Call 对象。

如果没有传入 CallAdapter 的话，默认情况返回的 Call 是 OkHttpCall 对象，它实现了 Call 接口。

### 同步请求

```java
@Override
public Response<T> execute() throws IOException {
    okhttp3.Call call;

    synchronized (this) {
        if (executed) throw new IllegalStateException("Already executed.");
        executed = true;

        if (creationFailure != null) {
            if (creationFailure instanceof IOException) {
                throw (IOException) creationFailure;
            } else if (creationFailure instanceof RuntimeException) {
                throw (RuntimeException) creationFailure;
            } else {
                throw (Error) creationFailure;
            }
        }

        call = rawCall;
        if (call == null) {
            try {
                // 1. 创建 OkHttp 的 Call 对象
                call = rawCall = createRawCall();
            } catch (IOException | RuntimeException | Error e) {
                throwIfFatal(e); //  Do not assign a fatal error to creationFailure.
                creationFailure = e;
                throw e;
            }
        }
    }

    if (canceled) {
        call.cancel();
    }

    // 2. 执行请求并解析返回结果
    return parseResponse(call.execute());
}
```

很简单，主要就是创建 OkHttp 的 Call 对象，调用 Call 的 `execute` 方法，对 Response 进行解析返回。

### 异步请求

```java
@Override
public void enqueue(final Callback<T> callback) {
    checkNotNull(callback, "callback == null");

    okhttp3.Call call;
    Throwable failure;

    synchronized (this) {
        if (executed) throw new IllegalStateException("Already executed.");
        executed = true;

        call = rawCall;
        failure = creationFailure;
        if (call == null && failure == null) {
            try {
                // 1. 创建 OkHttp 的 Call 对象
                call = rawCall = createRawCall();
            } catch (Throwable t) {
                throwIfFatal(t);
                failure = creationFailure = t;
            }
        }
    }

    if (failure != null) {
        callback.onFailure(this, failure);
        return;
    }

    if (canceled) {
        call.cancel();
    }

    // 2. 调用 Call 的异步执行方法
    call.enqueue(new okhttp3.Callback() {
        @Override
        public void onResponse(okhttp3.Call call, okhttp3.Response rawResponse) {
            Response<T> response;
            try {
                // 3. 解析返回结果
                response = parseResponse(rawResponse);
            } catch (Throwable e) {
                throwIfFatal(e);
                callFailure(e);
                return;
            }

            try {
                // 4. 执行回调
                callback.onResponse(OkHttpCall.this, response);
            } catch (Throwable t) {
                throwIfFatal(t);
                t.printStackTrace(); // TODO this is not great
            }
        }

        @Override
        public void onFailure(okhttp3.Call call, IOException e) {
            callFailure(e);
        }

        private void callFailure(Throwable e) {
            try {
                callback.onFailure(OkHttpCall.this, e);
            } catch (Throwable t) {
                throwIfFatal(t);
                t.printStackTrace(); // TODO this is not great
            }
        }
    });
}
```

也很简单，主要就是创建 OkHttp 的 Call 对象，调用 Call 的 `enqueue` 方法，解析返回结果，执行回调。

## 总结

至此，Retrofit 的源码基本上就看完了，虽然还有很多细节没有提及，但 Retrofit 的整体流程很清晰了。

Retrofit 本质上是一个 RESTful 的 Http 网络请求框架的封装，通过大量的设计模式封装了 OkHttp，使得更加简单易用。它内部主要是用动态代理的方式，动态将网络请求接口的注解解析成 HTTP 请求，最后执行请求的过程。

建议将 Retrofit 的源码下载下来，使用 IDEA 可以直接打开阅读。我这边已经将源码下载下来，进行了注释说明，有需要的可以直接从 [Android open framework analysis](https://github.com/zywudev/android-open-framework-analysis) 查看。

## 参考

1、[Android开源框架源码鉴赏：Retrofit](https://github.com/sucese/android-open-framework-analysis/blob/master/doc/Android开源框架源码鉴赏：Retrofit.md)

2、[Android：手把手带你 深入读懂 Retrofit 2.0 源码](https://www.jianshu.com/p/0c055ad46b6c)

3、[带你一步步剖析Retrofit 源码解析：一款基于 OkHttp 实现的网络请求框架](https://zhuanlan.zhihu.com/p/101508725)