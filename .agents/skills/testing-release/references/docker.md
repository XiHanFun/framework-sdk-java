# 本机用 Docker 构建与测试

本机没有 Maven 时，在容器里跑与 CI 相同的 `mvn` 命令，依赖缓存在命名卷里。先在仓库根初始化规范：`git submodule update --init`。

## 镜像与卷

| 名字 | 内容 |
| --- | --- |
| 镜像 `xihan-sdk-java-go` | 本机构建的镜像：以官方镜像 `maven:3-eclipse-temurin-21` 为底，另装了 Go（从 `golang.google.cn` 下载），`GOPROXY=https://goproxy.cn,direct`、`GOSUMDB=sum.golang.google.cn`；能跑到 `EchoConformanceTest` |
| 官方镜像 `maven:3-eclipse-temurin-21` | 只有 Maven 与 JDK 21，没有 Go；用它时 `EchoConformanceTest` 失败，要跳过就加 `-e XIHAN_CONFORMANCE_REQUIRED=0` |
| 卷 `xihan-sdk-java-m2` | 挂到 `/root/.m2`，缓存 Maven 依赖 |
| 卷 `xihan-sdk-java-go` | 挂到 `/root/go`，缓存 Go 模块（运行器的依赖） |

用之前先 `docker image ls` 确认镜像还在。镜像没有 Dockerfile 进仓库；要重建，就在 `maven:3-eclipse-temurin-21` 上装不低于 `spec/conformance/go.mod` 要求的 Go，把它的 `bin` 加进 `PATH`，并设上面两个 Go 环境变量。

## 命令

PowerShell（在仓库根）：

```powershell
docker run --rm -v "${PWD}:/src" -w /src -v xihan-sdk-java-m2:/root/.m2 -v xihan-sdk-java-go:/root/go xihan-sdk-java-go mvn -B -ntp verify
```

Git Bash（关掉路径转换，否则 `-w /src` 会被改写成 `C:/Program Files/Git/src`）：

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W):/src" -w /src -v xihan-sdk-java-m2:/root/.m2 -v xihan-sdk-java-go:/root/go xihan-sdk-java-go mvn -B -ntp verify
```

- 重新生成协议代码：把末尾的 `verify` 换成 `-Pprotocol process-sources`。
- 只跑一个测试类：末尾换成 `test -Dtest=<测试类>`。
- 只用官方镜像时：镜像名换成 `maven:3-eclipse-temurin-21`，去掉 `/root/go` 的卷，加 `-e XIHAN_CONFORMANCE_REQUIRED=0`。

## 注意

- 容器以 root 写出 `target/` 与重新生成的 `plugin/v1`：`target/` 已在 `.gitignore`，不提交；生成的协议代码要提交时先 `git diff` 核对内容。
- 容器是 Linux：插件运行时走 Unix 域套接字。Windows 上的回环 TCP 与 `.cmd` 启动脚本只有本机装了 JDK 21+ 与 Go（直接 `mvn -B verify`）或 CI 的 `windows-latest` 能覆盖。
- 有 Maven 3.9+ 与 JDK 21+ 时直接在本机跑 `mvn -B -ntp verify`；`EchoConformanceTest` 另需 PATH 上有 Go。
