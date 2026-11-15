# XiHan Framework SDK for Java

[XiHan Framework Spec](https://github.com/XiHanFun/framework-spec) 的 Java 实现：原语层、清单、调用上下文、管道与操作契约。用它可以把同一套原语用在 JVM 应用里；下一轮加上进程外插件运行时后，也能写在 .NET 宿主（[XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework)）里运行的 Java 插件。规范以 submodule 放在 `spec/`，一致性由规范仓库的向量验证；形状与语义的参照是 [framework-sdk-go](https://github.com/XiHanFun/framework-sdk-go)。

JDK 21+，Maven 坐标 `com.xihanfun:xihan-framework-<模块>`（Maven Central 命名空间 `com.xihanfun` 已验证，尚未发布）：

```xml
<dependency>
  <groupId>com.xihanfun</groupId>
  <artifactId>xihan-framework-operation</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

| 模块 | Java 包 | 内容 | 规范 |
| --- | --- | --- | --- |
| `xihan-framework-primitives` | `com.xihanfun.framework.primitives` | `Error` / `ErrorCode`、`Result<T>`、`Maybe<T>`、`TypedId<Tag, V>`、`LifecycleState`、`PipelineStage`、`Stability`、`BaggageKeys`、消息键与模板（`MessageTemplate`、`MessageCatalog`、`MessageScope`）、`FrameworkException`、`FrameworkInfo` | RFC-0001、RFC-0010、`conventions/` |
| | `com.xihanfun.framework.primitives.json` | 线上 JSON 编码：Jackson 模块 `PrimitivesModule`（Jackson 是可选依赖） | RFC-0001「线上编码」 |
| `xihan-framework-manifest` | `com.xihanfun.framework.manifest` | `xihan.manifest.json` 的模型、按 Schema 严格读取、规范化输出与校验，能力声明与依赖文本 | RFC-0002、`manifest/manifest.schema.json` |
| `xihan-framework-invocation` | `com.xihanfun.framework.invocation` | `Context`：取消与截止时间（按令牌归因为 `KERNEL_TIMEOUT_001` / `KERNEL_CANCELLED_001`，可登记回调）、W3C 追踪、已登记的 Baggage、对象袋 `Items`、按派生传递的值 | RFC-0003 |
| `xihan-framework-pipeline` | `com.xihanfun.framework.pipeline` | 十一阶段的 `PipelineBuilder`：`use` / `useAt` / `useNamedInstance`、`before` / `after`（类型名或实例名）、`describe` / `dump`、`build`；排序规则与错误码 `KERNEL_PIPELINE_00x` | RFC-0004 |
| `xihan-framework-operation` | `com.xihanfun.framework.operation` | `OperationContract`、`OperationSchema`、`OperationHandler<Req, Res>`、容量 10000 的 `OperationRegistry`、经完整管道执行的 `OperationInvoker`（XGI：JSON 请求与响应，另有类型化入口） | RFC-0011 |

依赖方向：`operation → pipeline → invocation → primitives`，`manifest → primitives`；每个模块带 `module-info.java`。

## 在 Java 应用里用原语

```java
record CreateOrder(int quantity) { }
record OrderCreated(String id, int quantity) { }

OperationRegistry registry = new OperationRegistry();
registry.add(OperationContract.of("orders.create").withStability(Stability.EXPERIMENTAL).withExpose(Expose.HTTP),
        CreateOrder.class, OrderCreated.class,
        (context, request) -> request.quantity() > 0
                ? Result.ok(new OrderCreated("o-" + context.correlation().orElse(""), request.quantity()))
                : Result.fail(Error.validation("ORDERS_VALIDATION_001", "orders.quantity.invalid", Map.of("quantity", request.quantity()))));

PipelineBuilder pipeline = new PipelineBuilder()
        .useAt(Middleware.of("Observe", (context, next) -> next.invoke(context)), PipelineStage.OBSERVE, Placement.END, PipelineBuilder.APP_OWNER);
OperationInvoker invoker = new OperationInvoker(registry, pipeline);

Context context = Context.builder().timeout(Duration.ofSeconds(2)).baggage(BaggageKeys.CORRELATION, "c1").build();
OperationResponse response = invoker.invoke(context, OperationRequest.of("orders.create", "{\"quantity\":2}"));
// response.payloadText() == Optional[{"id":"o-c1","quantity":2}]
```

- 可恢复失败返回 `Result` / `Error`，不抛异常。错误码按 `<领域大写>_<KIND>_<NNN>`，文案用消息键 + 语言文件（RFC-0010），`Error.message()` 在没有已解析文案时按 `MessageScope` 的进程默认目录解析，再退回 `键 (参数)` 的描述。
- 配置错误（清单不合法、管道成环、操作重复登记、登记表已满）抛出带错误码的 `FrameworkException`；参数格式不合法这类调用方的编程错误抛 `IllegalArgumentException`，状态不允许抛 `IllegalStateException`。消息是英文句子，标识符加单引号。
- `Result<T>`、`Error`、`Maybe<T>`、`TypedId` 的 JSON 编码与 .NET 参考实现和 Go SDK 一致（`{"ok":true,"value":…}` / `{"ok":false,"error":{…}}`）：`new ObjectMapper().registerModule(new PrimitivesModule())`。生命周期迁移表、管道阶段顺序、错误码、Baggage 键与能力依赖文法由 `spec/conformance/vectors` 验证；清单的规范化输出与 Go SDK 逐字节相同。
- 注意 `com.xihanfun.framework.primitives.Error` 与 `java.lang.Error` 同名：在用到它的文件里显式导入；它不是 `Throwable`，误用在编译期就报错。

## 设计取舍

**并发：虚拟线程上的同步签名，不用 `CompletableFuture`。** 中间件、处理器与调用器都是同步方法（`Result<Unit> invoke(Context, Step)`、`Result<Res> handle(Context, Req)`），按 Java 21 虚拟线程（JEP 444）设计：阻塞等待 I/O 时虚拟线程让出载体线程，不占平台线程，写法与 Go SDK 一一对应、调用栈与异常栈可读，不需要把每一层都改成回调链。下一轮的插件运行时让 grpc-java 服务端以 `Executors.newVirtualThreadPerTaskExecutor()` 执行调用即可，公开 API 不必改。为此：构建出的 `Step`、`OperationInvoker`、`OperationRegistry` 不可变或线程安全，可以被任意多个线程同时使用；`Context` 不可变、按派生传递，每次操作调用的状态随派生的上下文传递（`Context.withValue`），嵌套与并发调用互不干扰；锁一律用 `ReentrantLock`，不在 `synchronized` 里阻塞（Java 21 上 `synchronized` 会钉住虚拟线程）。

**取消与截止时间自己建模。** `Context` 有两个令牌：截止令牌由定时器在截止时间触发，外部令牌由 `CancellableContext.cancel()` 触发；父级取消连带子级，子级取消不影响父级。归因只看令牌（RFC-0003 规则 1、RFC-0004、ADR-0095）：截止令牌已触发即 `KERNEL_TIMEOUT_001`，不论调用方是否也已取消；只有外部令牌触发时为 `KERNEL_CANCELLED_001`。`onCancelled` 登记回调（已取消时立即执行），`awaitCancellation` 在虚拟线程上等待；`withCancellation` / `withDeadline` / `withTimeout` 返回的 `CancellableContext` 用完要关闭（try-with-resources），免得长寿命的父级积攒已结束的子级。

**JSON：Jackson。** Java 标准库没有 JSON；XGI 载荷、清单读取与线上编码都需要它。选 `jackson-databind` 是因为它是 JVM 上事实标准、本身是 JPMS 模块、支持记录类型，grpc-java 等常用库的使用者通常已经有它。原语核心类型不依赖 Jackson（RFC-0001：原语层不含序列化代码）：编码在 `primitives.json` 包里，Jackson 在 primitives 模块是可选依赖；manifest 只用树模型手工映射、不做反射绑定；operation 用 `OperationJson.newMapper()`——注册原语编码、忽略未知字段（与 Go 和 .NET 一致）、类型不符一律失败（不把字符串转数字、不截断小数、不把数字转字符串）。模块路径上的应用要把请求与响应类型所在的包 `opens` 给 `com.fasterxml.jackson.databind`。

**可空性。** 公开成员不接受也不返回 `null`：可能没有的值以 `Optional` 返回（`findBaggage`、`find`、`tenant()`），需要线上编码的数据字段用 `Maybe`（编码为值或 `null`，对象里缺这个字段也解码为无值），集合没有时是空集合；参数传 `null` 抛 `NullPointerException`。

**有界。** 对象袋最多 1024 个条目、Baggage 最多 180 个、按派生的值最多 256 个键、管道最多 1024 个中间件与 4096 条约束、操作登记表 10000 个操作，超出是编程或配置错误。

## 开发

本机没有 Maven 时用 Docker 官方镜像构建与测试，命名卷缓存依赖：

```bash
git submodule update --init          # spec/
```

PowerShell：

```powershell
docker run --rm -v "${PWD}:/src" -v xihan-sdk-java-m2:/root/.m2 -w /src maven:3-eclipse-temurin-21 mvn -B verify
```

Git Bash（关掉路径转换）：

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/src" -v xihan-sdk-java-m2:/root/.m2 -w /src maven:3-eclipse-temurin-21 mvn -B verify
```

有 Maven 3.9+ 与 JDK 21+ 时直接 `mvn -B verify`。门禁：`maven.compiler.release` 21，编译带 `-Xlint:all -Werror` 与 `-Xdoclint:all,-missing`；测试是 JUnit 5（JUnit Platform），在类路径上运行，一致性向量按仓库根的相对路径读取（不复制向量文件）。容器以 root 写出的 `target/` 不提交。

## 状态

`0.1.0-SNAPSHOT`：原语、清单、调用上下文、管道与操作五个模块可用；进程外插件运行时（`plugin`，`xihan.plugin.v1`，grpc-java）与远程 Provider 的操作集（首个是 `caching.distributed@1`）规划中，示例随它们提供。正式发布时去掉 `-SNAPSHOT`，以 `v0.1.0` 标签发布到 Maven Central。

## 参与

1. 先读 framework-spec 的 README 与各 RFC，再看 framework-sdk-go 的包划分。
2. 公开契约的变更不在 SDK 仓库里做：走 framework-spec 的 RFC 流程，再同步到各 SDK。

## 许可

[MIT](LICENSE)
