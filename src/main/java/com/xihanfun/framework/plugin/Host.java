package com.xihanfun.framework.plugin;

import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.ContextKey;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.invocation.Registration;
import com.xihanfun.framework.operation.OperationErrors;
import com.xihanfun.framework.operation.OperationMessages;
import com.xihanfun.framework.operation.OperationRequest;
import com.xihanfun.framework.operation.OperationResponse;
import com.xihanfun.framework.plugin.v1.HostGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.Error;
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
 * 上下文的租户是宿主此刻交给插件、还在处理的某个调用或投递的租户（ADR-0195）。每次回调都带会话令牌，元数据取自所在的调用上下文
 * （追踪、截止时间、已登记的 Baggage），gRPC 调用的截止时间与之相同，取消上下文即取消回调。线程安全；连接在第一次回调时建立。
 */
public final class Host {
    /** 运行时在每次调用与投递的上下文里放入本客户端。 */
    static final ContextKey<Host> KEY = ContextKey.named("xihan.plugin.host");

    /** 回调 {@code Host.Invoke} 需要的权限。 */
    public static final String INVOKE_PERMISSION = "capability:kernel.pipeline";

    /** 回调 {@code Host.Publish} 需要的权限。 */
    public static final String PUBLISH_PERMISSION = "capability:messaging.queue";

    /** {@code Host.Invoke}：状态映射里的 RPC 名（{@code callback-status.json} 的 {@code rpc}）。 */
    static final String RPC_INVOKE = "invoke";

    /** {@code Host.Publish}：状态映射里的 RPC 名，也是它失败时的 {@code phase}。 */
    static final String RPC_PUBLISH = "publish";

    /** 没有可用的回调端点时 {@code reason} 的固定文本（{@code plugin-messages.json}）。 */
    static final String NO_ENDPOINT = "no supported callback endpoint";

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
     * @param context 调用上下文
     * @param request XGI 请求
     * @return 宿主的结果
     */
    public OperationResponse invoke(Context context, OperationRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Optional<Error> refused = refuse(context, INVOKE_PERMISSION);
        if (refused.isPresent()) {
            return OperationResponse.failed(refused.get());
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
     * <p>空集合不发给宿主。权限与上下文租户的本地核对是整批的结果：不通过时每一项都是那个错误，一项也不发。信封的 {@code xihantenant}
     * 不在本地核对，由宿主逐项判定（宿主在它之前还核对发布器与主题，本地先判它会改变错误的先后）。整批的传输失败使每一项都得到同一个错误；
     * 宿主返回的结果数与信封数不同时每一项都是 {@code PLUGINS_UNEXPECTED_001}。
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
        Optional<Error> refused = refuse(context, PUBLISH_PERMISSION);
        if (refused.isPresent()) {
            return every(items.size(), Result.fail(refused.get()));
        }
        PluginProto.PublishRequest.Builder request = PluginProto.PublishRequest.newBuilder().setMetadata(Wire.metadata(context));
        items.forEach(envelope -> request.addEnvelopes(Wire.envelope(envelope)));
        Result<PluginProto.PublishResponse> response = call(context, RPC_PUBLISH, RPC_PUBLISH, stub -> stub.publish(request.build()));
        if (response.isFailed()) {
            return every(items.size(), Result.fail(response.error().orElseThrow()));
        }
        List<PluginProto.Status> statuses = response.orElseThrow().getResultsList();
        if (statuses.size() != items.size()) {
            return every(items.size(), Result.fail(wire.failed(RPC_PUBLISH,
                    "the host returned " + statuses.size() + " results for " + items.size() + " envelopes")));
        }
        List<Result<Unit>> results = new ArrayList<>(items.size());
        statuses.forEach(status -> results.add(wire.readStatus(status, RPC_PUBLISH)));
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

    private static List<Result<Unit>> every(int count, Result<Unit> result) {
        return new ArrayList<>(Collections.nCopies(count, result));
    }

    /**
     * 发出之前的本地核对（{@code invocation.md} §5）：先看有没有可用的回调端点，再按宿主的顺序核对权限与租户；不通过即不发请求。
     */
    private Optional<Error> refuse(Context context, String permission) {
        if (endpoint == null) {
            return Optional.of(wire.hostUnavailable(NO_ENDPOINT));
        }
        // 权限要在清单里声明并且已被授予：宿主授予而清单没有声明的权限不放行回调
        if (!declared.contains(permission) || !granted.contains(permission)) {
            return Optional.of(Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                    Map.of("id", wire.pluginId(), "permission", permission)));
        }
        Optional<String> tenant = context.tenant();
        if (tenant.isPresent() && !calls.handed(tenant.get())) {
            return Optional.of(Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED,
                    Map.of("id", wire.pluginId(), "tenant", tenant.get())));
        }
        return Optional.empty();
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
        // 回调在 gRPC 自己的可取消上下文里执行：调用上下文取消时它随之取消，正在进行的回调以 Cancelled 结束
        io.grpc.Context.CancellableContext scope = io.grpc.Context.current().withCancellation();
        Registration registration = context.onCancelled(() -> scope.cancel(null));
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
            return Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                    Map.of("id", wire.pluginId(), "permission", invoke ? INVOKE_PERMISSION : PUBLISH_PERMISSION));
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
            return Error.notFound(OperationErrors.NOT_FOUND, OperationMessages.UNKNOWN, Map.of("id", operation));
        }
        if (code == Status.Code.INVALID_ARGUMENT.value() && invoke) {
            return Error.validation(OperationErrors.INVALID_PAYLOAD, OperationMessages.INVALID_PAYLOAD, Map.of("id", operation, "reason", message));
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
