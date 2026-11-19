---
name: xihan-sdk-java-commit-msg
description: 根据 framework-sdk-java 当前暂存区生成一行提交信息时使用，适用于「提交信息」「commit msg」「msg」或提交前概括已暂存的改动；只生成消息，不负责暂存、解决冲突或执行提交。
---

# framework-sdk-java 提交信息

1. 检查 `git status --short --branch`；存在冲突、merge 或 rebase 时先报告，不生成完成态消息。
2. 只读取 `git diff --cached --stat`、完整的暂存 diff 和最近 20 条提交。暂存区为空时不从未暂存内容猜测。
3. 将全部暂存变化归纳成一个意图，输出一行 Conventional Commit。

格式：`<type>(<scope>): <中文说明>`。type 使用 `feat`、`fix`、`refactor`、`perf`、`docs`、`style`、`test`、`build`、`ci`、`chore`、`revert`。

scope 用本仓库的包名：`primitives`、`manifest`、`invocation`、`pipeline`、`operation`、`plugin`；向量与一致性测试用 `conformance`；只升规范指针用 `spec`；README、AGENTS.md 与技能用 `docs`；`pom.xml` 与构建配置用 `build`；工作流用 `ci`。跨多个包的同一项对齐可省略 scope。

- 说明写使用者可感知的结果；对齐规范时写明规范出处与 ADR 编号（如「（ADR-0245）」），升了规范指针时写「规范指针升到 <短哈希>」，与仓库历史的写法一致。
- 删除、改名或改变公开 API、异常类型、错误码与消息键时使用 `!`。
- 不加署名行。只要求消息时最终只输出一行标题，不附解释或候选列表。
