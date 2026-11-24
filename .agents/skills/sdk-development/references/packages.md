# 包、规范、向量与测试

源码在 `src/main/java/com/xihanfun/framework/`，测试在 `src/test/java/com/xihanfun/framework/` 的同名包里；读向量的测试辅助只有一份，`com.xihanfun.framework.Vectors`（`src/test/java/com/xihanfun/framework/Vectors.java`，与 `VectorCoverageTest` 同包），各测试包导入它，按仓库根的相对路径读 `spec/conformance/vectors/`，不在测试包里另写一份。

| Java 包 | 主要类型 | 规范 | 回放的向量 | 测试 |
| --- | --- | --- | --- | --- |
| `primitives` | `Error`、`ErrorCode`、`Result`、`Maybe`、`TypedId`、`Unit`、`LifecycleState`、`PipelineStage`、`Stability`、`BaggageKeys`、`MessageTemplate`、`MessageCatalog`、`MessageScope`、`MessageArguments`、`KernelMessages`、`JsonText`、`JsonValues`、`FrameworkException`、`FrameworkInfo` | RFC-0001、RFC-0010「模板」、`conventions/error-codes.md`、`conventions/baggage-keys.md` | `primitives/error-codes.json`、`lifecycle-transitions.json`、`baggage-keys.json`、`json-text.json`、`message-templates.json`、`kernel-messages.json`、`wire-encoding.json` | `PrimitivesTest`、`JsonTextVectorsTest`、`MessageTemplateVectorsTest`、`KernelMessagesVectorsTest`、`WireEncodingVectorsTest` |
| `primitives.json` | `PrimitivesModule`、`PrimitivesJson` 与各类型的编解码器 | RFC-0001「Result」 | `primitives/wire-encoding.json` | `PrimitivesModuleTest`、`WireEncodingVectorsTest` |
| `manifest` | `Manifest`、`ManifestJson`、`ManifestShape`、`CapabilityId`、`CapabilityRequirement`、`InterfaceVersions` | RFC-0002、`manifest/manifest.schema.json`、`conventions/capability-ids.md` | `manifest/manifests.json`、`primitives/capability-requirements.json` | `ManifestTest`、`ManifestVectorsTest` |
| `invocation` | `Context`、`CancellableContext`、`TraceParent`、`Items`、`Deadlines`、`InvocationErrors`、`InvocationMessages` | RFC-0003 | `primitives/trace-context.json`、`baggage-keys.json`、`kernel-messages.json` | `ContextTest`（测试辅助 `DeadlineSignals` 不动时钟直接触发截止令牌） |
| `pipeline` | `PipelineBuilder`、`Middleware`、`Placement`、`Step`、`PipelineErrors` | RFC-0004 | `pipeline/ordering.json` | `PipelineBuilderTest`、`OrderingVectorsTest` |
| `operation` | `OperationContract`、`OperationId`（文法与 128 个字符的上限 `MAX_LENGTH`）、`OperationSchema`、`OperationHandler`、`OperationRegistry`、`OperationInvoker`、`OperationRequest`、`OperationResponse`、`Operations`、`OperationJson`、`RequestShape`，内置步骤 `ObserveMiddleware`、`ValidationMiddleware`、`DeadlineMiddleware`，`OperationMessages`、`OperationErrors` | RFC-0011、`conventions/operation-ids.md` | `operations/payload-reasons.json`、`primitives/kernel-messages.json`、`primitives/operation-ids.json` | `OperationInvokerTest`、`OperationIdVectorsTest`、`PayloadReasonsVectorsTest`、`ResponseEncodingTest`、`UnhandledExceptionTest`、`KernelMessagesTest` |
| `plugin` | 见 plugin-runtime 技能 | RFC-0017、`spec/plugin/` | `plugin/*.json` | 见 plugin-runtime 技能 |
| `internal` | `Whitespace`、`WireNames`（各枚举 `parse` 共用的按线上名字查找）、`Grammars`（不止一个包要核对的文法，如操作 ID 连同它的长度上限） | RFC-0001「空白字符」、RFC-0011「操作 ID」 | 经 `wire-encoding.json`、`handshake-lines.json`、`manifests.json`、`operation-ids.json` 间接覆盖 | 随使用它的包 |

`VectorCoverageTest`（`src/test/java/com/xihanfun/framework/`）的 `REPLAYED` 是回放清单的唯一登记处，注释写读取它的测试类；新增回放时同时改这里。

## 模块描述

`src/main/java/module-info.java`：

- 导出 `primitives`、`primitives.json`、`manifest`、`invocation`、`pipeline`、`operation`、`plugin`；`internal` 与 `plugin.v1` 不导出，`plugin.v1` 只 `opens` 给 `com.google.protobuf`。
- `requires transitive com.fasterxml.jackson.databind`（`ObjectMapper` 出现在公开构造参数里，`PrimitivesModule` 继承 Jackson 的模块类型）。
- gRPC 与 protobuf 是 `requires static` 的自动模块，外加 `requires java.logging`（插件日志改写 `java.util.logging`）。
- 新增导出包要同时改这里，并在文档页「模块」一节补一行。

## 参考实现的位置

.NET 的对应实现在 `../XiHan.Framework/framework/src/`：原语层 `XiHan.Framework.Primitives`，内核（清单、管道、操作、本地化、JSON）`XiHan.Framework.Kernel`，插件协议与宿主 `XiHan.Framework.Plugins.Protocol`、`XiHan.Framework.Plugins.OutOfProcess`。行为有疑问时以这些源码和它们回放的同一份向量为准。
