package com.xihanfun.framework.plugin;

import com.xihanfun.framework.invocation.CancellableContext;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.manifest.Manifest;
import com.xihanfun.framework.manifest.ManifestJson;
import com.xihanfun.framework.manifest.ManifestKind;
import com.xihanfun.framework.manifest.PluginIsolation;
import com.xihanfun.framework.operation.OperationDescriptor;
import com.xihanfun.framework.operation.OperationInvoker;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.FrameworkInfo;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.MessageScope;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 进程外插件运行时（RFC-0017，{@code spec/plugin/}）：以宿主启动的子进程身份运行，提供 {@code xihan.plugin.v1} 的 {@code Plugin} 服务。
 *
 * <p>{@link #serve(PluginOptions)} 依次：读清单并核对声明，读宿主下发的环境变量（{@code handshake.md} §1），监听本机端点，
 * 向 stdout 写一行握手行（§2），然后服务到 {@code Stop} 完成、stdin 关闭或收到终止信号。宿主的每次 RPC 先按常数时间核对会话令牌（§4）；
 * 操作调用经 SDK 的调用器与完整管道执行，调用上下文按 {@code invocation.md} §2 从元数据还原；健康以 {@code WatchHealth} 推送；
 * 处理器经 {@link Host#of(Context)} 回调宿主。日志写 stderr，一行一个 JSON 对象（{@code invocation.md} §6）。
 *
 * <p>gRPC 服务端在虚拟线程上执行每一次调用，处理器与回调可以阻塞。
 */
public final class PluginRuntime {
    /** 本 SDK 实现的协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 握手行格式版本：与协议版本无关，只在行格式变化时递增。 */
    public static final String HANDSHAKE_LINE_VERSION = "xihan-plugin/1";

    /** 携带会话令牌的元数据键：宿主调用插件、插件回调宿主的每次 RPC 都带它。 */
    public static final String SESSION_METADATA = "xihan-plugin-session";

    /** 宿主支持的协议版本，逗号分隔、升序。 */
    public static final String ENV_PROTOCOL_VERSIONS = "XIHAN_PLUGIN_PROTOCOL_VERSIONS";

    /** 宿主的原语层大版本。 */
    public static final String ENV_PRIMITIVES = "XIHAN_PLUGIN_PRIMITIVES";

    /** 本次启动的会话令牌。 */
    public static final String ENV_SESSION = "XIHAN_PLUGIN_SESSION";

    /** 宿主回调端点：{@code <network> <address>}。 */
    public static final String ENV_HOST_ADDRESS = "XIHAN_PLUGIN_HOST_ADDRESS";

    /** 实例 ID。 */
    public static final String ENV_INSTANCE = "XIHAN_PLUGIN_INSTANCE";

    /** 宿主默认区域（BCP 47）。 */
    public static final String ENV_LOCALE = "XIHAN_PLUGIN_LOCALE";

    /** 健康推送没有收到状态变化时核对一次回调健康的间隔。 */
    static final Duration HEALTH_POLL = Duration.ofSeconds(1);

    /** 宿主没有给宽限时 {@code Stop} 等在途调用的时间。 */
    static final Duration DEFAULT_STOP_GRACE = Duration.ofSeconds(5);

    /** 未声明的健康项最多记住这么多个名字，只为每个名字告警一次。 */
    private static final int WARNED_ITEMS_CAPACITY = 64;

    final PluginOptions options;
    final Manifest manifest;
    final String manifestText;
    final OperationInvoker invoker;
    final Lifecycle lifecycle = new Lifecycle();
    final Calls calls = new Calls();
    final PluginLogger logger;
    final Wire wire;
    final String instance;
    final String locale;
    final Host host;
    private final String session;
    private final ExecutorService executor;
    private final Set<String> warnedItems = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    /** Configure 成功过才在停止时调用停止回调：没配置过的插件没有要释放的资源。 */
    final AtomicBoolean configured = new AtomicBoolean();

    /** 健康回调连续失败时只记第一次，免得健康推送每秒记一条。 */
    private final AtomicBoolean healthFailing = new AtomicBoolean();

    /** 监听在写握手行之前建好；停止线程与服务端线程都读它。 */
    private volatile Transport.Listening listening;

    private PluginRuntime(PluginOptions options, Manifest manifest, String manifestText, PluginLogger logger, String session,
            String instance, String locale, Optional<Endpoint> hostEndpoint) {
        this.options = options;
        this.manifest = manifest;
        this.manifestText = manifestText;
        this.logger = logger;
        this.instance = instance;
        this.locale = locale;
        this.session = session;
        this.invoker = new OperationInvoker(options.operations(), options.pipeline());
        this.wire = new Wire(manifest.id(), options.catalogs(), locale);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.host = hostEndpoint.map(endpoint -> new Host(endpoint, session, wire, calls, executor)).orElse(null);
    }

    /**
     * 以进程外插件的身份运行，直到 {@code Stop} 完成、stdin 关闭或收到终止信号；正常停止时返回。
     *
     * @param options 选项
     * @throws FrameworkException 配置错误：清单读不出或不是进程外插件（{@code PLUGINS_VALIDATION_001}）、声明的操作不在清单里或订阅重复
     *     （{@code PLUGINS_VALIDATION_004}）、宿主下发的环境变量缺失或不合法（{@code PRIMITIVES_VALIDATION_001}）、管道排序失败
     * @throws IOException 读不出清单文件，或监听失败
     * @throws InterruptedException 等待停止时被中断
     */
    public static void serve(PluginOptions options) throws IOException, InterruptedException {
        PluginRuntime runtime = start(options);
        runtime.awaitStopped();
    }

    /**
     * 核对、监听并写出握手行；返回时已经在服务。测试经它拿到运行时。
     */
    static PluginRuntime start(PluginOptions options) throws IOException {
        PluginLogger base = PluginLogger.of(options.stderr(), PluginLogger.Level.INFORMATION);
        if (options.bridgePlatformLogging()) {
            base.bridgePlatformLogging();
        }
        Path path = options.manifest().toAbsolutePath();
        Manifest manifest = ManifestJson.readFile(path);
        String manifestText = manifestText(path);
        validate(manifest, options);

        String session = options.environment(ENV_SESSION);
        if (session == null || session.isEmpty()) {
            throw configuration(ENV_SESSION + " is required: the plugin must be started by a XiHan host.");
        }
        List<Integer> versions = parseVersions(options.environment(ENV_PROTOCOL_VERSIONS));
        Optional<Endpoint> hostEndpoint = Optional.empty();
        String hostAddress = options.environment(ENV_HOST_ADDRESS);
        if (hostAddress != null && !hostAddress.isEmpty()) {
            hostEndpoint = Optional.of(Endpoint.parse(hostAddress)
                    .orElseThrow(() -> configuration(ENV_HOST_ADDRESS + " must be '<network> <address>', got '" + hostAddress + "'.")));
        }
        String instance = Optional.ofNullable(options.environment(ENV_INSTANCE)).orElse("");
        String requestedLocale = options.environment(ENV_LOCALE);
        String locale = MessageScope.isValidLocale(requestedLocale) ? requestedLocale : MessageScope.DEFAULT_LOCALE;

        PluginLogger logger = base.with("plugin", manifest.id());
        PluginRuntime runtime = new PluginRuntime(options, manifest, manifestText, logger, session, instance, locale, hostEndpoint);
        String primitives = options.environment(ENV_PRIMITIVES);
        if (primitives != null && !primitives.isEmpty() && !primitives.equals(Integer.toString(manifest.primitives()))) {
            logger.warning("the host's primitives version differs from the plugin's; the host will reject the handshake",
                    Map.of("host", primitives, "plugin", manifest.primitives()));
        }
        if (!versions.contains(PROTOCOL_VERSION)) {
            logger.warning("the host does not list a protocol version this SDK supports; the handshake will be refused",
                    Map.of("host", versions.toString(), "plugin", PROTOCOL_VERSION));
        }
        runtime.listen();
        return runtime;
    }

    /** 监听、写握手行，再开始盯 stdin 与终止信号。 */
    private void listen() throws IOException {
        listening = Transport.listen(options.network(), new PluginService(this), new SessionGuard(session), executor);
        OutputStream stdout = options.stdout();
        try {
            // 握手行是 stdout 的第一行，此后到握手完成不再写 stdout（handshake.md §2）
            stdout.write((HANDSHAKE_LINE_VERSION + " " + listening.endpoint() + "\n").getBytes(StandardCharsets.UTF_8));
            stdout.flush();
        } catch (IOException failed) {
            listening.server().shutdownNow();
            listening.cleanup().run();
            throw failed;
        }
        logger.information("listening", Map.of("endpoint", listening.endpoint().toString(), "instance", instance, "locale", locale));
        watchStdin(options.stdin());
        watchTermination();
    }

    /** 等到停止完成，然后释放回调连接、执行器与私有目录。 */
    void awaitStopped() throws InterruptedException {
        stopped.await();
        release();
    }

    /**
     * 最多等 {@code timeout}；停止完成时释放资源。
     *
     * @return 停止完成时为真
     */
    boolean awaitStopped(Duration timeout) throws InterruptedException {
        if (!stopped.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            return false;
        }
        release();
        return true;
    }

    private void release() {
        if (host != null) {
            host.close();
        }
        executor.shutdown();
        listening.cleanup().run();
    }

    // ---- 停止 ----

    /**
     * 开始停止（{@code lifecycle.md} §5 第 1 条）：进入 {@code Draining}、拒绝新调用、在宽限内等在途调用完成，再调用回调释放资源；只执行一次。
     *
     * @return 停止回调的结果；已经在停止时为成功
     */
    Result<Unit> beginStop(String reason, Duration grace) {
        if (!stopping.compareAndSet(false, true)) {
            return Result.success();
        }
        long deadline = System.nanoTime() + grace.toNanos();
        lifecycle.transition(LifecycleState.DRAINING, null);
        calls.drain();
        // 唤醒健康推送：它看到正在停止就推最后一条并结束，不拖住服务端的优雅停止
        lifecycle.touch();
        logger.information("stopping", Map.of("reason", reason, "grace", grace.toString()));
        try {
            if (!calls.awaitIdle(grace)) {
                logger.warning("in-flight calls did not finish within the grace period", Map.of());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!configured.get()) {
            return Result.success();
        }
        Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
        try (CancellableContext scope = Context.builder().timeout(remaining).build().withCancellation()) {
            return hook("stop", () -> options.hooks().stop(scope.context(), reason, remaining));
        }
    }

    /**
     * 停止服务端：已开始的调用在宽限内结束，然后标记 {@code Stopped} 并让 {@link #serve(PluginOptions)} 返回；只执行一次。
     */
    void finishStop(Duration grace) {
        if (!finishing.compareAndSet(false, true)) {
            return;
        }
        try {
            listening.server().shutdown();
            if (!listening.server().awaitTermination(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS)) {
                listening.server().shutdownNow();
                listening.server().awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            listening.server().shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            lifecycle.transition(LifecycleState.STOPPED, null);
            logger.information("stopped", Map.of());
            stopped.countDown();
        }
    }

    /** stdin 关闭（宿主退出或崩溃）即收尾退出，不留孤儿进程（{@code handshake.md} §1）。 */
    private void watchStdin(InputStream stdin) {
        Thread.ofPlatform().daemon().name("xihan-plugin-stdin").start(() -> {
            byte[] buffer = new byte[256];
            try {
                while (stdin.read(buffer) >= 0) {
                    // 宿主不经 stdin 发数据，读到的内容丢弃
                    continue;
                }
            } catch (IOException closed) {
                // 读失败与读到末尾一样：宿主那一端已经没了
            }
            if (!stopping.get()) {
                logger.information("stdin closed; stopping", Map.of());
            }
            beginStop("shutdown", options.shutdownGrace());
            finishStop(options.shutdownGrace());
        });
    }

    /** 终止信号（Unix 的 SIGTERM）与 stdin 关闭一样当作立即收尾退出的请求（{@code lifecycle.md} §5 第 2 条）。 */
    private void watchTermination() {
        Thread hook = Thread.ofPlatform().name("xihan-plugin-termination").unstarted(() -> {
            beginStop("shutdown", options.shutdownGrace());
            finishStop(options.shutdownGrace());
        });
        Runtime.getRuntime().addShutdownHook(hook);
        Thread.ofVirtual().name("xihan-plugin-termination-cleanup").start(() -> {
            try {
                stopped.await();
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (InterruptedException | IllegalStateException ignored) {
                // 进程已经在退出：钩子正在执行或已经执行，不用撤销
            }
        });
    }

    // ---- 状态与健康 ----

    /** 判断是否接受调用：启动之后、开始停止之前。 */
    boolean serving() {
        return lifecycle.is(LifecycleState.STARTED, LifecycleState.READY, LifecycleState.DEGRADED) && !calls.isDraining();
    }

    /**
     * 计算当前健康：没有回调时按运行时状态；回调报的状态只能是可自报的状态，健康项只保留清单声明的名字。
     * 可服务时回调报的 {@code Ready}、{@code Degraded}、{@code Failed} 同步到运行时状态；停止与失败之后以运行时状态为准。
     */
    PluginHealth health() {
        LifecycleState state = lifecycle.state();
        Optional<PluginHealth> reported;
        try {
            reported = options.hooks().health();
            healthFailing.set(false);
        } catch (RuntimeException failed) {
            if (healthFailing.compareAndSet(false, true)) {
                logger.error("the health hook failed; reporting the runtime state", failed, Map.of());
            }
            reported = Optional.empty();
        }
        if (reported == null || reported.isEmpty()) {
            return new PluginHealth(state, List.of(), lifecycle.error());
        }
        PluginHealth health = reported.get();
        List<HealthItem> items = new ArrayList<>();
        for (HealthItem item : health.items()) {
            if (manifest.health().contains(item.name())) {
                items.add(item);
            } else {
                warnUndeclared(item.name());
            }
        }
        if (state == LifecycleState.READY || state == LifecycleState.DEGRADED) {
            LifecycleState self = health.state();
            if (self == LifecycleState.READY || self == LifecycleState.DEGRADED || self == LifecycleState.FAILED) {
                lifecycle.transition(self, health.error().orElse(null));
            }
        }
        LifecycleState current = lifecycle.state();
        return new PluginHealth(current, items, health.error().or(lifecycle::error));
    }

    private void warnUndeclared(String name) {
        // 名字表有上限：并发时可能略超几个，但不随回调报的名字无限增长
        if (warnedItems.size() < WARNED_ITEMS_CAPACITY && warnedItems.add(name)) {
            logger.warning("the health item is not declared in contributes.health and is not reported", Map.of("item", name));
        }
    }

    // ---- 回调与上下文 ----

    /**
     * 执行一个生命周期回调：返回的失败原样交出；抛出的异常记日志并翻译为 {@code PLUGINS_UNEXPECTED_001}，框架异常的错误放进 inner。
     */
    Result<Unit> hook(String phase,
            Supplier<Result<Unit>> body) {
        try {
            Result<Unit> result = body.get();
            if (result == null) {
                return Result.fail(wire.failed(phase, "the hook returned null"));
            }
            return result;
        } catch (FrameworkException failed) {
            logger.error("the " + phase + " hook threw", failed, Map.of());
            return Result.fail(wire.failed(phase, failed.getMessage(), failed.toError()));
        } catch (RuntimeException failed) {
            logger.error("the " + phase + " hook threw", failed, Map.of());
            return Result.fail(wire.failed(phase, failed.getClass().getName()));
        }
    }

    /**
     * 为一次宿主发来的调用建上下文并执行：截止时间与追踪来自元数据，gRPC 调用取消时上下文随之取消；上下文里放入宿主回调客户端。
     */
    <T> T withContext(PluginProto.InvocationMetadata metadata, Function<Context, T> body) {
        io.grpc.Context transport = io.grpc.Context.current();
        Optional<Instant> transportDeadline = Optional.ofNullable(transport.getDeadline())
                .map(deadline -> Instant.now().plusNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS)));
        try (CancellableContext scope = Wire.context(metadata, transportDeadline).build().withCancellation()) {
            io.grpc.Context.CancellationListener listener = cancelled -> scope.cancel();
            transport.addListener(listener, Runnable::run);
            try {
                Context context = host == null ? scope.context() : scope.context().withValue(Host.KEY, host);
                return body.apply(context);
            } finally {
                transport.removeListener(listener);
            }
        }
    }

    // ---- 启动前的核对 ----

    /**
     * 核对清单与声明（{@code handshake.md} §3、RFC-0017「范围」）：在监听之前拒绝宿主一定会拒绝的插件，不让作者以为声明生效了。
     */
    private static void validate(Manifest manifest, PluginOptions options) {
        if (manifest.kind() != ManifestKind.PLUGIN || manifest.isolation().orElse(null) != PluginIsolation.OUT_OF_PROCESS) {
            throw invalidSource("The manifest '" + manifest.id() + "' must describe a plugin with isolation 'out-of-process'.");
        }
        if (manifest.entry().isEmpty()) {
            throw invalidSource("The plugin manifest '" + manifest.id() + "' must declare 'entry'.");
        }
        if (!manifest.effectiveContributions().pipeline().isEmpty()) {
            throw invalidSource("The plugin '" + manifest.id()
                    + "' must not declare 'contributes.pipeline': only wasm plugins can contribute middleware.");
        }
        int primitives = Integer.parseInt(FrameworkInfo.SPEC_VERSION);
        if (manifest.primitives() != primitives) {
            throw invalidSource("The plugin manifest '" + manifest.id() + "' declares primitives " + manifest.primitives()
                    + ", but this SDK implements primitives " + primitives + ".");
        }
        for (OperationDescriptor descriptor : options.operations().list()) {
            if (!manifest.operations().contains(descriptor.contract().id())) {
                throw mismatch("The operation '" + descriptor.contract().id() + "' is not declared in 'contributes.operations' of '"
                        + manifest.id() + "'.");
            }
        }
        // 订阅按（主题，类型）对去重而不看方式：同一对不能以两种方式各声明一次（handshake.md §3 第 5 条）
        Set<String> declared = new HashSet<>();
        for (PluginOptions.SubscriptionHandler handler : options.subscriptions()) {
            Subscription subscription = handler.subscription();
            String key = subscription.topic() + "\n" + subscription.messageType().orElse("");
            if (!declared.add(key)) {
                throw mismatch("The subscription to '" + subscription.topic() + "' with the message type '"
                        + subscription.messageType().orElse("") + "' is declared more than once by '" + manifest.id() + "'.");
            }
        }
    }

    /** 读清单原文，跳过开头的 UTF-8 BOM：握手时原样交给宿主。 */
    private static String manifestText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        boolean hasBom = bytes.length >= bom.length && Arrays.equals(bytes, 0, bom.length, bom, 0, bom.length);
        return new String(bytes, hasBom ? bom.length : 0, bytes.length - (hasBom ? bom.length : 0), StandardCharsets.UTF_8);
    }

    /** 解析宿主支持的协议版本：逗号分隔的非负整数。 */
    static List<Integer> parseVersions(String text) {
        if (text == null || text.isBlank()) {
            throw configuration(ENV_PROTOCOL_VERSIONS + " is required: the plugin must be started by a XiHan host.");
        }
        List<Integer> versions = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String trimmed = part.strip();
            if (!trimmed.matches("[0-9]{1,10}") || Long.parseLong(trimmed) > Integer.MAX_VALUE) {
                throw configuration(ENV_PROTOCOL_VERSIONS + " must be a comma-separated list of versions, got '" + text + "'.");
            }
            versions.add(Integer.parseInt(trimmed));
        }
        return List.copyOf(versions);
    }

    /**
     * 协商协议版本（{@code handshake.md} §3 第 1 条）：选宿主列表里最高的、本 SDK 也支持的版本。
     *
     * @return 选定的版本；没有共同版本时为空
     */
    static Optional<Integer> negotiate(List<Integer> host) {
        return host.stream().filter(version -> version == PROTOCOL_VERSION).max(Integer::compare);
    }

    /** 把已登记的操作列成日志字段。 */
    Map<String, Object> summary() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operations", invoker.registry().list().stream()
                .map(descriptor -> descriptor.contract().id())
                .collect(Collectors.joining(",")));
        fields.put("subscriptions", options.subscriptions().size());
        return fields;
    }

    private static FrameworkException invalidSource(String message) {
        return new FrameworkException(PluginErrors.INVALID_SOURCE, message);
    }

    private static FrameworkException mismatch(String message) {
        return new FrameworkException(PluginErrors.HANDSHAKE_MISMATCH, message);
    }

    private static FrameworkException configuration(String message) {
        return new FrameworkException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
