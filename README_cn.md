<div align="center">

<h1>XiHan Framework SDK for Java</h1>

<h3>与 .NET 宿主相同的原语、清单与管道，由同一套一致性向量验证</h3>

<p>
  <a href="https://github.com/XiHanFun/framework-sdk-java/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/XiHanFun/framework-sdk-java/ci.yml?branch=main&style=flat-square&label=CI" /></a>
  <img alt="JDK 21+" src="https://img.shields.io/badge/JDK-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" />
  <a href="./LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/XiHanFun/framework-sdk-java?style=flat-square&color=green" /></a>
  <a href="https://github.com/XiHanFun/framework-sdk-java/stargazers"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/XiHanFun/framework-sdk-java?style=flat-square&logo=github&label=Stars&color=1f6feb" /></a>
</p>

<p>
  <a href="https://framework.docs.xihanfun.com/sdks/java"><b>文档</b></a> ·
  <a href="#快速开始"><b>快速开始</b></a> ·
  <a href="https://github.com/XiHanFun/framework-spec"><b>规范</b></a> ·
  <a href="https://github.com/XiHanFun/XiHan.Framework"><b>参考实现</b></a>
</p>

<p><a href="./README.md">English</a> | <b>简体中文</b></p>

</div>

[XiHan Spec](https://github.com/XiHanFun/framework-spec) 的 Java 实现：原语层、清单、调用上下文、管道、操作契约与进程外插件运行时。用它可以把与 .NET 宿主（参考实现 [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework)）相同的原语用在 JVM 应用里，也能写在 .NET 宿主里运行的 Java 插件。规范以 submodule 放在 `spec/`，一致性由规范仓库的一致性向量与黑盒运行器验证。

## 模块

整个 SDK 是一个 Maven 构件 `com.xihanfun:xihan-framework`，JPMS 模块名 `com.xihanfun.framework`，按 Java 包划分：

| Java 包 | 内容 | 规范 |
| --- | --- | --- |
| `com.xihanfun.framework.primitives` | `Error`、`Result<T>`、`Maybe<T>`、`TypedId`、生命周期状态、消息键与模板 | RFC-0001、RFC-0010 |
| `com.xihanfun.framework.primitives.json` | 线上 JSON 编码：Jackson 模块 `PrimitivesModule` | RFC-0001 |
| `com.xihanfun.framework.manifest` | `xihan.manifest.json` 的读取、写出与校验 | RFC-0002 |
| `com.xihanfun.framework.invocation` | `Context`：取消与截止时间、W3C 追踪、已登记的 Baggage | RFC-0003 |
| `com.xihanfun.framework.pipeline` | 十一阶段的 `PipelineBuilder` 与排序规则 | RFC-0004 |
| `com.xihanfun.framework.operation` | 操作契约、`OperationRegistry` 与 `OperationInvoker` | RFC-0011 |
| `com.xihanfun.framework.plugin` | 进程外插件运行时 `PluginRuntime.serve`，基于 grpc-java | RFC-0017 |

普通依赖只有 `jackson-databind`。grpc-java 与 protobuf-java 只有插件运行时用到，是可选依赖，写插件的应用自己加上。

## 安装

需要 JDK 21+。Maven Central 命名空间 `com.xihanfun` 已验证，构件尚未发布：

```xml
<dependency>
  <groupId>com.xihanfun</groupId>
  <artifactId>xihan-framework</artifactId>
  <version>${xihan-framework.version}</version>
</dependency>
```

插件运行时的依赖与模块路径上的注意事项见文档站的 [Java SDK](https://framework.docs.xihanfun.com/sdks/java) 页。

## 快速开始

在 Java 应用里用原语：

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

可恢复失败返回 `Result` / `Error`，不抛异常。要写在 .NET 宿主里运行的插件，见文档页与示例插件 [`examples/echo`](./examples/echo)。

## 文档

| 去处 | 内容 |
| --- | --- |
| [Java SDK](https://framework.docs.xihanfun.com/sdks/java) | 安装、模块、写一个插件、与宿主共用的行为、语言映射、设计取舍、一致性、破坏性改动与迁移 |
| [进程外插件](https://framework.docs.xihanfun.com/plugins/out-of-process) | 宿主一侧：协议、握手与生命周期、代理操作、回调与失败降级 |
| [规范与多语言](https://framework.docs.xihanfun.com/appendix/spec) | 规范怎样组织、一致性档与各语言 SDK |
| [XiHan Spec](https://github.com/XiHanFun/framework-spec) | RFC、约定、清单 Schema、插件协议、一致性向量与黑盒运行器 |

## 状态

在有钉在规范标签上的发布版之前是 Experimental（RFC-0024）。本 SDK 认领一致性档 `core` 与 `plugin`，没有缺口，钉的规范版本是 `spec/` 子模块指向的提交；细节见文档页的「一致性」一节。

## 生态

| 项目 | 说明 |
| --- | --- |
| [framework-spec](https://github.com/XiHanFun/framework-spec) | XiHan Spec：RFC、术语表、协议映射与一致性向量 |
| [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework) | .NET 参考实现 |
| [framework-sdk-go](https://github.com/XiHanFun/framework-sdk-go) | Go SDK |
| [framework-sdk-typescript](https://github.com/XiHanFun/framework-sdk-typescript) | TypeScript SDK |
| [framework-sdk-rust](https://github.com/XiHanFun/framework-sdk-rust) | Rust SDK |
| [framework-sdk-python](https://github.com/XiHanFun/framework-sdk-python) | Python SDK |
| [framework-sdk-php](https://github.com/XiHanFun/framework-sdk-php) | PHP SDK |

## 参与

欢迎提交 [Issue](https://github.com/XiHanFun/framework-sdk-java/issues) 与 Pull Request。公开契约的变更不在 SDK 仓库里做：先走 framework-spec 的 RFC 流程，再同步到各 SDK。开发约定、门禁与发布流程见 [AGENTS.md](./AGENTS.md)。

## 许可证

[MIT](./LICENSE) © 2021-Present XiHanFun and contributors。
