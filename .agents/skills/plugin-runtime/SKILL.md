---
name: xihan-sdk-java-plugin-runtime
description: 实现、修复或审查 framework-sdk-java 的进程外插件运行时 com.xihanfun.framework.plugin 时使用，适用于握手、生命周期、调用与投递、宿主回调、stderr 日志、协议代码生成和黑盒运行器验证；原语、管道与操作本身用 sdk-development，发布用 version-release。
---

# framework-sdk-java 插件运行时

类的分工、平台差异与启动脚本读 `references/runtime.md`。

## 规范位置

| 内容 | 位置 |
| --- | --- |
| 协议总述与错误码 | `spec/rfcs/0017-plugin-protocol.md` |
| 协议定义 | `spec/plugin/plugin.proto`（包 `xihan.plugin.v1`） |
| 启动、环境变量、握手行、会话令牌、回调端点列表 | `spec/plugin/handshake.md` |
| 状态机、调用次序、配置、健康、停止与收尾 | `spec/plugin/lifecycle.md`（§7） |
| 调用、上下文还原、错误的写出与读入、投递、回调、日志、运行时消息键 | `spec/plugin/invocation.md` |
| 向量 | `spec/conformance/vectors/plugin/`：`handshake-lines`、`host-addresses`、`deadlines`、`protocol-versions`、`plugin-messages`、`callback-status`、`error-reading` |
| 黑盒运行器的用例与选项 | `spec/conformance/README.md` |

## 本 SDK 的实现

- 源码：`src/main/java/com/xihanfun/framework/plugin/`；入口 `PluginRuntime.serve(PluginOptions)`。
- 生成的协议代码：`src/main/java/com/xihanfun/framework/plugin/v1/`（不导出、不手改）。
- 运行时消息：`PluginMessages` 与 `src/main/resources/com/xihanfun/framework/plugin/Localization/<区域>.json`（10 种区域，与参考实现相同的文案）。
- 示例插件：`examples/echo`（`EchoPlugin` 与 `xihan.manifest.json`），随测试编译、不进构件。
- 测试：`src/test/java/com/xihanfun/framework/plugin/`：`PluginRuntimeTest`、`PluginCallsTest`、`PluginShutdownTest`、`PluginTypesTest`、`HostCallbackTest`、`EchoConformanceTest`，测试辅助 `RunningPlugin`；向量与仓库根经各测试包共用的 `com.xihanfun.framework.Vectors` 读取。

## 黑盒运行器

```bash
cd spec/conformance
go run ./cmd/xihan-conformance plugin --dir <插件目录> --sample '<操作>=<JSON>' --exclude <会崩溃的操作>
```

- 运行器与锁定的规范是同一个提交；需要 Go（版本见 `spec/conformance/go.mod`）。
- `EchoConformanceTest` 把 echo 放进临时目录、按平台生成启动脚本，以 `--json --quiet --config '{"greeting":"hello"}' --sample 'echoes.create={"text":"conformance"}' --exclude echoes.crash` 跑全部用例，断言每个用例都通过、没有跳过，且报出的用例名与测试里的 `CASES` 完全相同：规范的运行器增删用例时同步改 `CASES`。
- 在 Windows 与 Linux 都要跑：Linux 上经 Unix 域套接字，Windows 上经回环 TCP 与 `.cmd` 启动脚本；本机 Docker 只覆盖 Linux，Windows 看 CI 的 `windows-latest`。

## 协议代码

`spec/plugin/plugin.proto` 变了（升规范指针）就重新生成：`mvn -B -ntp -Pprotocol process-sources`。生成物整个替换 `plugin/v1`，与源码改动一起提交；CI 在 ubuntu 上重新生成后用 `git diff --exit-code` 核对。

## 完成条件

1. 行为与 `spec/plugin/*.md` 一致，相关向量都回放；`EchoConformanceTest` 在 Linux 与 Windows 都通过。
2. `mvn -B -ntp verify` 通过；协议代码与 proto 一致。
3. 公开 API 不出现 gRPC 类型；grpc-java 与 protobuf-java 仍是可选依赖。
4. 文档页 `../XiHan.Framework/docs/sdks/java.md` 的「写一个插件」与「与宿主共用的行为」里插件的各条已同步。
