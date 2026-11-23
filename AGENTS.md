# AGENTS.md

本文件约束在 framework-sdk-java 仓库内工作的 AI Agent。回答、文档与提交说明使用中文；代码标识、公开 API 和标准技术术语保持英文。

## 仓库概览

framework-sdk-java 是 [XiHan Spec](https://github.com/XiHanFun/framework-spec) 的 Java 实现：原语层、清单、调用上下文、管道、操作契约与进程外插件运行时。整个 SDK 是一个 Maven 构件 `com.xihanfun:xihan-framework`，JPMS 模块名 `com.xihanfun.framework`，按 Java 包划分；插件运行时 `com.xihanfun.framework.plugin` 用 grpc-java，传输依赖是可选依赖。规范以 submodule 放在 `spec/`，.NET 的 [XiHan.Framework](https://github.com/XiHanFun/XiHan.Framework) 是参考实现，规范写到的行为与它逐字一致，由规范的一致性向量与黑盒运行器验证。

### 技术栈

| 技术 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 21+（`pom.xml` 的 `maven.compiler.release` 为 21，CI 用 Temurin 21） | 编译与运行；并发按虚拟线程设计 |
| Maven | 3.9+ | 构建、测试与协议代码生成 |
| Jackson | `jackson-databind`，版本见 `pom.xml` 的 `jackson.version` | 唯一的普通依赖（`requires transitive`）：XGI 载荷、清单读取、线上编码 |
| grpc-java、protobuf-java | `grpc.version`、`protobuf.version`；`<optional>true</optional>`、`requires static` | 插件运行时的传输与生成的协议代码 |
| JUnit | JUnit 6（JUnit Platform），`junit.version` | 测试，在类路径上运行 |
| protobuf-maven-plugin（`io.github.ascopes`） | 只在 `-Pprotocol` 配置里 | 由 `spec/plugin/plugin.proto` 生成协议代码 |
| maven-source、maven-javadoc、maven-gpg、central-publishing 插件 | 只在 `-Prelease` 配置里 | 发布到 Maven Central：源码包、Javadoc 包、GPG 签名与 Central Portal 上传 |
| Go | 版本见 `spec/conformance/go.mod` | 运行规范的黑盒运行器（`EchoConformanceTest`） |
| Docker | 本机没有 Maven 时 | 镜像 `xihan-sdk-java-go`（Maven、JDK 21 与 Go），见 testing-release 技能 |

## 开始前必须做

1. 按任务读取对应技能：
   - 原语、清单、调用上下文、管道、操作契约的设计、实现、重构与对齐：`.agents/skills/sdk-development/SKILL.md`。
   - 进程外插件运行时（握手、生命周期、调用、投递、回调、协议代码）：`.agents/skills/plugin-runtime/SKILL.md`。
   - 门禁、向量覆盖、打包验证与本机 Docker 构建：`.agents/skills/testing-release/SKILL.md`。
   - 版本与发布：`.agents/skills/version-release/SKILL.md`。
   - 整理用户可感知的变化：`.agents/skills/changelog-collect/SKILL.md`。
   - 提交信息：`.agents/skills/commit-msg/SKILL.md`；PR：`.agents/skills/create-pr/SKILL.md`；Issue：`.agents/skills/issue-reply/SKILL.md`；测试审查：`.agents/skills/test-review/SKILL.md`。
2. 同时涉及多个职责时加载对应的多个技能，不要一次读取无关资料。
3. 检查当前分支、`git status`、最近提交与 `git submodule status`（`spec/` 是否已初始化、指向哪个提交）；保留用户已有改动。
4. 读相关规范章节：`spec/rfcs/0001-primitives.md`、`0002-manifest.md`、`0003-invocation-context.md`、`0004-pipeline-stages.md`、`0010-localization.md`、`0011-operations.md`、`0017-plugin-protocol.md`、`0024-versions-and-profiles.md`，`spec/conventions/`（错误码、Baggage 键、能力 ID、操作 ID、术语表），`spec/manifest/manifest.schema.json`，`spec/plugin/handshake.md`、`lifecycle.md`、`invocation.md` 与 `plugin.proto`，`spec/conformance/profiles.json` 与 `spec/conformance/vectors/` 下对应的向量。只读与任务相关的部分。
5. 读目标 Java 包的 `package-info.java`、`src/main/java/module-info.java`、相邻实现与 `src/test/java` 下同包的测试。
6. 先找已有实现再动手：共同的 JSON 文本检查 `JsonText`、值模型 `JsonValues`、空白字符 `internal.Whitespace`、各枚举按线上名字的查找 `internal.WireNames`、不止一个包要核对的文法 `internal.Grammars`、消息键常量类（`KernelMessages` 是内核消息键唯一写出文本的地方，`InvocationMessages`、`OperationMessages` 引用它；另有 `PluginMessages`）、错误码常量类（`InvocationErrors`、`OperationErrors`、`PipelineErrors`、`PluginErrors`；前、二、四另有创建错误的工厂方法，`PipelineErrors` 只有错误码），不重复建设。

## 目录结构

```text
/
├─ AGENTS.md
├─ .agents/skills/                        # 按开发、插件运行时、测试和发布分类的仓库技能
├─ .claude/skills -> ../.agents/skills    # Claude Code 技能兼容入口
├─ CLAUDE.md -> AGENTS.md                 # Claude Code 仓库指令入口
├─ .github/workflows/ci.yml               # 门禁：ubuntu 与 windows 上 mvn verify，ubuntu 另核对生成的协议代码
├─ .github/workflows/release.yml          # 发布：推送 v* 标签后核对、签名并上传 Maven Central
├─ scripts/release-check.sh               # 发版核对：标签、main、版本与规范标签；打标签前在本地跑，release.yml 再跑一遍
├─ pom.xml                                # 单构件 com.xihanfun:xihan-framework：版本、依赖、编译门禁、protocol 与 release 配置
├─ spec/                                  # 规范（XiHanFun/framework-spec 的 submodule）：RFC、约定、清单 Schema、插件协议、档表、向量与黑盒运行器
├─ src/main/java/
│  ├─ module-info.java                    # JPMS 模块 com.xihanfun.framework 的导出与依赖
│  └─ com/xihanfun/framework/
│     ├─ primitives/                      # Error、Result、Maybe、TypedId、生命周期、消息键与模板（RFC-0001、RFC-0010）
│     │  └─ json/                         # 线上 JSON 编码：Jackson 模块 PrimitivesModule
│     ├─ manifest/                        # xihan.manifest.json 的模型、读取、写出与校验（RFC-0002）
│     ├─ invocation/                      # Context：取消与截止时间、W3C 追踪、Baggage、对象袋（RFC-0003）
│     ├─ pipeline/                        # 十一阶段的 PipelineBuilder 与排序（RFC-0004）
│     ├─ operation/                       # 操作契约、登记表与调用器（RFC-0011）
│     ├─ plugin/                          # 进程外插件运行时（RFC-0017）
│     │  └─ v1/                           # 由 spec/plugin/plugin.proto 生成的协议代码（不导出、不手改）
│     └─ internal/                        # 不导出的模块内共用实现
├─ src/main/resources/com/xihanfun/framework/plugin/Localization/   # 插件运行时消息的 10 种区域语言文件
├─ src/test/java/                         # JUnit 测试：单元、向量回放、VectorCoverageTest、EchoConformanceTest
└─ examples/echo/                         # 示例插件：随测试编译，由黑盒运行器验证，不进构件
```

## 常用命令

在仓库根执行；本机没有 Maven 时在 Docker 里执行同样的 `mvn` 命令（见 testing-release 技能）。

| 任务 | 命令 |
| --- | --- |
| 初始化规范 | `git submodule update --init` |
| 全部门禁：编译（`-Xlint:all -Werror`、doclint）与全部测试（含向量回放与黑盒运行器） | `mvn -B -ntp verify` |
| 生成协议代码 | `mvn -B -ntp -Pprotocol process-sources` |
| 核对生成的协议代码（CI 的 ubuntu 步骤，生成之后） | `git add --intent-to-add src/main/java/com/xihanfun/framework/plugin/v1 && git diff --exit-code -- src/main/java/com/xihanfun/framework/plugin/v1` |
| 只跑一个测试类 | `mvn -B -ntp test -Dtest=<测试类>` |
| 黑盒运行器（插件目录） | `cd spec/conformance && go run ./cmd/xihan-conformance plugin --dir <插件目录> --sample '<操作>=<JSON>'` |
| Docker（PowerShell） | `docker run --rm -v "${PWD}:/src" -w /src -v xihan-sdk-java-m2:/root/.m2 -v xihan-sdk-java-go:/root/go xihan-sdk-java-go mvn -B verify` |
| 发版核对（打标签之前） | `bash scripts/release-check.sh v<版本>` |
| 发布产物（不签名，只看产物） | `mvn -B -ntp -Prelease -DskipTests -Dgpg.skip package` |

## 规范与一致性

- 规范是事实源：`spec/` 子模块钉的提交上的 RFC、约定、Schema、插件协议与向量。改行为先看规范怎么写，引用到章节（如 `invocation.md` §5、RFC-0001「空白字符」）。
- .NET（XiHan.Framework）是参考实现：规范写到的行为与它一致，错误码、消息键、参数、英文模板、JSON 路径与排序结果逐字相同；向量的期望值由 .NET 算出。
- 向量回放：测试按仓库根的相对路径直接读 `spec/conformance/vectors/`（系统属性 `xihan.repository.root` 由 surefire 设置，不复制向量文件）。`VectorCoverageTest` 读档表 `spec/conformance/profiles.json`，本 SDK 认领 `core` 与 `plugin`（RFC-0024「一致性档」）：认领的档要求的每个向量要么由测试回放（登记在 `REPLAYED`，注释写回放它的测试类），要么列为缺口（`GAPS`，写明理由）；规范新增、删除或改名向量文件时它失败。
- 插件一侧另由黑盒运行器验证：`EchoConformanceTest` 从 submodule 以 `go run` 运行规范仓库的运行器，跑示例插件 `examples/echo` 的全部用例。
- 规范没写到的行为不自行发明：停下来报告差异（本 SDK、.NET 与其他 SDK 各自怎么做），提出规范文字，在 framework-spec 提交并推送，再回来改实现。
- 公开契约的变化顺序：framework-spec 的 RFC 与向量 → XiHan.Framework 的 ADR（`plans/adr`）→ 各 SDK。本仓库的提交说明与文档引用对应的 ADR 编号。
- 升规范指针：`git -C spec fetch` 后 `git -C spec checkout <提交>`，`git add spec`；跑全部门禁，`VectorCoverageTest` 报出的新向量补回放或列为缺口，`plugin.proto` 变了就重新生成协议代码；实现改动与指针放在同一提交，提交说明写「规范指针升到 <短哈希>」。日常分支可以指向规范已推送的任意提交，发版时必须正好在规范标签上（RFC-0024「实现钉规范」）。

## 代码规范

- 注释：Javadoc 与 `//` 注释用中文。公开类型与成员都写 Javadoc：首句概括，细节进 `<p>`，写明规范出处（RFC 编号、`invocation.md` §N、ADR 编号），带 `@param`、`@return`、`@throws`；需要说明的私有字段与方法用单行 `/** … */`。关键逻辑（锁与临界区、判定顺序、平台差异、绕开 Jackson 默认行为的原因）用 `//` 说明为什么，`//` 注释句末不加「。」。
- 编译门禁：`-Xlint:all -Werror` 与 `-Xdoclint:all,-missing`，生成的协议代码也要通过；`@SuppressWarnings` 只在确有理由时用在最小范围上，`module-info.java` 的 `requires-automatic` 旁边写着原因。
- 格式：`.editorconfig`：UTF-8、LF、文件末尾换行、去掉行尾空白，Java 4 空格缩进、每行最多 140 列；仓库没有 Checkstyle、Spotless 等格式化工具。`plugin/v1` 保持 protoc 的原样输出（`.gitattributes` 标为 `-whitespace linguist-generated`）。
- 命名：Java 标准命名，常量 `UPPER_SNAKE_CASE`；消息键常量类 `<领域>Messages`、错误码常量类 `<领域>Errors`（不止一处要创建的错误在它上面给工厂方法）；测试类 `<被测类型>Test`、回放向量的测试 `<名字>VectorsTest`，测试方法名是描述行为的英文驼峰句子。
- 错误：可恢复的失败返回 `Result` / `Error`，不抛异常。配置错误（清单不合法、管道成环、重复登记、登记表已满）抛带错误码的 `FrameworkException`；调用方的编程错误抛 `IllegalArgumentException`，状态不允许抛 `IllegalStateException`，`null` 参数经 `Objects.requireNonNull(value, "name")` 抛 `NullPointerException`。异常消息是英文句子，标识符加单引号。`com.xihanfun.framework.primitives.Error` 与 `java.lang.Error` 同名，用到时显式导入。
- 文案只引用消息键（RFC-0010）：错误带消息键与参数（`Error.validation(code, key, Map.of(...))`），键定义在 `<领域>Messages` 常量类；`KernelMessages` 的英文模板与 `kernel-messages.json` 逐字相同；插件运行时的键与 `plugin-messages.json` 一致，10 种区域的语言文件在 `src/main/resources/com/xihanfun/framework/plugin/Localization/<区域>.json`；`Error.literal` 只用于线上还原与异常转换。
- 可空性：公开成员不接受也不返回 `null`；可能没有的值返回 `Optional`，需要线上编码的字段用 `Maybe`，没有元素时是空集合。
- 取消与截止时间：经 `Context` 的两个令牌传递，归因只看令牌（RFC-0003 规则 1）；不中断线程、不取消 future。`withCancellation` / `withDeadline` / `withTimeout` 返回的 `CancellableContext` 用 try-with-resources 关闭。
- 并发：同步签名、按虚拟线程设计；构建出的对象不可变或线程安全；锁用 `ReentrantLock`，不在 `synchronized` 里阻塞（Java 21 上会钉住虚拟线程）。
- 有界：对象袋、Baggage、按派生传递的值、操作登记表都有上限（容量写成常量并在 Javadoc 里写明），新增来自外部输入的集合同样要有上限；管道的中间件与约束与 .NET 一样不设上限。
- 依赖：普通依赖只有 `jackson-databind`；grpc-java 与 protobuf-java 是可选依赖，只有 `plugin` 包引用，公开 API 不出现 gRPC 类型；原语核心类型不依赖 Jackson（编码在 `primitives.json`）。包依赖方向 `plugin → operation → pipeline → invocation → primitives`、`plugin → manifest → primitives`。新增依赖要有明确收益。
- 文法与文本：规范的正则一律整串匹配（`Matcher.matches()`）；空白按 `internal.Whitespace`（RFC-0001「空白字符」的 25 个码位），不用 `Character.isWhitespace` 或 `String.isBlank`；读外部 JSON 先过 `JsonText.check`，不依赖 Jackson 的宽松读取。

## 测试

- 改哪个包，就在 `src/test/java` 的同一个包里添加或更新测试；示例插件 `examples/echo` 的行为由 `EchoConformanceTest` 覆盖。
- 规范新增或修改向量时补回放测试，并在 `VectorCoverageTest` 的 `REPLAYED` 登记（注释写测试类）；确实做不到的才列进 `GAPS` 并写理由。
- 插件运行时用黑盒运行器在 Windows 与 Linux 都跑全部用例：Linux 上经 Unix 域套接字，Windows 上经回环 TCP 与 `.cmd` 启动脚本。本机 Docker 只覆盖 Linux，Windows 以 CI 的 `windows-latest` 为准。
- 没有 Go 时 `EchoConformanceTest` 失败；只有设了 `XIHAN_CONFORMANCE_REQUIRED=0` 才跳过（打印原因），这只用于本机临时跳过，CI 不设。
- 修复缺陷先写能确定复现问题的测试，再改实现；涉及时钟与取消时直接触发信号（如测试辅助 `DeadlineSignals`），不靠 sleep。
- 断言消息与夹具文本用英文。不删测试、不放宽门禁来通过。

## 文档

- README 只放概览、安装、一个最小示例与链接；`README.md`（英文）与 `README_cn.md`（中文）同构，两份同步修改。
- 详细文档在 XiHan.Framework 仓库的 `docs/sdks/java.md`（站点 `https://framework.docs.xihanfun.com/sdks/java`）。本地克隆在 `../XiHan.Framework`，是另一个仓库，在那边单独提交；文档构建在那个仓库根执行 `pnpm --dir docs build`。
- 文档正文不写会变的数字：包版本、规范提交哈希、测试数量；分别指向 `pom.xml`、`spec/` 子模块与测试本身。
- 改公开 API 或行为时，同步 Javadoc、文档页对应章节与其中的「破坏性改动与迁移」，旧行为以「上一版…」写明。

## 发布

- 版本真源是 `pom.xml` 的 `<version>`；`FrameworkInfo.SDK_VERSION` 必须与它相同（`PrimitivesTest` 核对）。
- 注册表是 Maven Central，坐标 `com.xihanfun:xihan-framework`；命名空间 `com.xihanfun` 已验证，版本由 `release.yml` 发布；目前只有预发布版，README 与文档页写着「目前只有预发布版」，首个正式版发布后改掉。
- 推送 `v*` 标签触发 `.github/workflows/release.yml`：guard 跑 `scripts/release-check.sh`（标签是 v<语义化版本>、提交在 `main` 的历史里、标签号等于 `pom.xml` 与 `FrameworkInfo.SDK_VERSION` 且不带 SNAPSHOT、`spec` 正好在规范标签上：预发布可以钉规范的 rc 标签，正式版只能钉正式版标签，RFC-0024），通过后以 `-Prelease` 构建、签名并上传 Maven Central。打标签之前先在本地跑同一份脚本。工作流不建 GitHub Release：与 XiHan.Framework 相同，发布后按需手动 `gh release create v<版本> --verify-tag [--prerelease]`。
- 上传用仓库 Secrets 里的 Central 令牌与 GPG 私钥（`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`、`MAVEN_GPG_PRIVATE_KEY`、`MAVEN_GPG_PASSPHRASE`），由用户在仓库设置里配置；打标签与推送标签只在用户明确要求时执行。细节见 version-release 技能。

## 分支

- 日常提交在 `dev`。
- `main` 只在用户明确说「推送并快进 main」时由 `dev` 快进（`git fetch . dev:main`，不是快进就停下查），不在 `main` 上直接提交。
- CI（`.github/workflows/ci.yml`）在 `main` 与 `dev` 的 push、目标为 `main` 或 `dev` 的 PR 以及手动触发时运行，矩阵是 `ubuntu-latest` 与 `windows-latest`。发布（`.github/workflows/release.yml`）只在推送 `v*` 标签或在标签上手动触发时运行。

## Git 与提交

- 使用 Conventional Commits：`<type>(<scope>): <中文说明>`。scope 用本仓库的包名 `primitives`、`manifest`、`invocation`、`pipeline`、`operation`、`plugin`，或 `conformance`、`spec`、`docs`、`build`、`ci`。
- 保留用户已有和无关改动；不要 reset、checkout 或清理它们，只暂存自己的文件。
- 提交前检查 `git diff --check` 与相关门禁；容器以 root 写出的 `target/` 不提交。
- 一项能力或一个独立修复一个提交；契约对齐、实现、测试、README 与规范指针属于同一闭环时放在同一提交，说明里写 ADR 编号与规范指针。
- 不推送、不打标签、不发布，除非用户明确要求。

## 禁止事项

- 不在本仓库改公开契约而绕过 framework-spec 的 RFC；规范没写到的行为不自行发明。
- 不手改 `src/main/java/com/xihanfun/framework/plugin/v1` 的生成代码，改 proto 或生成配置后重新生成。
- 不放宽门禁：不去掉 `-Werror` 与 doclint，不在 CI 设 `XIHAN_CONFORMANCE_REQUIRED=0`，不从 `VectorCoverageTest` 删向量或加没有理由的缺口。
- 不让 grpc-java 与 protobuf-java 变成必需依赖，不在 `plugin` 以外的包引用它们，不在公开 API 里暴露 gRPC 类型。
- 不添加无界集合，不在 `synchronized` 里阻塞，不靠中断线程实现取消。
- 不提交 `target/`、`.claude/worktrees/` 与 `.claude/settings.local.json`。
- 不猜测版本、命令或规范内容；以当前检出与 `spec/` 为准。
