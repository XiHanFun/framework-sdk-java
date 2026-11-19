# 插件运行时的实现

## 类的分工

| 类 | 职责 | 规范 |
| --- | --- | --- |
| `PluginRuntime` | `serve` 的总流程：读清单并核对声明（不合即拒绝启动），读环境变量，选回调端点，监听，写握手行，服务到 `Stop` 完成、stdin 关闭或 SIGTERM | `handshake.md` §1–§4 |
| `PluginOptions` | `serve` 的输入：清单、操作、管道、订阅、目录与生命周期回调；测试可以换掉环境变量与标准输入输出 | — |
| `SessionGuard` | 每个 RPC 先按常数时间核对元数据 `xihan-plugin-session`，恰好一个值且匹配才放行 | `handshake.md` §4 |
| `PluginService` | `xihan.plugin.v1.Plugin` 服务：握手、配置、启动、停止、健康、调用、流式调用、投递 | `handshake.md` §3、`lifecycle.md` §7、`invocation.md` §1–§4 |
| `Lifecycle` | 插件一侧的状态机，按原语层的合法迁移表核对，迁移时唤醒健康推送 | `lifecycle.md` §1、§7 |
| `Calls` | 在途的调用与投递：收尾时等待并触发取消，回调时据此判断租户 | `lifecycle.md` §7.5、§7.7，`invocation.md` §5 |
| `Host` | 宿主回调客户端：`Host.Invoke`、批量的 `Host.Publish`，发出前的本地核对与 gRPC 状态映射 | `invocation.md` §5 |
| `Transport`、`Endpoint`、`Network` | 监听与连接：握手行与端点文法、回调端点列表的解析与选择 | `handshake.md` §2、§4 |
| `Wire` | 协议消息与 SDK 类型的转换：错误按 RFC-0001 编码、上下文按 RFC-0003 平铺 | `invocation.md` §2、§3 |
| `PluginConfiguration`、`ConfigurationNames`、`ConfigurationScalars` | 配置节：先过共同的 JSON 文本检查，成员名按 ASCII 不区分大小写，字符串叶子按 JSON 数字与布尔的文法 | `lifecycle.md` §7.2 |
| `PluginLogger` | stderr 一行一个 JSON 对象；`java.util.logging` 的根日志也改写成这种行 | `invocation.md` §6 |
| `PluginErrors`、`PluginMessages` | 运行时的错误码与七个消息键 | RFC-0017「错误码」、`invocation.md` §8 |

## 平台差异

- 监听：Linux 上 grpc-netty-shaded 自带的 epoll 原生库可用时（x86_64、aarch_64）监听 Unix 域套接字，放在本进程新建的私有目录（随机名、`0700`，套接字 `0600`）；Windows、macOS 与其余情形监听回环 TCP `127.0.0.1` 的随机端口，同机的其他用户连得上，隔离只靠会话令牌。不监听命名管道。
- 回调：只能连回环 TCP 与 Linux 上的 Unix 域套接字（grpc-java 不支持命名管道）。宿主总在端点列表的最后给出回环 TCP，所以 Windows 上的 .NET 宿主（命名管道加回环 TCP）也能回调。
- SIGTERM：关闭钩子收尾后以 `Runtime.halt(0)` 结束进程，否则 JVM 以 143 退出；作者自己在服务期间调用 `System.exit` 也经过这个钩子，同样以 0 结束。stdin 读到结尾或读出错同样是收尾请求，收尾最多 3 秒。
- 线程：服务端以 `Executors.newVirtualThreadPerTaskExecutor()` 执行每一次调用，处理器、钩子与回调都可以阻塞。
- JDK 23 起 Netty 用到的 `sun.misc.Unsafe` 内存访问会在 stderr 打告警，启动脚本加 `--sun-misc-unsafe-memory-access=allow`（JDK 21 没有这个选项）。

## 启动脚本

清单的 `entry` 指向启动脚本：Unix 上是带 shebang、可执行的 sh 脚本，Windows 上是 `.cmd`（宿主在没有扩展名时补 `.exe`，所以 Windows 上写全 `.cmd`）。宿主只继承 `handshake.md` §1 列出的环境变量，`JAVA_HOME` 不在其中，脚本里写 `java` 的绝对路径与完整的类路径。`EchoConformanceTest.writeLauncher` 是现成的写法：`java` 的绝对路径加 Java 参数文件（`@echo.args`，类路径与主类，Windows 路径换成正斜杠）。

## 依赖与模块路径

- grpc-java（`grpc-netty-shaded`、`grpc-protobuf`、`grpc-stub`）与 `protobuf-java` 在本构件里是 `<optional>true</optional>`、`requires static`，不传递给使用者；写插件的应用自己加，版本与 `pom.xml` 的 `grpc.version`、`protobuf.version` 相同（生成代码要求 protobuf-java 不低于生成它的版本）。`pom.xml` 的 `dependencyManagement` 导入 `protobuf-bom`，因为 grpc-protobuf 传递的是 protobuf-java 3.x。
- 模块路径上，自动模块 `io.grpc`、`io.grpc.stub`、`io.grpc.protobuf`、`io.grpc.netty.shaded`、`com.google.protobuf`、`com.google.common` 要进入模块图（应用 `requires` 或 `--add-modules`）。

## 协议代码的生成

`plugin.proto` 没有 Java 选项：`protocol` 配置先把它复制到 `target/protocol/source/plugin/plugin.proto`，在末尾补上 `java_package = "com.xihanfun.framework.plugin.v1"` 与 `java_outer_classname = "PluginProto"`，再用 protobuf-maven-plugin 调 protoc 与 protoc-gen-grpc-java（都从 Maven Central 取，版本即 `protobuf.version` 与 `grpc.version`），最后整个替换 `src/main/java/com/xihanfun/framework/plugin/v1`。生成代码提交进仓库：构建不需要下载 protoc 原生程序，各平台一致，可以审阅；它同样要过 `-Xlint:all -Werror` 与 doclint。
