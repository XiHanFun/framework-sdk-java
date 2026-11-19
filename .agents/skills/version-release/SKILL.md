---
name: xihan-sdk-java-version-release
description: 为 framework-sdk-java 准备版本、修改 pom.xml 的版本、核对规范标签与打包结果、打标签或发布到 Maven Central 时使用；只有用户明确要求实际发布时，才由用户用自己的凭据推送标签或上传构件，日常的门禁与测试见 testing-release。
---

# framework-sdk-java 版本发布

## 版本真源

- `pom.xml` 的 `<version>` 是唯一的版本真源；`FrameworkInfo.SDK_VERSION`（插件握手时上报的 SDK 版本）必须与它相同，`PrimitivesTest` 核对。
- 开发期是 `-SNAPSHOT` 版本；正式发布时去掉 `-SNAPSHOT`。标签写成 `v<版本>`，例如首发计划的 `v0.1.0`。
- 坐标 `com.xihanfun:xihan-framework`，注册表 Maven Central；命名空间 `com.xihanfun` 已在 central.sonatype.com 验证，还没有发布过。

## 与规范标签的关系（RFC-0024「实现钉规范」）

- 日常分支可以指向规范已推送的任意提交；发预发布或正式版时，`spec` 子模块必须正好在某个规范标签上。
- 预发布可以钉规范的 `vX.Y.Z-rc.N`，正式版只能钉规范的正式版标签（不带预发布后缀）。规范的首个标签计划是 `v1.0.0-rc.1`，先用 `git -C spec tag --points-at HEAD` 看子模块是否正好在标签上。规范还没有对应标签时，先请用户决定是否在 framework-spec 打标签（规范的标签打在它的 `main` 上，同一提交另打 `conformance/vX.Y.Z`），再把 `spec` 指过去、跑全部门禁。
- 成熟度：在有钉在规范标签上的发布版之前是 Experimental；文档页「一致性」一节写明认领的档与缺口。

## 准备版本

每一步都在用户要求之后才做，不替用户选版本号：

1. 工作区干净，在 `dev` 上；核对上一个标签（首发时是全部历史）到当前提交的公开 API 与行为变化，文档页的「破坏性改动与迁移」已写全（changelog-collect 技能）。
2. 改 `pom.xml` 的 `<version>` 与 `FrameworkInfo.SDK_VERSION`，单独提交（与 XiHan.Framework 一样写 `build: v<版本>`）。
3. 跑 `mvn -B -ntp verify`（本机见 testing-release 技能的 Docker 用法），核对 `target/xihan-framework-<版本>.jar` 与 CI 全绿。

## 发布

- 仓库里没有发布工作流，`pom.xml` 也没有签名、源码包、Javadoc 包与上传到 Maven Central 的配置；第一次发布前要先补这些配置，按普通改动评审、提交。
- 上传要用户自己的 Central 凭据与签名密钥，由用户执行或在用户明确要求时执行；不把凭据写进仓库、命令行历史或日志。
- 打标签与推送标签都是外部状态变更：只有用户明确要求时 `git tag -a v<版本> -m "v<版本>"` 并推送。Maven Central 上的版本发出后不能撤回，发错了只能用下一个号。
- 发布后核对 Maven Central 上的构件、文档页与 README 的安装说明。
