---
name: xihan-sdk-java-create-pr
description: 为 framework-sdk-java 准备、审查或创建 Pull Request 时使用，适用于生成 PR 标题与正文、比较分支或执行 gh pr create；普通的代码审查不使用，推送与创建只在用户明确要求时执行。
---

# framework-sdk-java 创建 PR

## 前置检查

- 检查分支、上游、工作区、merge/rebase 和冲突；不在正常分支状态时不创建 PR。
- base 默认是 `dev`；只有用户明确要求时才面向 `main`（`main` 平时由 `dev` 快进，不走 PR）。
- 用 `git log <base>..HEAD`、`git diff --stat <base>...HEAD` 和完整 diff 审查整条分支。
- 核对公开 API 与 Javadoc、`module-info.java`、`pom.xml` 的依赖（grpc-java 与 protobuf-java 仍是 optional）、规范指针、生成的协议代码与 proto 一致、测试与文档页。

## PR 内容

仓库没有 PR 模板，正文按以下几段写：

1. **关联 Issue**：`Closes #<编号>`，没有就写「无」。
2. **变更说明**：使用者可感知的结果，对齐规范时写规范出处与 ADR 编号、规范指针升到哪个提交。
3. **影响范围**：涉及的 Java 包（`primitives`、`manifest`、`invocation`、`pipeline`、`operation`、`plugin`）与是否影响插件运行时的线上行为。
4. **自测**：只列实际执行过的命令与结果，如 `mvn -B -ntp verify`（本机 Docker 或本机 Maven）、`EchoConformanceTest` 在哪个平台通过；Windows 没有本机跑过就写以 CI 为准。
5. **破坏性变更**：公开 API、异常、错误码、消息键、线上编码的变化与迁移方式，并说明文档页的「破坏性改动与迁移」是否已更新；没有就写「无」。

标题用 Conventional Commit 形态，scope 规则同 commit-msg 技能。

## 远程操作

只要草稿时不写远程。明确要求创建 PR 时才推送当前分支并执行 `gh pr create --base dev`；禁止 force push、合并或改写其他远端分支。创建后返回 PR 链接。
