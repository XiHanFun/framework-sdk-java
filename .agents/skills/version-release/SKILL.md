---
name: xihan-sdk-java-version-release
description: 为 framework-sdk-java 准备版本、修改 pom.xml 的版本、跑发版核对脚本、打标签或经 release.yml 发布到 Maven Central 时使用；只有用户明确要求实际发布时才打标签、推送标签，日常的门禁与测试见 testing-release。
---

# framework-sdk-java 版本发布

## 版本真源

- `pom.xml` 的 `<version>` 是唯一的版本真源；`FrameworkInfo.SDK_VERSION`（插件握手时上报的 SDK 版本）必须与它相同，`PrimitivesTest` 核对。
- 开发期是 `-SNAPSHOT` 版本；正式发布时去掉 `-SNAPSHOT`。标签写成 `v<版本>`，例如首发计划的 `v0.1.0`。
- 坐标 `com.xihanfun:xihan-framework`，注册表 Maven Central；命名空间 `com.xihanfun` 已在 central.sonatype.com 验证，还没有发布过。

## 与规范标签的关系（RFC-0024「实现钉规范」）

- 日常分支可以指向规范已推送的任意提交；发预发布或正式版时，`spec` 子模块必须正好在某个规范标签上。
- 预发布可以钉规范的 `vX.Y.Z-rc.N`，正式版只能钉规范的正式版标签（不带预发布后缀）。`scripts/release-check.sh` 按这条核对；手查时用 `git -C spec tag --points-at HEAD` 看子模块是否正好在标签上。规范还没有对应标签时，先请用户决定是否在 framework-spec 打标签（规范的标签打在它的 `main` 上，同一提交另打 `conformance/vX.Y.Z`），再把 `spec` 指过去、跑全部门禁。
- 成熟度：在有钉在规范标签上的发布版之前是 Experimental；文档页「一致性」一节写明认领的档与缺口。

## 准备版本

每一步都在用户要求之后才做，不替用户选版本号：

1. 工作区干净，在 `dev` 上；核对上一个标签（首发时是全部历史）到当前提交的公开 API 与行为变化，文档页的「破坏性改动与迁移」已写全（changelog-collect 技能）。
2. 改 `pom.xml` 的 `<version>` 与 `FrameworkInfo.SDK_VERSION`，单独提交（与 XiHan.Framework 一样写 `build: v<版本>`）。
3. 跑 `mvn -B -ntp verify`（本机见 testing-release 技能的 Docker 用法），核对 `target/xihan-framework-<版本>.jar` 与 CI 全绿；要看发布产物时跑 `mvn -B -ntp -Prelease -DskipTests -Dgpg.skip package`，`target/` 里另有 `-sources.jar` 与 `-javadoc.jar`。
4. 用户明确说「推送并快进 main」时由 `dev` 快进 `main`（`git fetch . dev:main`，不是快进就停下查），推送并等 CI 全绿。

## 发布

发布由 `.github/workflows/release.yml` 完成，推送 `v*` 标签触发：

1. guard 跑 `scripts/release-check.sh <标签> <提交>`，缺一不发：标签是 `v<语义化版本>`；提交在 `main` 的历史里（预发布与正式版都从 `main` 发）；标签号等于 `pom.xml` 的项目版本与 `FrameworkInfo.SDK_VERSION`，不带 SNAPSHOT；`spec` 正好在规范标签上（见上一节）。
2. publish 以 `-Prelease` 构建（源码包、Javadoc 包、GPG 签名），经 Central Portal 上传并等到发布完成；Maven Central 上已有这个版本时跳过上传，重跑幂等。不跑测试，测试门禁在 `ci.yml`。

工作流不建 GitHub Release：与 XiHan.Framework 相同，发布后按需手动建。

步骤：

- 打标签之前在本地 `main` 的那个提交上跑 `bash scripts/release-check.sh v<版本>`，全部通过再打。Central 上的版本发出后不能撤回或覆盖，发错了只能用下一个号。
- 打标签与推送标签都是外部状态变更：只有用户明确要求时 `git tag -a v<版本> -m "v<版本>" main` 并 `git push origin v<版本>`，然后等 release.yml 跑完。
- 首次发布前，用户要在仓库 Settings → Secrets and variables → Actions 里配四个 Secrets：`MAVEN_CENTRAL_USERNAME` 与 `MAVEN_CENTRAL_PASSWORD`（central.sonatype.com → View Account → Generate User Token，命名空间 `com.xihanfun` 已验证）、`MAVEN_GPG_PRIVATE_KEY`（签名私钥的 ASCII 导出，公钥要先发到公钥服务器）、`MAVEN_GPG_PASSPHRASE`。凭据只经 Secrets，不写进仓库、命令行历史或日志。
- 发布后核对 Maven Central 上的构件（`https://central.sonatype.com/artifact/com.xihanfun/xihan-framework/<版本>`）、文档页与 README 的安装说明。用户要 GitHub Release 时手动 `gh release create v<版本> --verify-tag [--prerelease]`（预发布加 `--prerelease`），正文写钉的规范标签与构件地址。之后 `dev` 上改回哪个 `-SNAPSHOT` 版本由用户决定。
