---
name: xihan-sdk-java-development
description: 设计、实现、重构或对齐 framework-sdk-java 的原语、清单、调用上下文、管道与操作契约时使用，适用于对照规范与 .NET 参考实现修改行为、补向量回放和调整公开 API；进程外插件运行时用 plugin-runtime，门禁、打包与发布分别用 testing-release 与 version-release。
---

# framework-sdk-java 开发

包与规范、向量、测试的对应读 `references/packages.md`；与 .NET 对齐的做法和规范没写到时怎么办读 `references/alignment.md`。

## 先查事实

```bash
git status --short --branch
git log -5 --oneline
git submodule status
```

`spec/` 没初始化就先 `git submodule update --init`。

## 先读的规范

按顺序，只读与任务相关的部分：

1. `spec/conventions/vocabulary.md` 与相关约定：`error-codes.md`、`baggage-keys.md`、`capability-ids.md`、`operation-ids.md`。
2. 目标包对应的 RFC（`references/packages.md`），连同其中「与 .NET 的映射」一节。
3. `spec/conformance/profiles.json` 里 `core` 档列出的向量，以及 `spec/conformance/README.md` 对每个向量文件的说明。
4. XiHan.Framework（本地 `../XiHan.Framework`）的相关 ADR（`plans/adr`）与 .NET 实现，确认参考实现的实际行为。

## 不可变约束

- 规范写到的行为与 .NET 逐字一致：错误码、消息键、参数名、英文模板、JSON 路径、排序结果与报错的先后。
- 可恢复的失败返回 `Result` / `Error`；配置错误抛带错误码的 `FrameworkException`；文案只引用消息键（RFC-0010）。
- 读外部 JSON 先过 `JsonText.check`，再按文本顺序核对（载荷 `RequestShape`、清单 `ManifestShape`），不随 Jackson 的内部顺序变。
- 原语核心类型不依赖 Jackson；包依赖方向 `operation → pipeline → invocation → primitives`、`manifest → primitives`；grpc-java 只在 `plugin`。
- 同步签名、按虚拟线程设计；锁用 `ReentrantLock`；集合有上限；取消只经 `Context` 的令牌。

## 规范没写到时

停下，不自行选一种写法：列出本 SDK、.NET 与其他 SDK 的实际行为和可复现的输入，提出规范文字与向量用例，在 framework-spec 提交并推送；规范与 XiHan.Framework 的 ADR 落定之后，再在本仓库升规范指针并改实现。

## 完成条件

1. 行为与规范及 .NET 一致；相关向量都回放，并在 `VectorCoverageTest` 的 `REPLAYED` 登记（做不到的进 `GAPS` 并写理由）。
2. `mvn -B -ntp verify` 通过：`-Xlint:all -Werror`、doclint 与全部测试。
3. 公开类型与成员有中文 Javadoc 并写明规范出处；新导出的包写进 `module-info.java`。
4. 文档页 `../XiHan.Framework/docs/sdks/java.md` 的「模块」「与宿主共用的行为」「语言映射」「破坏性改动与迁移」已同步；README 只在概览变化时改，两份同步。
5. 一项能力一个提交：`<type>(<scope>): <中文说明>`，写明 ADR 编号与规范指针。
