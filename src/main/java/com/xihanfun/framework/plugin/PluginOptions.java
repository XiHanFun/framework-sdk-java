package com.xihanfun.framework.plugin;

import com.xihanfun.framework.manifest.Manifest;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PluginRuntime#serve(PluginOptions)} 的输入：清单、操作、管道、订阅、消息目录与生命周期回调。
 *
 * <p>不可变；经 {@link #builder()} 创建。缺省值面向宿主启动的进程：清单是工作目录（即插件目录）里的 {@code xihan.manifest.json}，
 * 环境变量与标准输入输出取进程自己的；测试可以换掉它们。
 */
public final class PluginOptions {
    private final Path manifest;
    private final OperationRegistry operations;
    private final PipelineBuilder pipeline;
    private final List<SubscriptionHandler> subscriptions;
    private final List<PluginCatalog> catalogs;
    private final PluginHooks hooks;
    private final Network network;
    private final Duration shutdownGrace;
    private final Map<String, String> environment;
    private final InputStream stdin;
    private final OutputStream stdout;
    private final OutputStream stderr;
    private final boolean bridgePlatformLogging;

    private PluginOptions(Builder builder) {
        this.manifest = builder.manifest;
        this.operations = builder.operations;
        this.pipeline = builder.pipeline.copy();
        this.subscriptions = List.copyOf(builder.subscriptions);
        this.catalogs = List.copyOf(builder.catalogs);
        this.hooks = builder.hooks;
        this.network = builder.network;
        this.shutdownGrace = builder.shutdownGrace;
        this.environment = builder.environment == null ? null : Map.copyOf(builder.environment);
        this.stdin = builder.stdin;
        this.stdout = builder.stdout;
        this.stderr = builder.stderr;
        this.bridgePlatformLogging = builder.bridgePlatformLogging;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 一个订阅与它的处理器。 */
    record SubscriptionHandler(Subscription subscription, EventHandler handler) {
    }

    Path manifest() {
        return manifest;
    }

    OperationRegistry operations() {
        return operations;
    }

    PipelineBuilder pipeline() {
        return pipeline;
    }

    List<SubscriptionHandler> subscriptions() {
        return subscriptions;
    }

    List<PluginCatalog> catalogs() {
        return catalogs;
    }

    PluginHooks hooks() {
        return hooks;
    }

    Optional<Network> network() {
        return Optional.ofNullable(network);
    }

    Duration shutdownGrace() {
        return shutdownGrace;
    }

    String environment(String name) {
        return environment == null ? System.getenv(name) : environment.get(name);
    }

    InputStream stdin() {
        return stdin == null ? System.in : stdin;
    }

    OutputStream stdout() {
        return stdout == null ? new FileOutputStream(FileDescriptor.out) : stdout;
    }

    OutputStream stderr() {
        return stderr == null ? new FileOutputStream(FileDescriptor.err) : stderr;
    }

    boolean bridgePlatformLogging() {
        return bridgePlatformLogging;
    }

    /**
     * {@link PluginOptions} 的构建器；不是线程安全的。
     */
    public static final class Builder {
        private Path manifest = Path.of(Manifest.FILE_NAME);
        private OperationRegistry operations = new OperationRegistry();
        private PipelineBuilder pipeline = new PipelineBuilder();
        private final List<SubscriptionHandler> subscriptions = new ArrayList<>();
        private final List<PluginCatalog> catalogs = new ArrayList<>();
        private PluginHooks hooks = PluginHooks.NONE;
        private Network network;
        private Duration shutdownGrace = Duration.ofSeconds(3);
        private Map<String, String> environment;
        private InputStream stdin;
        private OutputStream stdout;
        private OutputStream stderr;
        private boolean bridgePlatformLogging = true;

        private Builder() {
        }

        /**
         * 设置清单文件：握手时原样交给宿主，必须与插件目录里的文件逐字段一致（{@code handshake.md} §3 第 3 条）。
         *
         * @param value 路径；相对路径按工作目录解析，缺省是工作目录里的 {@code xihan.manifest.json}
         * @return 本构建器
         */
        public Builder manifest(Path value) {
            this.manifest = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置插件服务的操作：每个 ID 都必须出现在清单 {@code contributes.operations} 里。
         *
         * @param value 登记表
         * @return 本构建器
         */
        public Builder operations(OperationRegistry value) {
            this.operations = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置插件进程里的管道：宿主发来的调用经它与调用器的内置步骤执行；缺省只有内置步骤。
         *
         * @param value 管道构建器；构建选项时复制一份
         * @return 本构建器
         */
        public Builder pipeline(PipelineBuilder value) {
            this.pipeline = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 订阅一个主题。
         *
         * @param subscription 订阅
         * @param handler 处理器
         * @return 本构建器
         */
        public Builder subscribe(Subscription subscription, EventHandler handler) {
            subscriptions.add(new SubscriptionHandler(Objects.requireNonNull(subscription, "subscription"),
                    Objects.requireNonNull(handler, "handler")));
            return this;
        }

        /**
         * 加一份握手时交给宿主的消息目录：插件自己的消息键都要在里面。
         *
         * @param value 目录
         * @return 本构建器
         */
        public Builder catalog(PluginCatalog value) {
            catalogs.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * 设置生命周期回调。
         *
         * @param value 回调
         * @return 本构建器
         */
        public Builder hooks(PluginHooks value) {
            this.hooks = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 指定监听的网络类型；缺省时 Linux 上有 epoll 原生库用 {@link Network#UNIX}，其余用 {@link Network#TCP}。
         *
         * @param value {@link Network#UNIX} 或 {@link Network#TCP}
         * @return 本构建器
         * @throws IllegalArgumentException 给了 {@link Network#NPIPE}：本 SDK 不监听命名管道
         */
        public Builder network(Network value) {
            Objects.requireNonNull(value, "value");
            if (value == Network.NPIPE) {
                throw new IllegalArgumentException("The plugin runtime cannot listen on a named pipe; use 'unix' or 'tcp'.");
            }
            this.network = value;
            return this;
        }

        /**
         * 设置 stdin 关闭或收到终止信号时等待在途调用的时间（{@code lifecycle.md} §5 第 2 条：宿主随后只再等 5 秒）；缺省 3 秒。
         *
         * @param value 时长
         * @return 本构建器
         * @throws IllegalArgumentException 为负
         */
        public Builder shutdownGrace(Duration value) {
            Objects.requireNonNull(value, "value");
            if (value.isNegative()) {
                throw new IllegalArgumentException("The shutdown grace must not be negative.");
            }
            this.shutdownGrace = value;
            return this;
        }

        /**
         * 换掉进程的环境变量（测试用）。
         *
         * @param value 环境变量
         * @return 本构建器
         */
        public Builder environment(Map<String, String> value) {
            this.environment = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 换掉标准输入输出（测试用）：stdin 关闭即退出，stdout 只写握手行，stderr 写日志。
         *
         * @param in 标准输入
         * @param out 标准输出
         * @param err 标准错误
         * @return 本构建器
         */
        public Builder streams(InputStream in, OutputStream out, OutputStream err) {
            this.stdin = Objects.requireNonNull(in, "in");
            this.stdout = Objects.requireNonNull(out, "out");
            this.stderr = Objects.requireNonNull(err, "err");
            return this;
        }

        /**
         * 是否把 {@code java.util.logging} 的根日志改写成协议的 JSON 行（缺省是）：gRPC 与 {@code System.Logger} 缺省经它写 stderr。
         *
         * @param value 为真时改写
         * @return 本构建器
         */
        public Builder bridgePlatformLogging(boolean value) {
            this.bridgePlatformLogging = value;
            return this;
        }

        /**
         * 构建选项；清单与声明在 {@link PluginRuntime#serve(PluginOptions)} 里核对。
         *
         * @return 选项
         */
        public PluginOptions build() {
            return new PluginOptions(this);
        }
    }
}
