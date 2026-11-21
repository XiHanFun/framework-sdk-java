<div align="center">

<h1>XiHan Framework SDK for Java</h1>

<h3>The same primitives, manifests and pipeline as the .NET host, verified by shared conformance vectors</h3>

<p>
  <a href="https://github.com/XiHanFun/framework-sdk-java/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/XiHanFun/framework-sdk-java/ci.yml?branch=main&style=flat-square&label=CI" /></a>
  <img alt="JDK 21+" src="https://img.shields.io/badge/JDK-21%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" />
  <a href="./LICENSE"><img alt="MIT License" src="https://img.shields.io/github/license/XiHanFun/framework-sdk-java?style=flat-square&color=green" /></a>
  <a href="https://github.com/XiHanFun/framework-sdk-java/stargazers"><img alt="GitHub Stars" src="https://img.shields.io/github/stars/XiHanFun/framework-sdk-java?style=flat-square&logo=github&label=Stars&color=1f6feb" /></a>
</p>

<p>
  <a href="https://framework.docs.xihanfun.com/sdks/java"><b>Docs</b></a> ·
  <a href="#quick-start"><b>Quick Start</b></a> ·
  <a href="https://github.com/XiHanFun/framework-spec"><b>Specification</b></a> ·
  <a href="https://github.com/XiHanFun/XiHan.Framework"><b>Reference Implementation</b></a>
</p>

<p><b>English</b> | <a href="./README_cn.md">简体中文</a></p>

</div>

The Java implementation of [XiHan Spec](https://github.com/XiHanFun/framework-spec): the primitives layer, manifests, the invocation context, the pipeline, operation contracts and the out-of-process plugin runtime. Use it to bring the same primitives as the .NET host (the reference implementation, [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework)) into JVM applications, or to write Java plugins that run in the .NET host. The spec lives in `spec/` as a submodule, and conformance is verified by the conformance vectors and the black-box runner of the spec repository.

## Modules

The whole SDK is a single Maven artifact, `com.xihanfun:xihan-framework`, with the JPMS module name `com.xihanfun.framework`, divided into Java packages:

| Java package | Contents | Spec |
| --- | --- | --- |
| `com.xihanfun.framework.primitives` | `Error`, `Result<T>`, `Maybe<T>`, `TypedId`, lifecycle states, message keys and templates | RFC-0001, RFC-0010 |
| `com.xihanfun.framework.primitives.json` | Wire JSON encoding: the Jackson module `PrimitivesModule` | RFC-0001 |
| `com.xihanfun.framework.manifest` | Reading, writing and validating `xihan.manifest.json` | RFC-0002 |
| `com.xihanfun.framework.invocation` | `Context`: cancellation and deadlines, W3C tracing, registered baggage | RFC-0003 |
| `com.xihanfun.framework.pipeline` | The eleven-stage `PipelineBuilder` and its ordering rules | RFC-0004 |
| `com.xihanfun.framework.operation` | Operation contracts, `OperationRegistry` and `OperationInvoker` | RFC-0011 |
| `com.xihanfun.framework.plugin` | The out-of-process plugin runtime `PluginRuntime.serve`, on grpc-java | RFC-0017 |

The only regular dependency is `jackson-databind`. grpc-java and protobuf-java, used only by the plugin runtime, are optional dependencies that applications writing plugins add themselves.

## Installation

Requires JDK 21+. The artifact is on Maven Central and only prereleases are published so far; replace `${xihan-framework.version}` with a version from [Releases](https://github.com/XiHanFun/framework-sdk-java/releases) (without the `v`):

```xml
<dependency>
  <groupId>com.xihanfun</groupId>
  <artifactId>xihan-framework</artifactId>
  <version>${xihan-framework.version}</version>
</dependency>
```

The dependencies for the plugin runtime and the notes for the module path are on the [Java SDK](https://framework.docs.xihanfun.com/sdks/java) page.

## Quick Start

Using the primitives in a Java application:

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

Recoverable failures return `Result` / `Error` instead of throwing. To write a plugin that runs in the .NET host, see the docs page and the example plugin [`examples/echo`](./examples/echo).

## Documentation

The documentation site is in Chinese.

| Where | Contents |
| --- | --- |
| [Java SDK](https://framework.docs.xihanfun.com/sdks/java) | Installation, modules, writing a plugin, behavior shared with the host, language mapping, design, conformance, breaking changes and migration |
| [Out-of-process plugins](https://framework.docs.xihanfun.com/plugins/out-of-process) | The host side: protocol, handshake and lifecycle, proxied operations, callbacks and degradation |
| [Spec and languages](https://framework.docs.xihanfun.com/appendix/spec) | How the spec is organized, conformance profiles, and the SDKs for each language |
| [XiHan Spec](https://github.com/XiHanFun/framework-spec) | RFCs, conventions, the manifest schema, the plugin protocol, conformance vectors and the black-box runner |

## Status

The maturity is Experimental (RFC-0024): there is a prerelease pinned to a spec rc tag, and Stable also needs a release pinned to a formal spec tag. This SDK claims the conformance profiles `core` and `plugin` with no gaps, and pins the spec at the commit of the `spec/` submodule; details are in the conformance section of the docs page.

## Ecosystem

| Project | Description |
| --- | --- |
| [framework-spec](https://github.com/XiHanFun/framework-spec) | XiHan Spec: RFCs, vocabulary, protocol mappings and conformance vectors |
| [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework) | The .NET reference implementation |
| [framework-sdk-go](https://github.com/XiHanFun/framework-sdk-go) | Go SDK |
| [framework-sdk-typescript](https://github.com/XiHanFun/framework-sdk-typescript) | TypeScript SDK |
| [framework-sdk-rust](https://github.com/XiHanFun/framework-sdk-rust) | Rust SDK |
| [framework-sdk-python](https://github.com/XiHanFun/framework-sdk-python) | Python SDK |
| [framework-sdk-php](https://github.com/XiHanFun/framework-sdk-php) | PHP SDK |

## Contributing

[Issues](https://github.com/XiHanFun/framework-sdk-java/issues) and pull requests are welcome. Changes to public contracts are not made in the SDK repositories: they go through the RFC process of framework-spec first and are then synced to each SDK. Development conventions, gates and the release process are in [AGENTS.md](./AGENTS.md).

## License

[MIT](./LICENSE) © 2021-Present XiHanFun and contributors.
