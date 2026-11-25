package com.xihanfun.framework.plugin;

import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.ContextKey;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.invocation.Registration;
import com.xihanfun.framework.operation.OperationErrors;
import com.xihanfun.framework.operation.OperationRequest;
import com.xihanfun.framework.operation.OperationResponse;
import com.xihanfun.framework.plugin.v1.HostGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宿主回调服务的客户端（{@code invocation.md} §5）：经宿主的完整管道调用操作（{@code Host.Invoke}，需要权限
 * {@code capability:kernel.pipeline}），经宿主的消息代理批量发布（{@code Host.Publish}，需要权限 {@code capability:messaging.queue}）。
 *
 * <p>回调端点在启动时按 {@code XIHAN_PLUGIN_HOST_ADDRESS} 的顺序取第一个本 SDK 能连的（回环 TCP，Linux 上另有 Unix 域套接字），之后不换；
 * 没有能连的端点时每次回调都以 {@code PLUGINS_UNAVAILABLE_001} + {@value PluginMessages#HOST_UNAVAILABLE} 失败、不发请求（{@code handshake.md} §4）。
 * 发出之前在本地按宿主的顺序核对，与宿主给出同样的错误：所需权限既在清单 {@code permissions} 里、也在 {@code Configure} 授予的权限里；
 * 上下文的租户是宿主此刻交给插件、还在处理的某个调用或投递的租户（ADR-0195）；然后，上下文的截止时间已过或已被取消时也不发请求；
 * {@code Host.Invoke} 最后还核对以 JSON 文本给出的载荷编得成 UTF-8（ADR-0312），{@code Host.Publish} 最后逐项核对以文本给出的 {@code data}
 * 编得成 UTF-8（ADR-0315）。每次回调都带会话令牌，元数据取自所在的调用上下文
 * （追踪、截止时间、已登记的 Baggage），gRPC 调用的截止时间与之相同，取消上下文即取消回调。线程安全；连接在第一次回调时建立。
 */
public final class Host {
    /** 运行时在每次调用与投递的上下文里放入本客户端。 */
    static final ContextKey<Host> KEY = ContextKey.named("xihan.plugin.host");

    /** 回调 {@code Host.Invoke} 需要的权限。 */
    public static final String PERMISSION_INVOKE = "capability:kernel.pipeline";

    /** 回调 {@code Host.Publish} 需要的权限。 */
    public static final String PERMISSION_PUBLISH = "capability:messaging.queue";

    /**
     * 回调 {@code Host.Invoke} 需要的权限。
     *
     * @deprecated 用 {@link #PERMISSION_INVOKE}：与其他语言的 SDK 同名，取值相同。
     */
    @Deprecated
    public static final String INVOKE_PERMISSION = PERMISSION_INVOKE;

    /**
     * 回调 {@code Host.Publish} 需要的权限。
     *
     * @deprecated 用 {@link #PERMISSION_PUBLISH}：与其他语言的 SDK 同名，取值相同。
     */
    @Deprecated
    public static final String PUBLISH_PERMISSION = PERMISSION_PUBLISH;

    /** {@code Host.Invoke}：状态映射里的 RPC 名（{@code callback-status.json} 的 {@code rpc}）。 */
    static final String RPC_INVOKE = "invoke";

    /** {@code Host.Publish}：状态映射里的 RPC 名，也是它失败时的 {@code phase}。 */
    static final String RPC_PUBLISH = "publish";

    /** 没有可用的回调端点时 {@code reason} 的固定文本（{@code plugin-messages.json}）。 */
    static final String NO_ENDPOINT = "no supported callback endpoint";

    /** 信封不合法或无法转换的错误码（{@code conventions/error-codes.md}），与宿主转换不了信封时相同。 */
    static final String ENVELOPE_INVALID = "MESSAGING_VALIDATION_002";

    /** 信封不合法的消息键（RFC-0009），参数 {@code reason}。 */
    static final String ENVELOPE_INVALID_KEY = "messaging.envelope.invalid";

    /** 以文本设置的 {@code data} 编不成 UTF-8 时 {@code reason} 的固定文本（{@code invocation.md} §5，ADR-0315）。 */
    static final String UNENCODABLE_DATA = "the data has a lone surrogate and cannot be encoded as UTF-8";

    /** gRPC 状态码 0–16 的 PascalCase 名字（{@code invocation.md} §3），下标即状态码；grpc-java 的枚举名是大写下划线，不能直接用。 */
    private static final List<String> STATUS_NAMES = List.of("OK", "Cancelled", "Unknown", "InvalidArgument", "DeadlineExceeded", "NotFound",
            "AlreadyExists", "PermissionDenied", "ResourceExhausted", "FailedPrecondition", "Aborted", "OutOfRange", "Unimplemented",
            "Internal", "Unavailable", "DataLoss", "Unauthenticated");

    /** 启动时选定的回调端点；没有能连的为 {@code null}。 */
    private final Endpoint endpoint;
    private final String session;
    private final Wire wire;
    private final Calls calls;
    private final Executor executor;

    /** 清单 {@code permissions}：插件权限的唯一事实源（RFC-0017）。 */
    private final Set<String> declared;

    /** {@code Configure} 授予的权限；配置之前为空，任何回调都过不了权限核对。 */
    private volatile Set<String> granted = Set.of();

    /** 只保护通道的惰性创建与关闭。 */
    private final ReentrantLock lock = new ReentrantLock();
    private Transport.Dialed dialed;
    private boolean closed;

    Host(Optional<Endpoint> endpoint, String session, Wire wire, Calls calls, Executor executor, List<String> declaredPermissions) {
        this.endpoint = endpoint.orElse(null);
        this.session = session;
        this.wire = wire;
        this.calls = calls;
        this.executor = executor;
        this.declared = Set.copyOf(declaredPermissions);
    }

    /**
     * 从调用上下文取宿主客户端：运行时在操作调用与事件投递的上下文里放入它。
     *
     * @param context 调用上下文
     * @return 客户端；上下文不是运行时交给处理器的时为空
     */
    public static Optional<Host> of(Context context) {
        return Objects.requireNonNull(context, "context").findValue(KEY);
    }

    /** 记下 {@code Configure} 授予的权限（{@code lifecycle.md} §7.2：回调前的权限核对以它为准）。 */
    void grant(List<String> permissions) {
        granted = Set.copyOf(permissions);
    }

    /** 启动时选定的回调端点。 */
    Optional<Endpoint> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    /**
     * 经宿主的完整管道调用一个操作。
     *
     * @param context 调用上下文
     * @param operation 操作 ID
     * @param payload 请求类型的 UTF-8 JSON；空等价于 {@code {}}
     * @return 宿主的结果；回调失败时按 {@code invocation.md} §5 的映射给出错误（如宿主不可达为 {@code PLUGINS_UNAVAILABLE_001}）
     */
    public OperationResponse invoke(Context context, String operation, byte[] payload) {
        return invoke(context, OperationRequest.of(operation, payload));
    }

    /**
     * 经宿主的完整管道调用一个操作。
     *
     * <p>以 JSON 文本创建的请求（{@link OperationRequest#of(String, String)}）文本编不成 UTF-8 时（孤立的代理项，
     * {@link OperationRequest#hasUnencodableText()}）不发请求，结果为 {@code KERNEL_VALIDATION_001} + {@code kernel.operation.invalid_payload}
     * （{@code id} 为操作 ID，{@code reason} 为 {@code $}），与宿主读到不是 JSON 的载荷相同；这一项排在端点、权限、租户、截止时间与取消的核对之后
     * （{@code invocation.md} §5，ADR-0312）。以字节给出的载荷原样发出，由宿主核对。
     *
     * @param context 调用上下文
     * @param request XGI 请求
     * @return 宿主的结果
     */
    public OperationResponse invoke(Context context, OperationRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Optional<Error> refused = refuse(context, RPC_INVOKE, request.id(), PERMISSION_INVOKE);
        if (refused.isPresent()) {
            return OperationResponse.failed(refused.get());
        }
        // 宿主在管道的 Validate 阶段才解码载荷：权限、租户与截止时间的错误先于它，所以排在 refuse 之后
        if (request.hasUnencodableText()) {
            return OperationResponse.failed(OperationErrors.invalidPayload(request.id(), JsonText.ROOT));
        }
        PluginProto.InvokeRequest message = PluginProto.InvokeRequest.newBuilder()
                .setOperation(request.id())
                .setPayload(ByteString.copyFrom(request.payload()))
                .setMetadata(Wire.metadata(context))
                .build();
        Result<PluginProto.InvokeResponse> response = call(context, RPC_INVOKE, request.id(), stub -> stub.invoke(message));
        if (response.isFailed()) {
            return OperationResponse.failed(response.error().orElseThrow());
        }
        PluginProto.InvokeResponse invoked = response.orElseThrow();
        Result<Unit> outcome = wire.readStatus(invoked.getStatus(), request.id());
        if (outcome.isFailed()) {
            return OperationResponse.failed(outcome.error().orElseThrow());
        }
        // 成功的 XGI 响应必须带载荷；宿主给空载荷时按 {} 处理（与请求的空载荷同义）
        byte[] payload = invoked.getPayload().isEmpty() ? new byte[] {'{', '}'} : invoked.getPayload().toByteArray();
        return OperationResponse.success(payload);
    }

    /**
     * 经宿主的消息代理发布一批消息，逐项结果与入参顺序一一对应（批量优先，{@code conventions/batch-first.md}）。
     *
     * <p>空集合不发给宿主。端点、权限、上下文租户与截止时间的本地核对是整批的结果：不通过时每一项都是那个错误，一项也不发。之后逐项核对以文本
     * 设置的 {@code data}（{@link Envelope#hasUnencodableData()}，ADR-0315）：编不成 UTF-8 的那一项不发出，结果是 {@code MESSAGING_VALIDATION_002}
     * + {@code messaging.envelope.invalid}（{@code reason} 为 {@code the data has a lone surrogate and cannot be encoded as UTF-8}），
     * 其余各项照常组成一次请求，结果放回各自原来的位置；每一项都编不成时不发请求（{@code invocation.md} §5）。信封的 {@code xihantenant} 不在本地核对，由宿主逐项判定（宿主在它之前还核对发布器
     * 与主题，本地先判它会改变错误的先后）。整批的传输失败使发出的每一项都得到同一个错误；宿主返回的结果数与发出的信封数不同时发出的每一项都是
     * {@code PLUGINS_UNEXPECTED_001}。
     *
     * @param context 调用上下文；宿主以它的元数据建调用上下文，补齐信封缺的扩展属性
     * @param envelopes 信封；每个信封的主题必须非空，否则宿主对该项返回 {@code MESSAGING_VALIDATION_002}
     * @return 逐项结果
     */
    public List<Result<Unit>> publish(Context context, List<Envelope> envelopes) {
        Objects.requireNonNull(context, "context");
        List<Envelope> items = List.copyOf(Objects.requireNonNull(envelopes, "envelopes"));
        if (items.isEmpty()) {
            return new ArrayList<>();
        }
        Optional<Error> refused = refuse(context, RPC_PUBLISH, RPC_PUBLISH, PERMISSION_PUBLISH);
        if (refused.isPresent()) {
            return every(items.size(), Result.fail(refused.get()));
        }
        // 宿主转换信封是它逐项核对的最后一步，所以排在整批的核对之后：编不成的那一项先占好自己的位置，其余的按原来的先后发出
        List<Result<Unit>> results = new ArrayList<>(Collections.nCopies(items.size(), null));
        List<Integer> sent = new ArrayList<>(items.size());
        PluginProto.PublishRequest.Builder request = PluginProto.PublishRequest.newBuilder().setMetadata(Wire.metadata(context));
        for (int index = 0; index < items.size(); index++) {
            Envelope envelope = items.get(index);
            if (envelope.hasUnencodableData()) {
                results.set(index, Result.fail(unencodableData()));
            } else {
                sent.add(index);
                request.addEnvelopes(Wire.envelope(envelope));
            }
        }
        if (sent.isEmpty()) {
            return results;
        }
        Result<PluginProto.PublishResponse> response = call(context, RPC_PUBLISH, RPC_PUBLISH, stub -> stub.publish(request.build()));
        if (response.isFailed()) {
            Result<Unit> failed = Result.fail(response.error().orElseThrow());
            sent.forEach(index -> results.set(index, failed));
            return results;
        }
        // 宿主只看到发出的信封，结果数按发出的核对
        List<PluginProto.Status> statuses = response.orElseThrow().getResultsList();
        if (statuses.size() != sent.size()) {
            Result<Unit> mismatch = Result.fail(wire.failed(RPC_PUBLISH,
                    "the host returned " + statuses.size() + " results for " + sent.size() + " envelopes"));
            sent.forEach(index -> results.set(index, mismatch));
            return results;
        }
        for (int position = 0; position < sent.size(); position++) {
            results.set(sent.get(position), wire.readStatus(statuses.get(position), RPC_PUBLISH));
        }
        return results;
    }

    /** 断开与宿主的连接；之后的回调以 {@code PLUGINS_UNAVAILABLE_001} 失败。 */
    void close() {
        lock.lock();
        try {
            closed = true;
            if (dialed != null) {
                dialed.channel().shutdownNow();
                dialed.cleanup().run();
                dialed = null;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 以文本设置的 {@code data} 编不成 UTF-8 的那一项的错误：与宿主转换不了信封时同一个码与键（{@code invocation.md} §5，ADR-0315）。 */
    private static Error unencodableData() {
        return Error.validation(ENVELOPE_INVALID, ENVELOPE_INVALID_KEY, Map.of("reason", UNENCODABLE_DATA));
    }

    private static List<Result<Unit>> every(int count, Result<Unit> result) {
        return new ArrayList<>(Collections.nCopies(count, result));
    }

    /**
     * 发出之前的本地核对（{@code invocation.md} §5），依次：有没有可用的回调端点；按宿主的顺序核对权限与租户；最后回调上下文的截止时间已过
     * 或已被取消时也不发请求，结果与映射表相应的一行相同（截止时间已过按 {@code DeadlineExceeded}，已被取消按取消信号已触发的
     * {@code Cancelled}）。不通过即不发请求。
     */
    private Optional<Error> refuse(Context context, String rpc, String operation, String permission) {
        if (endpoint == null) {
            return Optional.of(wire.hostUnavailable(NO_ENDPOINT));
        }
        // 权限要在清单里声明并且已被授予：宿主授予而清单没有声明的权限不放行回调
        if (!declared.contains(permission) || !granted.contains(permission)) {
            return Optional.of(PluginErrors.callbackDenied(wire.pluginId(), permission));
        }
        Optional<String> tenant = context.tenant();
        if (tenant.isPresent() && !calls.handed(tenant.get())) {
            return Optional.of(Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED,
                    Map.of("id", wire.pluginId(), "tenant", tenant.get())));
        }
        // 截止时间先于取消：截止令牌触发时上下文同样算已取消，而这一行按 DeadlineExceeded。截止时间已过先看截止令牌：它由定时器按
        // nanoTime 触发，墙上时钟（Instant.now()）可能还差一点才到截止时间，只看时钟会把截止时间报成取消；令牌还没触发而时钟已过的同样算
        if (deadlineFired(context) || context.isExpired(Instant.now())) {
            return Optional.of(mapStatus(wire, rpc, Status.Code.DEADLINE_EXCEEDED.value(), "", true, operation));
        }
        if (context.isCancelled()) {
            return Optional.of(mapStatus(wire, rpc, Status.Code.CANCELLED.value(), "", true, operation));
        }
        return Optional.empty();
    }

    /** 上下文的截止令牌已触发：{@link Context#cancellationError()} 在截止令牌触发时总是报超时，不论外部令牌，也不读时钟。 */
    private static boolean deadlineFired(Context context) {
        return context.cancellationError().map(Error::code).filter(InvocationErrors.DEADLINE_EXCEEDED::equals).isPresent();
    }

    /** 一次阻塞的 gRPC 调用。 */
    private interface Rpc<T> {
        T invoke(HostGrpc.HostBlockingStub stub);
    }

    /**
     * 执行一次回调：带会话令牌与上下文的截止时间，上下文取消时取消回调；失败按 {@code invocation.md} §5 的表映射。
     */
    private <T> Result<T> call(Context context, String rpc, String operation, Rpc<T> invocation) {
        ManagedChannel channel;
        try {
            Optional<ManagedChannel> opened = channel();
            if (opened.isEmpty()) {
                return Result.fail(mapStatus(wire, rpc, Status.Code.UNAVAILABLE.value(), "the plugin runtime has stopped", context.isCancelled(),
                        operation));
            }
            channel = opened.get();
        } catch (RuntimeException failed) {
            // 建不起通道与连接失败一样按 Unavailable（invocation.md §5）
            return Result.fail(mapStatus(wire, rpc, Status.Code.UNAVAILABLE.value(), Objects.toString(failed.getMessage(), ""),
                    context.isCancelled(), operation));
        }
        Metadata headers = new Metadata();
        headers.put(SessionGuard.KEY, session);
        HostGrpc.HostBlockingStub base = HostGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        Optional<Duration> remaining = context.remainingTime(Instant.now());
        HostGrpc.HostBlockingStub stub = remaining.map(duration -> base.withDeadlineAfter(Math.max(1, nanos(duration)), TimeUnit.NANOSECONDS))
                .orElse(base);
        // 回调在 gRPC 自己的可取消上下文里执行：调用方取消时它随之取消，正在进行的回调以 Cancelled 结束。截止时间到了不在这里取消：
        // gRPC 调用带着同一个截止时间，由 grpc-java 在本端以 DeadlineExceeded 结束，结果只按映射表，不另按令牌改写（invocation.md §5）
        io.grpc.Context.CancellableContext scope = io.grpc.Context.current().withCancellation();
        Registration registration = context.onCancelled(() -> {
            if (context.cancellationError().map(Error::code).filter(InvocationErrors.CANCELLED::equals).isPresent()) {
                scope.cancel(null);
            }
        });
        try {
            Callable<T> task = () -> invocation.invoke(stub);
            return Result.ok(scope.call(task));
        } catch (Exception failed) {
            // 不是 gRPC 状态的失败按 Unknown 处理；连接失败在 grpc-java 里就是 Unavailable
            Status status = Status.fromThrowable(failed);
            return Result.fail(mapStatus(wire, rpc, status.getCode().value(), Objects.toString(status.getDescription(), ""), context.isCancelled(),
                    operation));
        } finally {
            registration.close();
            scope.cancel(null);
        }
    }

    /** 时长换成纳秒，远到表示不了（约 292 年）时取最大值：gRPC 的截止时间同样饱和。 */
    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooFar) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 回调的 gRPC 失败映射成错误（{@code invocation.md} §5 的表，向量 {@code callback-status.json}）。
     *
     * @param rpc {@value #RPC_INVOKE} 或 {@value #RPC_PUBLISH}
     * @param code gRPC 状态码的数值；连接失败按 {@code Unavailable}
     * @param message 状态的消息，原样写进 {@code reason}，可以为空
     * @param contextCancelled 回调所在的调用上下文的取消信号是否已触发
     * @param operation {@code Host.Invoke} 的操作 ID；{@code Host.Publish} 时不用
     */
    static Error mapStatus(Wire wire, String rpc, int code, String message, boolean contextCancelled, String operation) {
        boolean invoke = RPC_INVOKE.equals(rpc);
        String reason = statusName(code) + ": " + message;
        if (code == Status.Code.UNAUTHENTICATED.value()) {
            return Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.SESSION_INVALID);
        }
        if (code == Status.Code.PERMISSION_DENIED.value()) {
            return PluginErrors.callbackDenied(wire.pluginId(), invoke ? PERMISSION_INVOKE : PERMISSION_PUBLISH);
        }
        if (code == Status.Code.DEADLINE_EXCEEDED.value()) {
            return InvocationErrors.deadlineExceeded();
        }
        // Cancelled 只在调用方确实取消时算取消：超时的归因交给插件自己管道里的截止时间步骤（RFC-0003）；
        // 调用方没有取消的 Cancelled 是连接被宿主或网络中断，与 Unavailable 同样处理
        if (code == Status.Code.CANCELLED.value() && contextCancelled) {
            return InvocationErrors.cancelled();
        }
        if (code == Status.Code.CANCELLED.value() || code == Status.Code.UNAVAILABLE.value()) {
            return wire.hostUnavailable(reason);
        }
        // Unimplemented 与 InvalidArgument 只对 Host.Invoke 有操作层面的含义；Host.Publish 的这两种状态落到最后一项
        if (code == Status.Code.UNIMPLEMENTED.value() && invoke) {
            return OperationErrors.notFound(operation);
        }
        if (code == Status.Code.INVALID_ARGUMENT.value() && invoke) {
            return OperationErrors.invalidPayload(operation, message);
        }
        return wire.failed(invoke ? operation : RPC_PUBLISH, reason);
    }

    /**
     * gRPC 状态码的 PascalCase 名字（{@code invocation.md} §3，如 {@code InvalidArgument}）；0–16 之外的写成十进制数。
     */
    static String statusName(int code) {
        return code >= 0 && code < STATUS_NAMES.size() ? STATUS_NAMES.get(code) : Integer.toString(code);
    }

    /** 第一次回调时建立通道；已经关闭时为空。 */
    private Optional<ManagedChannel> channel() {
        lock.lock();
        try {
            if (closed) {
                return Optional.empty();
            }
            if (dialed == null) {
                dialed = Transport.dial(endpoint, executor);
            }
            return Optional.of(dialed.channel());
        } finally {
            lock.unlock();
        }
    }
}
