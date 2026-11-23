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
import com.xihanfun.framework.primitives.Error;
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
 * <p>{@link #serve(PluginOptions)} 依次：读清单并核对声明（不合即拒绝启动：不监听、不写握手行，stderr 记一条 {@code error}），读宿主下发的
 * 环境变量（{@code handshake.md} §1），按 {@code XIHAN_PLUGIN_HOST_ADDRESS} 选回调端点（§4），监听本机端点，向 stdout 写一行握手行（§2），
 * 然后服务到 {@code Stop} 完成、stdin 关闭或收到终止信号。宿主的每次 RPC 先按常数时间核对会话令牌（§4），再按 {@code lifecycle.md} §7 的次序表；
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

    /** 宿主回调端点：一个或多个 {@code <network> <address>}，按宿主的偏好排序、以 {@code ;} 分隔。 */
    public static final String ENV_HOST_ADDRESS = "XIHAN_PLUGIN_HOST_ADDRESS";

    /** 实例 ID。 */
    public static final String ENV_INSTANCE = "XIHAN_PLUGIN_INSTANCE";

    /** 宿主默认区域（BCP 47）。 */
    public static final String ENV_LOCALE = "XIHAN_PLUGIN_LOCALE";

    /** 可服务期间 {@code WatchHealth} 重新求健康回调的间隔：宿主 {@code HealthInterval} 的缺省值（{@code lifecycle.md} §7.4）。 */
    static final Duration HEALTH_INTERVAL = Duration.ofSeconds(10);

    /** 宿主给的宽限为 0 时 {@code Stop} 等在途调用的时间（{@code lifecycle.md} §7.5）。 */
    static final Duration DEFAULT_STOP_GRACE = Duration.ofSeconds(5);

    /** stdin 关闭与终止信号的收尾时限：短于宿主发出终止信号后再等的 5 秒（{@code lifecycle.md} §7.7）。 */
    static final Duration SHUTDOWN_LIMIT = Duration.ofSeconds(3);

    /** 停服务端时等已开始的 RPC 结束的上限：在途调用已经等过、取消过，这里只给应答送出的时间。 */
    private static final Duration SERVER_DRAIN = Duration.ofSeconds(1);

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

    /** 次序表里只接受一次的 RPC 各占一个标记：并发的两次同样的调用只有一次能执行（{@code lifecycle.md} §7.1）。 */
    final AtomicBoolean handshaking = new AtomicBoolean();
    final AtomicBoolean configuring = new AtomicBoolean();
    final AtomicBoolean starting = new AtomicBoolean();

    /** 启动回调成功过：只有这样停止回调才会被调用（{@code lifecycle.md} §7.5）。 */
    final AtomicBoolean startSucceeded = new AtomicBoolean();

    /** 一次停止（{@code Stop} 或收尾）已经开始：停止中再来的 {@code Stop} 以 {@code FailedPrecondition} 拒绝。 */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /** 停止回调在进程里最多调一次。 */
    private final AtomicBoolean stopHookCalled = new AtomicBoolean();

    /** stdin 关闭或终止信号的收尾只执行一次。 */
    private final AtomicBoolean terminating = new AtomicBoolean();

    /** 停服务端只执行一次。 */
    private final AtomicBoolean finishing = new AtomicBoolean();

    /** 停止已经应答或收尾已经结束：健康推送据此结束流（{@code lifecycle.md} §7.4）。 */
    private volatile boolean closing;

    private final CountDownLatch stopped = new CountDownLatch(1);

    /** 健康回调连续抛出时只记第一次，免得每次求健康都记一条。 */
    private final AtomicBoolean healthFailing = new AtomicBoolean();

    /** 最近一次求得的健康，与当时的迁移计数：状态没有迁移过时，不求回调的快照沿用它。 */
    private volatile HealthSnapshot lastHealth;

    /** 监听在写握手行之前建好；停止线程与服务端线程都读它。 */
    private volatile Transport.Listening listening;

    /** 终止信号的关闭钩子；测试里不登记。 */
    private volatile Thread terminationHook;

    /** 一次求得的健康与当时的迁移计数。 */
    private record HealthSnapshot(PluginHealth health, long transitions) {
    }

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
        this.wire = new Wire(manifest.id());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.host = new Host(hostEndpoint, session, wire, calls, executor, manifest.permissions());
    }

    /**
     * 以进程外插件的身份运行，直到 {@code Stop} 完成、stdin 关闭或收到终止信号；正常停止时返回，{@code main} 随之结束，进程以退出码 0 退出。
     *
     * <p>stdin 关闭或收到终止信号时，运行时最多用 3 秒收尾，到时不论是否完成都以退出码 0 结束进程（{@code lifecycle.md} §7.7）；
     * 关闭钩子同样作用于服务期间作者自己调用的 {@code System.exit}。
     *
     * @param options 选项
     * @throws FrameworkException 拒绝启动：清单读不出或不是进程外插件（{@code PLUGINS_VALIDATION_001}）、声明的操作不在清单里或订阅重复
     *     （{@code PLUGINS_VALIDATION_004}）、宿主下发的会话令牌或协议版本缺失或不合法（{@code PRIMITIVES_VALIDATION_001}）、管道排序失败；
     *     拒绝之前已在 stderr 记了一条 {@code error}
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
        Manifest manifest;
        String manifestText;
        String session;
        List<Integer> versions;
        try {
            Path path = options.manifest().toAbsolutePath();
            manifest = ManifestJson.readFile(path);
            manifestText = manifestText(path);
            validate(manifest, options);
            session = options.environment(ENV_SESSION);
            if (session == null || session.isEmpty()) {
                throw configuration(ENV_SESSION + " is required: the plugin must be started by a XiHan host.");
            }
            versions = parseVersions(options.environment(ENV_PROTOCOL_VERSIONS));
        } catch (FrameworkException | IOException refused) {
            // 拒绝启动：不监听、不写握手行，在 stderr 记一条 error；宿主看到的是进程在握手之前退出（handshake.md §1、§5）
            base.error("the plugin refuses to start: " + refused.getMessage(), null, refusal(refused));
            throw refused;
        }
        String hostAddress = options.environment(ENV_HOST_ADDRESS);
        // 按宿主的偏好取第一个本 SDK 能连的端点，之后不换（handshake.md §4）
        Optional<Endpoint> hostEndpoint = Endpoint.select(hostAddress, Transport.dialable());
        String instance = Optional.ofNullable(options.environment(ENV_INSTANCE)).orElse("");
        String requestedLocale = options.environment(ENV_LOCALE);
        String locale = MessageScope.isValidLocale(requestedLocale) ? requestedLocale : MessageScope.DEFAULT_LOCALE;

        PluginLogger logger = base.with("plugin", manifest.id());
        PluginRuntime runtime;
        try {
            runtime = new PluginRuntime(options, manifest, manifestText, logger, session, instance, locale, hostEndpoint);
        } catch (FrameworkException refused) {
            // 管道排序失败（成环、约束目标未登记）同样是拒绝启动
            logger.error("the plugin refuses to start: " + refused.getMessage(), null, refusal(refused));
            throw refused;
        }
        if (hostEndpoint.isEmpty()) {
            // 照常服务：回调一律以 plugins.host.unavailable 失败、不发请求（handshake.md §4）
            logger.warning("no supported callback endpoint; callbacks to the host will fail",
                    Map.of("hostAddress", Optional.ofNullable(hostAddress).orElse(""), "reason", Host.NO_ENDPOINT));
        }
        String primitives = options.environment(ENV_PRIMITIVES);
        if (primitives != null && !primitives.isEmpty() && !primitives.equals(Integer.toString(manifest.primitives()))) {
            logger.warning("the host's primitives version differs from the plugin's; the host will reject the handshake",
                    Map.of("host", primitives, "plugin", manifest.primitives()));
        }
        if (!versions.contains(PROTOCOL_VERSION)) {
            logger.warning("the host does not list a protocol version this SDK supports; the handshake will be refused",
                    Map.of("host", versions.toString(), "plugin", PROTOCOL_VERSION));
        }
        try {
            runtime.listen();
        } catch (IOException | RuntimeException failed) {
            logger.error("the plugin cannot listen: " + failed.getMessage(), null, refusal(failed));
            runtime.executor.shutdown();
            throw failed;
        }
        return runtime;
    }

    /** 拒绝启动时日志里的字段：框架异常带错误码，其余带异常的简单类型名。 */
    private static Map<String, Object> refusal(Exception refused) {
        return refused instanceof FrameworkException framework ? Map.of("code", framework.code()) : Map.of("exception", Wire.typeName(refused));
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
        logger.information("listening", Map.of("endpoint", listening.endpoint().toString(), "instance", instance, "locale", locale,
                "hostEndpoint", host.endpoint().map(Endpoint::toString).orElse("")));
        watchStdin(options.stdin());
        if (options.exitProcess()) {
            watchTermination();
        }
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
        host.close();
        executor.shutdown();
        listening.cleanup().run();
        Thread hook = terminationHook;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException exiting) {
                // 进程已经在退出：钩子正在执行或已经执行，不用撤销
            }
        }
    }

    // ---- 停止与收尾 ----

    /**
     * 核对 {@code Stop} 的次序（{@code lifecycle.md} §7.1）：{@code Declared}、{@code Draining}、{@code Stopped} 之外，且没有别的停止在进行。
     *
     * @return 接受时为真，此后由调用方执行 {@link #stop(String, Duration)}
     */
    boolean claimStop() {
        if (lifecycle.is(LifecycleState.DECLARED, LifecycleState.DRAINING, LifecycleState.STOPPED)) {
            return false;
        }
        return stopping.compareAndSet(false, true);
    }

    /**
     * 执行 {@code Stop}（{@code lifecycle.md} §7.5）：启动回调没成功过时什么都不调；否则拒绝新调用（可服务时进入 {@code Draining}），
     * 等在途调用到宽限期，到时触发它们的取消信号、不再等，然后调停止回调。
     *
     * @return {@code StopResponse.status} 的内容
     */
    Result<Unit> stop(String reason, Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos();
        if (!startSucceeded.get()) {
            // 宿主在探测启动、配置或启动失败后都这样停止插件：没有要释放的资源
            logger.information("stopping before a successful start", Map.of("reason", reason));
            return Result.success();
        }
        calls.drain();
        // Started、Ready、Degraded 进入 Draining；健康回调报过 Failed 的不迁移，在途调用照样处理
        lifecycle.transition(LifecycleState.DRAINING, null);
        logger.information("stopping", Map.of("reason", reason, "grace", grace.toString()));
        awaitCalls(grace);
        return stopHook(reason, remaining(deadline));
    }

    /** 在宽限内等在途调用；等满时触发它们的取消信号（只经令牌通知，RFC-0003），不再等。 */
    private void awaitCalls(Duration grace) {
        try {
            if (!calls.awaitIdle(grace)) {
                logger.warning("in-flight calls did not finish within the grace period; cancelling them", Map.of());
                calls.cancelAll();
            }
        } catch (InterruptedException interrupted) {
            calls.cancelAll();
            Thread.currentThread().interrupt();
        }
    }

    /** 调停止回调，在进程里最多一次；返回的失败与抛出都写进结果，进程照常退出。 */
    private Result<Unit> stopHook(String reason, Duration remaining) {
        if (!stopHookCalled.compareAndSet(false, true)) {
            return Result.success();
        }
        try (CancellableContext scope = Context.builder().timeout(remaining).build().withCancellation()) {
            Context context = scope.context().withValue(Host.KEY, host);
            Result<Unit> outcome = hook("stop", () -> options.hooks().stop(context, reason, remaining));
            outcome.error().ifPresent(error -> logger.warning("the stop hook failed", describe(error)));
            return outcome;
        }
    }

    /**
     * stdin 关闭与终止信号的收尾（{@code lifecycle.md} §7.7）：可服务的插件进入 {@code Draining}（其他状态不迁移），立即触发全部在途调用的取消信号
     * 并等它们结束；启动回调成功过而停止回调还没调过的，调一次（{@code reason} 为 {@code shutdown}）。从收到起最多 3 秒，正在处理的
     * {@code Stop} 照常进行，但同样受这 3 秒限制；到时进程不论是否完成都以退出码 0 结束。
     */
    void terminate(String cause) {
        long deadline = System.nanoTime() + SHUTDOWN_LIMIT.toNanos();
        if (!terminating.compareAndSet(false, true)) {
            awaitStoppedUntil(deadline);
            return;
        }
        if (stopped.getCount() > 0) {
            logger.information(cause + "; shutting down", Map.of("limit", SHUTDOWN_LIMIT.toString()));
        }
        // 已经停下（Stop 之后）也照样计时：serve 返回后进程若被作者的非守护线程留住，到时仍以退出码 0 结束
        armShutdownLimit(deadline);
        if (stopping.compareAndSet(false, true)) {
            calls.drain();
            if (lifecycle.is(LifecycleState.READY, LifecycleState.DEGRADED)) {
                lifecycle.transition(LifecycleState.DRAINING, null);
            }
            calls.cancelAll();
            try {
                calls.awaitIdle(remaining(deadline));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (startSucceeded.get()) {
                stopHook("shutdown", remaining(deadline));
            }
            finishStop();
        }
        awaitStoppedUntil(deadline);
    }

    /**
     * 到收尾时限时结束进程：运行时在宿主启动的进程里以退出码 0 结束（{@code Runtime.halt}，不再等作者的非守护线程与其他关闭钩子）；
     * 测试在本进程里跑运行时，到时只强行停下服务端。
     */
    private void armShutdownLimit(long deadline) {
        Thread.ofPlatform().daemon().name("xihan-plugin-shutdown-limit").start(() -> {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (options.exitProcess()) {
                Runtime.getRuntime().halt(0);
            }
            forceFinish();
        });
    }

    /** 停止已经应答，或收尾已经结束：结束健康推送的长流，免得它们拖住服务端的停止。 */
    void endStreams() {
        closing = true;
        lifecycle.touch();
    }

    boolean closing() {
        return closing;
    }

    /**
     * 停服务端：已开始的 RPC 最多再等 1 秒，然后标记 {@code Stopped}（从 {@code Draining}）并让 {@link #serve(PluginOptions)} 返回；只执行一次。
     */
    void finishStop() {
        if (!finishing.compareAndSet(false, true)) {
            return;
        }
        endStreams();
        try {
            listening.server().shutdown();
            if (!listening.server().awaitTermination(SERVER_DRAIN.toMillis(), TimeUnit.MILLISECONDS)) {
                listening.server().shutdownNow();
                listening.server().awaitTermination(SERVER_DRAIN.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            listening.server().shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            lifecycle.transition(LifecycleState.STOPPED, null);
            logger.information("stopped", Map.of("state", lifecycle.state().wireName()));
            stopped.countDown();
        }
    }

    /** 收尾到时仍没停下：强行停服务端并让 {@link #serve(PluginOptions)} 返回。 */
    private void forceFinish() {
        if (stopped.getCount() == 0) {
            return;
        }
        endStreams();
        listening.server().shutdownNow();
        lifecycle.transition(LifecycleState.STOPPED, null);
        stopped.countDown();
    }

    private void awaitStoppedUntil(long deadline) {
        try {
            stopped.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /** stdin 读到结尾或读出错即收尾退出，不留孤儿进程（{@code handshake.md} §1、{@code lifecycle.md} §7.7）。 */
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
            terminate("stdin closed");
        });
    }

    /**
     * 终止信号（Unix 的 SIGTERM）与 stdin 关闭一样收尾（{@code lifecycle.md} §7.7）：关闭钩子里收尾，最后以退出码 0 结束——
     * 不这样做时 JVM 收到 SIGTERM 以 143 退出。
     */
    private void watchTermination() {
        Thread hook = Thread.ofPlatform().name("xihan-plugin-termination").unstarted(() -> {
            terminate("terminated by a signal");
            Runtime.getRuntime().halt(0);
        });
        Runtime.getRuntime().addShutdownHook(hook);
        terminationHook = hook;
    }

    // ---- 状态与健康 ----

    /** 判断是否接受调用与投递：只有 {@code Ready} 与 {@code Degraded} 是可服务的状态（{@code lifecycle.md} §7.3），开始停止之后不再接受。 */
    boolean servable() {
        return lifecycle.is(LifecycleState.READY, LifecycleState.DEGRADED) && !calls.isDraining();
    }

    /**
     * 启动回调成功之后进入就绪（{@code lifecycle.md} §7.3）：有健康回调时立即求一次，按结果进入 {@code Ready}、{@code Degraded} 或 {@code Failed}；
     * 没有健康回调，或回调报的是这三种之外的状态时进入 {@code Ready}；回调抛出时这一次的结果是 {@code Degraded}，插件按它进入 {@code Degraded}。
     */
    void settle() {
        PluginHealth health = evaluate(true);
        lifecycle.transition(health.state(), health.error().orElse(null));
        lastHealth = new HealthSnapshot(new PluginHealth(lifecycle.state(), health.items(), health.error().or(lifecycle::error)),
                lifecycle.transitions());
    }

    /**
     * 求一次健康（{@code lifecycle.md} §7.4）：{@code Start} 之前报本地状态，没有健康项、不调健康回调；{@code Ready} 与 {@code Degraded} 期间
     * {@code ask} 为真时求一次健康回调；其余状态照实报，不调健康回调。
     *
     * @param ask 为真时可服务期间求一次健康回调；为假时沿用最近一次的结果（状态迁移过则按当前状态）
     */
    PluginHealth health(boolean ask) {
        LifecycleState state = lifecycle.state();
        HealthSnapshot last = lastHealth;
        switch (state) {
            case DECLARED, RESOLVED, CONFIGURED -> {
                return new PluginHealth(state, List.of(), Optional.empty());
            }
            case READY, DEGRADED -> {
                if (ask) {
                    PluginHealth health = evaluate(false);
                    lastHealth = new HealthSnapshot(health, lifecycle.transitions());
                    return health;
                }
                if (last != null && last.transitions() == lifecycle.transitions()) {
                    return last.health();
                }
                return new PluginHealth(state, last == null ? List.of() : last.health().items(), lifecycle.error());
            }
            default -> {
                return new PluginHealth(state, last == null ? List.of() : last.health().items(),
                        lifecycle.error().or(() -> last == null ? Optional.empty() : last.health().error()));
            }
        }
    }

    /**
     * 调一次健康回调：健康项只保留清单声明的名字；回调报的 {@code Ready}、{@code Degraded}、{@code Failed} 按合法迁移同步到插件状态
     * （{@code settling} 时由 {@link #settle()} 迁移）；抛出时这一次的结果是 {@code Degraded}、没有健康项，错误为 {@code PLUGINS_UNEXPECTED_001}，
     * 状态也与回调报了 {@code Degraded} 一样迁到 {@code Degraded}。
     *
     * @param settling 为真时是 {@code Start} 之后的第一次：返回的状态是要进入的状态，不在这里迁移
     */
    private PluginHealth evaluate(boolean settling) {
        Optional<PluginHealth> reported;
        try {
            reported = options.hooks().health();
            if (reported == null) {
                throw new NullPointerException("The health hook returned null.");
            }
            healthFailing.set(false);
        } catch (RuntimeException failed) {
            if (healthFailing.compareAndSet(false, true)) {
                logger.error("the health hook threw", failed, Map.of());
            }
            Error error = wire.failed("health", Wire.typeName(failed));
            if (!settling) {
                // 与回调报了 Degraded 一样迁到 Degraded，之后的结果照常决定状态（lifecycle.md §7.4）
                lifecycle.transition(LifecycleState.DEGRADED, error);
            }
            return new PluginHealth(LifecycleState.DEGRADED, List.of(), Optional.of(error));
        }
        if (reported.isEmpty()) {
            return settling ? new PluginHealth(LifecycleState.READY, List.of(), Optional.empty())
                    : new PluginHealth(lifecycle.state(), List.of(), lifecycle.error());
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
        LifecycleState self = health.state();
        boolean adopted = self == LifecycleState.READY || self == LifecycleState.DEGRADED || self == LifecycleState.FAILED;
        if (settling) {
            return new PluginHealth(adopted ? self : LifecycleState.READY, items, adopted ? health.error() : Optional.empty());
        }
        if (adopted) {
            // Failed 之后的迁移都不合法，插件不会从 Failed 回来
            lifecycle.transition(self, health.error().orElse(null));
        }
        return new PluginHealth(lifecycle.state(), items, health.error().or(lifecycle::error));
    }

    private void warnUndeclared(String name) {
        // 名字表有上限：并发时可能略超几个，但不随回调报的名字无限增长；同一个名字在进程里只告警一次
        if (warnedItems.size() < WARNED_ITEMS_CAPACITY && warnedItems.add(name)) {
            logger.warning("the health item is not declared in contributes.health and is not reported", Map.of("item", name));
        }
    }

    // ---- 回调与上下文 ----

    /**
     * 执行一个生命周期回调：返回的失败原样交出；抛出的异常（与返回 {@code null}）记进 stderr，翻译为 {@code PLUGINS_UNEXPECTED_001} +
     * {@value PluginMessages#FAILED}，{@code reason} 是异常的简单类型名、不带异常的文本（{@code lifecycle.md} §7.6）。
     */
    Result<Unit> hook(String phase, Supplier<Result<Unit>> body) {
        try {
            Result<Unit> result = body.get();
            if (result == null) {
                throw new NullPointerException("The " + phase + " hook returned null.");
            }
            return result;
        } catch (RuntimeException failed) {
            logger.error("the " + phase + " hook threw", failed, Map.of());
            return Result.fail(wire.failed(phase, Wire.typeName(failed)));
        }
    }

    /**
     * 以一次宿主 RPC 的截止时间与取消建上下文并执行：截止时间取元数据与 gRPC 截止时间中较早的，gRPC 调用取消时上下文随之取消；
     * 上下文里放入宿主回调客户端。
     */
    <T> T withScope(PluginProto.InvocationMetadata metadata, Function<CancellableContext, T> body) {
        io.grpc.Context transport = io.grpc.Context.current();
        Optional<Instant> transportDeadline = Optional.ofNullable(transport.getDeadline())
                .map(deadline -> Instant.now().plusNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS)));
        try (CancellableContext scope = Wire.context(metadata, transportDeadline).build().withCancellation()) {
            io.grpc.Context.CancellationListener listener = cancelled -> scope.cancel();
            transport.addListener(listener, Runnable::run);
            try {
                return body.apply(scope);
            } finally {
                transport.removeListener(listener);
            }
        }
    }

    /**
     * 执行一次宿主交给插件的调用或投递：登记为在途调用（停止时据此等待与取消，回调时据此核对租户），开始停止之后到达的以 {@code unavailable} 结束。
     */
    <T> T call(PluginProto.InvocationMetadata metadata, Function<Context, T> body, Supplier<T> unavailable) {
        return withScope(metadata, scope -> {
            Context context = scope.context().withValue(Host.KEY, host);
            Optional<Calls.Call> call = calls.enter(context.tenant(), scope::cancel);
            if (call.isEmpty()) {
                return unavailable.get();
            }
            try {
                return body.apply(context);
            } finally {
                call.get().close();
            }
        });
    }

    /** 错误的日志字段：码、消息键与细节，不渲染文案。 */
    static Map<String, Object> describe(Error error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", error.code());
        error.messageKey().ifPresent(key -> fields.put("messageKey", key));
        error.text().ifPresent(text -> fields.put("text", text));
        if (!error.details().isEmpty()) {
            fields.put("details", error.details().toString());
        }
        return fields;
    }

    // ---- 启动前的核对 ----

    /**
     * 核对清单与声明（{@code handshake.md} §1 末段）：{@code kind} 是 {@code plugin}、{@code isolation} 是 {@code out-of-process}、没有
     * {@code contributes.pipeline}、{@code primitives} 等于 SDK 支持的原语层大版本、声明的操作都在 {@code contributes.operations} 里
     * （登记表本身不收重复的 ID）、订阅的主题合文法（{@link Subscription} 构造时核对）且（主题，类型）不重复、消息目录含 {@code en-US}
     * （{@link PluginCatalog} 构造时核对）。健康项由回调在运行时给出，不在清单里的丢掉并告警。另核对宿主在发现时要求的 {@code entry}。
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

    /** 读清单原文，去掉开头的 UTF-8 BOM：握手时原样交给宿主（{@code handshake.md} §3 第 3 条）。 */
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
        fields.put("state", lifecycle.state().wireName());
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
