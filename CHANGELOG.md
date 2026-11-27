# 更新日志 · XiHan Framework SDK for Java

本页记录 `com.xihanfun:xihan-framework` 各版本中使用者可感知的变更，每条标注 **新增 / 修复 / 优化 / 调整 / 移除** 类别；仓库自身的 CI、测试与构建脚本不列入。版本号见 `pom.xml`，详细说明见文档站 [Java SDK](https://framework.docs.xihanfun.com/sdks/java) 的「破坏性改动与迁移」。

## 未发布

### 升级须知

- `Result` 与 `Error` 的字符串有孤立的代理项、从根算起嵌套超过 64 层或有重复成员即写不出、不写出文本：经 `PrimitivesJson.write` 抛 `IllegalArgumentException`，直接用 `ObjectMapper` 时抛原因为它的 `JsonMappingException`；写出之前去掉这类值（ADR-0320、ADR-0340）
- 已登记 Baggage 键的值有孤立的代理项时，`Context.Builder.baggage` 丢弃这个成员、`withBaggage` 抛 `IllegalArgumentException`：从外部输入拼值之前先去掉（ADR-0321）
- `Envelope.time()` 改为 `Optional<Instant>`，`Builder.time(String)` 换成 `time(Instant)`：传 `Instant`，要文本时自己格式化（ADR-0306）
- `ResolvedBinding` 新增记录组件 `properties`：按三个组件解构的记录模式补上第四个；Jackson 写出绑定只有 `name`、`type` 与 `propertyNames`，要值的自己取 `value()` 与 `properties()`（ADR-0285）
- 操作 ID 超过 128 个字符时 `OperationId.validate`、`OperationContract.of` 抛 `IllegalArgumentException`，读清单为 `kernel.manifest.invalid`：把 ID 缩到 128 个字符以内（ADR-0276）
- `OperationRequest.of(String, String)` 与 `Envelope.Builder.data(String)` 严格编码成 UTF-8，孤立的代理项不再换成 `?`，`Host.invoke` 与 `Host.publish` 在本地失败、不发出：要发出的文本里不要有孤立的代理项（ADR-0312、ADR-0315）
- `Envelope.toString()` 有 `dataschema` 时多一段 `, dataSchema=…`，`ResolvedBinding.toString()` 多一段属性名：按旧文本断言的地方更新
- 回调客户端关闭之后的回调，`plugins.host.unavailable` 的 `reason` 由 `Unavailable: the plugin runtime has stopped` 改为与其他 SDK 相同的 `Unavailable: the host client is closed`：按旧文本判断的地方更新（ADR-0340）

### 变更

- **修复** 插件写给宿主的自由文本错误文案（含内部错误的）有孤立的代理项时各换成 U+FFFD 写出，不再换成 `?`（ADR-0339）
- **新增** `PrimitivesJson.write(ObjectMapper, Result)` 与 `write(ObjectMapper, Error)`：严格写出线上编码，写不出抛 `IllegalArgumentException`，Jackson 的异常留作原因（ADR-0340）
- **修复** 插件写给宿主的错误细节有孤立的代理项或超过 64 层时不带细节，其余字段照写，不再把孤立的代理项换成 `?`（ADR-0320）
- **新增** `PluginMessages.ENVELOPE_INVALID`，内置目录能按 10 个区域渲染本地报出的 `messaging.envelope.invalid`（ADR-0315）
- **新增** `Envelope.dataSchema()`、`Builder.dataSchema(String)` 与 `Envelope.hasUnencodableData()`、`OperationRequest.hasUnencodableText()`（ADR-0305、ADR-0312、ADR-0315）
- **新增** `ResolvedBinding.properties()`：`Configure` 下发的绑定属性交给配置回调（ADR-0285）
- **新增** `OperationId.MAX_LENGTH`、`ManifestJson.tryRead` / `tryReadFile`、`Manifest.Builder.validate`、`Operations.findDecodedRequest`、`Context.cancellationError()`、`Host.PERMISSION_INVOKE` / `PERMISSION_PUBLISH` 与各枚举的 `wireName()`
- **修复** 宿主写来的信封 `time` 读不出时投递以 `INVALID_ARGUMENT` 拒绝；错误细节里的孤立代理项当作没有细节（ADR-0301、ADR-0306）
- **修复** 管道终点报的 `kernel.operation.unknown` 补上参数 `id`，文案能填出操作 ID
- **调整** 清单 `activation` 里 `onEvent:` 的模式可以带下划线（ADR-0276）
- **调整** 弃用 `Context.cancellation()`、`Host.INVOKE_PERMISSION` / `PUBLISH_PERMISSION`、`LifecycleState.stateName()`、`PipelineStage.manifestName()` 与 `OperationErrors.UNEXPECTED`，旧名仍可用：改用上面的新名与 `InvocationErrors.UNHANDLED_EXCEPTION`

## v0.2.0-rc.1（2026-11-21）

- **新增** 首个对外预发布版，发布到 Maven Central：原语、清单、调用上下文、管道、操作契约与进程外插件运行时，钉规范 `v1.0.0-rc.1`，认领一致性档 `core` 与 `plugin`
