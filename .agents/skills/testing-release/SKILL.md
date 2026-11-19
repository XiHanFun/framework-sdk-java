---
name: xihan-sdk-java-testing-release
description: 运行或排查 framework-sdk-java 的门禁、测试、向量覆盖、协议代码核对与打包验证时使用，适用于照 ci.yml 本地复现 CI、维护 VectorCoverageTest 和在没有 Maven 的本机用 Docker 构建；不负责实现功能，发版步骤见 version-release。
---

# framework-sdk-java 测试与打包

本机 Docker 的用法与镜像读 `references/docker.md`。事实源是 `.github/workflows/ci.yml` 与 `pom.xml`。

## 门禁（照 ci.yml）

CI 在 `ubuntu-latest` 与 `windows-latest` 上各跑一遍：检出（含 `spec` submodule），装 Temurin JDK 21 与 Go（版本取 `spec/conformance/go.mod`），然后：

```bash
# 只在 ubuntu：重新生成协议代码并核对与提交的一致
mvn -B -ntp -Pprotocol process-sources
git add --intent-to-add src/main/java/com/xihanfun/framework/plugin/v1
git diff --exit-code -- src/main/java/com/xihanfun/framework/plugin/v1

# 两个平台：编译与全部测试
mvn -B -ntp verify
```

- 编译：`maven.compiler.release` 21，`-Xlint:all -Werror` 与 `-Xdoclint:all,-missing`，测试代码与 `examples/echo` 一起编译、同一套门禁。
- 测试：JUnit 6（JUnit Platform），surefire 在类路径上运行（`useModulePath` 为 false，模块边界由编译期的 `module-info.java` 核对）；系统属性 `xihan.repository.root` 指向仓库根，向量按相对路径读取，`xihan.project.version` 供 `PrimitivesTest` 核对 `FrameworkInfo.SDK_VERSION`。
- 先跑受影响的测试类（`mvn -B -ntp test -Dtest=<测试类>`），再跑完整的 `verify`。
- 打包验证：`verify` 同时打出 `target/xihan-framework-<版本>.jar`；仓库里没有另外的打包检查或发布配置。

## 向量覆盖

`VectorCoverageTest` 读 `spec/conformance/profiles.json`，认领 `core` 与 `plugin`：

- 规范在认领的档里新增向量：它失败，直到补上回放测试并把文件登记进 `REPLAYED`（注释写测试类），或登记进 `GAPS` 并写理由。
- 规范删掉或改名了回放的文件：它同样失败，改 `REPLAYED`。
- 认领的档名不在档表里：`claimedProfilesAreDefinedByTheSpec` 失败。

## 黑盒运行器

`EchoConformanceTest` 从 `spec/conformance` 以 `go run ./cmd/xihan-conformance` 运行规范的黑盒运行器。没有 Go 时它失败；只有设了 `XIHAN_CONFORMANCE_REQUIRED=0` 才跳过并打印原因，这只用于本机临时跳过，不在 CI 设，也不能当作门禁通过。

## 不允许

- 不删测试、不放宽 `-Werror` 与 doclint、不把缺口当作通过。
- 不提交 `target/`（容器以 root 写出）。
- 不手改 `plugin/v1` 的生成代码来让核对通过。
