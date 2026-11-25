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

- 说明写使用者可感知的结果；对齐规范时末尾注 ADR（如「（ADR-0245）」）。只升规范指针的写 `build(spec): 规范指针升到 <短哈希>（ADR-NNNN）`，与实现同一提交时不另写指针哈希。
- 删除、改名或改变公开 API、异常类型、错误码与消息键时使用 `!`。
- 不加署名行。

## 写短

- 只写一行标题，不写正文。
- 说明一句话讲这次改动的结果，不超过 40 个字：汉字、字母、数字、标点各算一个，末尾的 `（ADR-NNNN）` 不计。
- 说明里不再出现「：」「；」，除末尾的 ADR 注外不用括号或破折号补充；不罗列文件、成员、取值与改前改后，不写向量与测试的数量、门禁结果，这些写进 文档页、ADR 或 PR 描述。
- 40 个字写不下时，多半是暂存区混了几件事（先建议拆成几笔），或细节写多了（删到只剩结果），不要加长标题。

不要：`feat: 规范指针升到 c90faf8，跟上两处修订（ADR-0276）：操作 ID 不超过 128 个字符（conventions/operation-ids.md……`

要：`feat: 跟上操作 ID 限长等两处规范修订（ADR-0276）`

只要求消息时最终只输出一行标题，不附解释或候选列表。
