---
name: xihan-sdk-java-changelog-collect
description: 收集 framework-sdk-java 两个引用之间使用者可感知的变化、整理进 XiHan.Framework 文档站 Java SDK 页的「破坏性改动与迁移」时使用；它不改版本、不打标签、不发布，也不提交 XiHan.Framework 仓库，除非用户要求。
---

# framework-sdk-java 变化整理

## 事实源

- 用户指定的起止标签或提交；没有指定时先列出最近的标签（还没有标签时是全部历史）与当前 `HEAD`，请用户确认范围。
- 范围内的提交（`git log --format='%h %s' <起>..<止>`）、实际 diff、公开 API（导出包里的 `public` 类型与成员、`module-info.java`）与 `pom.xml` 的依赖变化。
- 规范指针的变化（`git diff <起>..<止> -- spec`）与对应的 ADR 编号。
- 文档页 `../XiHan.Framework/docs/sdks/java.md` 现有的「破坏性改动与迁移」与各节里「（上一版…）」的注记。

## 收录规则

- 只收使用者可感知的变化：公开 API 的删除、改名与签名变化，异常类型与抛出条件，错误码、消息键与参数，线上编码与载荷解码，清单读写，插件运行时对宿主的可观察行为，依赖与 JDK 要求。
- 纯测试、CI、内部重构不收，除非改变构件内容或使用方式。
- 每条写成「改了什么、怎样迁移」，带 Java 类型或成员名与规范出处（RFC、`invocation.md` §N、ADR 编号），不罗列内部文件。
- 同一次规范对齐（同一个 ADR）引起的多处变化合并成一条，只有使用者要做的事不同才拆开。
- 文档页正文不写版本号与提交哈希；需要区分时写「上一版」。

## 写入

- 写进文档页的「破坏性改动与迁移」；相关各节里的行为描述同步改成新行为，旧行为以「（上一版…）」注明。
- 文档页在 XiHan.Framework 仓库：在那边单独提交，构建用 `pnpm --dir docs build`（在 XiHan.Framework 根）。
- 本技能不改 `pom.xml` 的版本、不打标签、不发布。
