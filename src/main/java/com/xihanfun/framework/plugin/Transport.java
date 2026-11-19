package com.xihanfun.framework.plugin;

import com.xihanfun.framework.internal.Whitespace;
import io.grpc.BindableService;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollIoHandler;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 插件端点的监听与宿主回调端点的连接（{@code handshake.md} §2、§4）。
 *
 * <p>grpc-java 的 Netty 传输只在 Linux 上经 epoll 原生库（grpc-netty-shaded 自带 x86_64 与 aarch_64）支持 Unix 域套接字，不支持命名管道；
 * 所以 Linux 上有 epoll 时监听 Unix 域套接字，其余平台监听回环 TCP（规范允许三种网络，TCP 只绑定回环地址、隔离只靠会话令牌）。
 * 回调时能连的也只有回环 TCP 与 Linux 上的 Unix 域套接字；宿主总会在端点列表的最后给出一个回环 TCP（{@code handshake.md} §4），
 * 所以在 Windows 上也能回调。
 */
final class Transport {
    /** 只绑定回环地址；写进握手行的是 IPv4 写法。 */
    private static final String LOOPBACK = "127.0.0.1";

    private Transport() {
    }

    /** 一个已经开始监听的服务端。 */
    record Listening(Server server, Endpoint endpoint, Runnable cleanup) {
    }

    /**
     * 判断本进程能不能用 Unix 域套接字：只在 Linux 上、epoll 原生库可用时。
     */
    static boolean unixSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux") && Epoll.isAvailable();
    }

    /**
     * 返回本进程的 gRPC 库能连的网络：回环 TCP 总能连，Unix 域套接字只在 Linux 上有 epoll 时，命名管道不能（{@code handshake.md} §4 的选择依据）。
     */
    static Set<Network> dialable() {
        return unixSupported() ? EnumSet.of(Network.TCP, Network.UNIX) : EnumSet.of(Network.TCP);
    }

    /**
     * 按网络类型监听；没有指定时有 Unix 域套接字就用它，否则用回环 TCP。
     *
     * @throws IOException 监听失败，或指定了本平台不支持的网络
     */
    static Listening listen(Optional<Network> requested, BindableService service, io.grpc.ServerInterceptor guard, Executor executor)
            throws IOException {
        Network network = requested.orElse(unixSupported() && unixPathUsable() ? Network.UNIX : Network.TCP);
        return switch (network) {
            case UNIX -> listenUnix(service, guard, executor);
            case TCP -> listenTcp(service, guard, executor);
            case NPIPE -> throw new IOException("The plugin runtime cannot listen on a named pipe.");
        };
    }

    /**
     * 判断临时目录下的套接字路径能不能写进握手行：握手行的地址不能有空白（{@code handshake.md} §2 的字符集，{@link Whitespace#isWhitespace(char)}），
     * 套接字路径不能超过 sun_path 的 107 个字节；不能时自动选择退回回环 TCP。
     */
    private static boolean unixPathUsable() {
        String sample = Path.of(System.getProperty("java.io.tmpdir"), "xihan-plugin-0123456789012345678", "plugin.sock")
                .toAbsolutePath().toString();
        return !Whitespace.containsWhitespace(sample) && sample.getBytes(StandardCharsets.UTF_8).length <= 107;
    }

    private static Listening listenTcp(BindableService service, io.grpc.ServerInterceptor guard, Executor executor) throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0);
        Server server = NettyServerBuilder.forAddress(address, InsecureServerCredentials.create())
                .executor(executor)
                .addService(ServerInterceptors.intercept(service, guard))
                .build()
                .start();
        int port = ((InetSocketAddress) server.getListenSockets().get(0)).getPort();
        return new Listening(server, new Endpoint(Network.TCP, LOOPBACK + ":" + port), () -> {
        });
    }

    private static Listening listenUnix(BindableService service, io.grpc.ServerInterceptor guard, Executor executor) throws IOException {
        if (!unixSupported()) {
            throw new IOException("Unix domain sockets need Linux with the epoll native library of grpc-netty-shaded.");
        }
        // 套接字放进本进程新建的私有目录（handshake.md §4）：名字随机、创建时即 0700、只新建不复用，
        // 别人抢先建好的同名目录或指向别处的链接不会被拿来用；目录之外的人连不到其中的套接字，所以随后再改 0600 没有空档
        Path directory = Files.createTempDirectory("xihan-plugin-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path socket = directory.resolve("plugin.sock");
        EventLoopGroup boss = epollGroup(1, "xihan-plugin-boss");
        EventLoopGroup worker = epollGroup(0, "xihan-plugin-worker");
        Runnable cleanup = () -> {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            try {
                Files.deleteIfExists(socket);
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                // 临时目录删不掉只留下一个空目录，不影响退出
            }
        };
        try {
            Server server = NettyServerBuilder.forAddress(new DomainSocketAddress(socket.toString()), InsecureServerCredentials.create())
                    .channelType(EpollServerDomainSocketChannel.class)
                    .bossEventLoopGroup(boss)
                    .workerEventLoopGroup(worker)
                    .executor(executor)
                    .addService(ServerInterceptors.intercept(service, guard))
                    .build()
                    .start();
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            // 随机目录名只含 ASCII 字母与数字，路径在 unixPathUsable 里已核对没有空白，写进握手行合文法
            return new Listening(server, new Endpoint(Network.UNIX, socket.toString()), cleanup);
        } catch (IOException | RuntimeException failed) {
            cleanup.run();
            throw failed;
        }
    }

    /**
     * 连接宿主回调端点：回环 TCP，或 Linux 上的 Unix 域套接字。连接是惰性的，第一次调用时才真正建立。
     *
     * @param endpoint 已按 {@link Endpoint#parse(String)} 核对过的端点：TCP 地址是回环地址的字面量加端口，不经名字解析
     * @return 通道与关闭时要做的清理
     * @throws UnsupportedOperationException 本平台连不了这种网络（命名管道，或没有 epoll 的 Unix 域套接字）
     * @throws IllegalArgumentException TCP 地址不是回环地址加端口
     */
    static Dialed dial(Endpoint endpoint, Executor executor) {
        switch (endpoint.network()) {
            case TCP -> {
                if (!Endpoint.isLoopbackAddress(endpoint.address())) {
                    throw new IllegalArgumentException("The tcp endpoint '" + endpoint.address() + "' must be a loopback address and a port.");
                }
                int colon = endpoint.address().lastIndexOf(':');
                String host = endpoint.address().substring(0, colon);
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                int port = Integer.parseInt(endpoint.address().substring(colon + 1));
                ManagedChannel channel = NettyChannelBuilder
                        .forAddress(new InetSocketAddress(host, port), InsecureChannelCredentials.create())
                        .executor(executor)
                        .build();
                return new Dialed(channel, () -> {
                });
            }
            case UNIX -> {
                if (!unixSupported()) {
                    throw new UnsupportedOperationException("unix endpoints need Linux with the epoll native library of grpc-netty-shaded");
                }
                EventLoopGroup group = epollGroup(1, "xihan-plugin-host");
                ManagedChannel channel = NettyChannelBuilder
                        .forAddress(new DomainSocketAddress(endpoint.address()), InsecureChannelCredentials.create())
                        .channelType(EpollDomainSocketChannel.class)
                        .eventLoopGroup(group)
                        .executor(executor)
                        .build();
                return new Dialed(channel, group::shutdownGracefully);
            }
            default -> throw new UnsupportedOperationException("npipe endpoints are not supported by grpc-java");
        }
    }

    /** epoll 的事件循环组：守护线程，进程退出不等它们；线程数为 0 时取 Netty 的缺省。 */
    private static EventLoopGroup epollGroup(int threads, String name) {
        return new MultiThreadIoEventLoopGroup(threads, new DefaultThreadFactory(name, true), EpollIoHandler.newFactory());
    }

    /** 一个宿主回调通道。 */
    record Dialed(ManagedChannel channel, Runnable cleanup) {
    }
}
