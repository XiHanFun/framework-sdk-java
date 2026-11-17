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
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宿主回调服务的客户端（{@code invocation.md} §5）：经宿主的完整管道调用操作（{@code Host.Invoke}，需要权限
 * {@code capability:kernel.pipeline}），经宿主的消息代理批量发布（{@code Host.Publish}，需要权限 {@code capability:messaging.queue}）。
 *
 * <p>每次回调都带会话令牌；宿主以插件身份执行。上下文的追踪、截止时间与已登记的 Baggage 随回调传过去，取消上下文即取消回调。
 * 租户只能是宿主随调用交给插件、此刻还在处理的调用的租户（ADR-0195）：带了别的租户时本地就以 {@code PLUGINS_DENIED_002} 拒绝，不发给宿主。
 * 线程安全；连接在第一次回调时建立。
 */
public final class Host {
    /** 运行时在每次调用与投递的上下文里放入本客户端。 */
    static final ContextKey<Host> KEY = ContextKey.named("xihan.plugin.host");

    /** 回调 {@code Host.Invoke} 需要的权限。 */
    public static final String INVOKE_PERMISSION = "capability:kernel.pipeline";

    /** 回调 {@code Host.Publish} 需要的权限。 */
    public static final String PUBLISH_PERMISSION = "capability:messaging.queue";

    private final Endpoint endpoint;
    private final String session;
    private final Wire wire;
    private final Calls calls;
    private final Executor executor;

    /** 只保护通道的惰性创建与关闭。 */
    private final ReentrantLock lock = new ReentrantLock();
    private Transport.Dialed dialed;
    private String unsupported;
    private boolean closed;

    Host(Endpoint endpoint, String session, Wire wire, Calls calls, Executor executor) {
        this.endpoint = endpoint;
        this.session = session;
        this.wire = wire;
        this.calls = calls;
        this.executor = executor;
    }

    /**
     * 从调用上下文取宿主客户端：运行时在操作调用与事件投递的上下文里放入它。
     *
     * @param context 调用上下文
     * @return 客户端；宿主没有给出回调端点，或上下文不是运行时交给处理器的时为空
     */
    public static Optional<Host> of(Context context) {
        return Objects.requireNonNull(context, "context").findValue(KEY);
    }

    /**
     * 经宿主的完整管道调用一个操作。
     *
     * @param context 调用上下文
     * @param operation 操作 ID
     * @param payload 请求类型的 UTF-8 JSON；空等价于 {@code {}}
     * @return 宿主的结果；回调失败时按 {@code invocation.md} §3 的映射给出错误（如宿主不可达为 {@code PLUGINS_UNAVAILABLE_001}）
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
        Optional<Error> refused = refuseTenant(context.tenant());
        if (refused.isPresent()) {
            return OperationResponse.failed(refused.get());
        }
        PluginProto.InvokeRequest message = PluginProto.InvokeRequest.newBuilder()
                .setOperation(request.id())
                .setPayload(ByteString.copyFrom(request.payload()))
                .setMetadata(Wire.metadata(context))
                .build();
        Result<PluginProto.InvokeResponse> response = call(context, Optional.of(request.id()), INVOKE_PERMISSION,
                stub -> stub.invoke(message));
        if (response.isFailed()) {
            return OperationResponse.failed(response.error().orElseThrow());
        }
        PluginProto.InvokeResponse invoked = response.orElseThrow();
        Result<Unit> outcome = wire.fromStatus(invoked.getStatus(), "invoke");
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
     * <p>空集合不发给宿主。带了宿主没有交给插件的租户（{@code xihantenant} 扩展，或上下文的租户）的那一项本地就是
     * {@code PLUGINS_DENIED_002}，其余照常发布；整批的传输失败使每一项都得到同一个错误。
     *
     * @param context 调用上下文
     * @param envelopes 信封；每个信封的主题必须非空，否则宿主对该项返回 {@code MESSAGING_VALIDATION_002}
     * @return 逐项结果
     */
    public List<Result<Unit>> publish(Context context, List<Envelope> envelopes) {
        Objects.requireNonNull(context, "context");
        List<Envelope> items = List.copyOf(Objects.requireNonNull(envelopes, "envelopes"));
        List<Result<Unit>> results = new ArrayList<>(items.size());
        if (items.isEmpty()) {
            return results;
        }
        Optional<Error> refusedContext = refuseTenant(context.tenant());
        PluginProto.PublishRequest.Builder request = PluginProto.PublishRequest.newBuilder().setMetadata(Wire.metadata(context));
        // 先按租户逐项判定：越界的项不发，记下其余项在请求里的位置，回来后按原顺序填结果
        List<Integer> sent = new ArrayList<>();
        for (Envelope envelope : items) {
            Optional<Error> refused = refusedContext.isPresent() ? refusedContext : refuseTenant(envelope.tenant());
            if (refused.isPresent()) {
                results.add(Result.fail(refused.get()));
            } else {
                results.add(null);
                sent.add(results.size() - 1);
                request.addEnvelopes(Wire.envelope(envelope));
            }
        }
        if (sent.isEmpty()) {
            return results;
        }
        Result<PluginProto.PublishResponse> response = call(context, Optional.empty(), PUBLISH_PERMISSION,
                stub -> stub.publish(request.build()));
        for (int index = 0; index < sent.size(); index++) {
            int position = sent.get(index);
            if (response.isFailed()) {
                results.set(position, Result.fail(response.error().orElseThrow()));
            } else if (index < response.orElseThrow().getResultsCount()) {
                results.set(position, wire.fromStatus(response.orElseThrow().getResults(index), "publish"));
            } else {
                results.set(position, Result.fail(wire.failed("publish", "the host returned fewer results than envelopes")));
            }
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

    /**
     * 租户只能是宿主此刻交给插件处理的某个调用的租户；不带租户的回调是无租户的调用（{@code invocation.md} §5）。
     */
    private Optional<Error> refuseTenant(Optional<String> tenant) {
        if (tenant.isEmpty() || calls.handed(tenant.get())) {
            return Optional.empty();
        }
        return Optional.of(Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED,
                Map.of("id", wire.pluginId(), "tenant", tenant.get())));
    }

    /** 一次阻塞的 gRPC 调用。 */
    private interface Rpc<T> {
        T invoke(HostGrpc.HostBlockingStub stub);
    }

    /**
     * 执行一次回调：带会话令牌与上下文的截止时间，上下文取消时取消回调；传输失败按 {@code invocation.md} §3 映射。
     */
    private <T> Result<T> call(Context context, Optional<String> operation, String permission, Rpc<T> rpc) {
        Optional<ManagedChannel> channel = channel();
        if (channel.isEmpty()) {
            return Result.fail(hostUnavailable(unsupportedReason()));
        }
        Metadata headers = new Metadata();
        headers.put(SessionGuard.KEY, session);
        HostGrpc.HostBlockingStub base = HostGrpc.newBlockingStub(channel.get())
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        Optional<Duration> remaining = context.remainingTime(Instant.now());
        HostGrpc.HostBlockingStub stub = remaining
                .map(duration -> base.withDeadlineAfter(Math.max(1, duration.toNanos()), TimeUnit.NANOSECONDS))
                .orElse(base);
        // 回调在 gRPC 自己的可取消上下文里执行：调用上下文取消时它随之取消，正在进行的回调以 Cancelled 结束
        io.grpc.Context.CancellableContext scope = io.grpc.Context.current().withCancellation();
        Registration registration = context.onCancelled(() -> scope.cancel(null));
        try {
            Callable<T> task = () -> rpc.invoke(stub);
            return Result.ok(scope.call(task));
        } catch (StatusRuntimeException failed) {
            return Result.fail(map(failed, context, operation, permission));
        } catch (Exception failed) {
            // 不是 gRPC 状态的失败（编解码、执行器已关闭）：照未预期的回调失败处理，异常类型进 reason
            return Result.fail(wire.failed("callback", failed.getClass().getName()));
        } finally {
            registration.close();
            scope.cancel(null);
        }
    }

    /**
     * 把 gRPC 状态映射为错误（{@code invocation.md} §3 的映射表，从插件一侧看宿主）。
     */
    private Error map(StatusRuntimeException failed, Context context, Optional<String> operation, String permission) {
        io.grpc.Status status = failed.getStatus();
        String description = status.getDescription() == null ? status.getCode().name() : status.getDescription();
        return switch (status.getCode()) {
            case UNAVAILABLE -> hostUnavailable(description);
            case DEADLINE_EXCEEDED -> InvocationErrors.deadlineExceeded();
            // 调用方的令牌触发了才是取消（截止令牌已触发即超时，RFC-0003 规则 1）；否则是连接被切断，按不可达
            case CANCELLED -> context.cancellation().orElseGet(() -> hostUnavailable(description));
            case UNAUTHENTICATED -> Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.SESSION_INVALID);
            case PERMISSION_DENIED -> Error.denied(PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                    Map.of("id", wire.pluginId(), "permission", permission));
            case UNIMPLEMENTED -> operation
                    .map(id -> Error.notFound(OperationErrors.NOT_FOUND, OperationMessages.UNKNOWN, Map.of("id", id)))
                    .orElseGet(() -> wire.failed("callback", status.getCode().name() + ": " + description));
            case INVALID_ARGUMENT -> operation
                    .map(id -> Error.validation(OperationErrors.INVALID_PAYLOAD, OperationMessages.INVALID_PAYLOAD,
                            Map.of("id", id, "reason", "$")))
                    .orElseGet(() -> wire.failed("callback", status.getCode().name() + ": " + description));
            default -> wire.failed("callback", status.getCode().name() + ": " + description);
        };
    }

    private Error hostUnavailable(String reason) {
        return Error.unavailable(PluginErrors.UNAVAILABLE, PluginMessages.HOST_UNAVAILABLE,
                Map.of("id", wire.pluginId(), "reason", reason));
    }

    private String unsupportedReason() {
        lock.lock();
        try {
            return closed ? "the plugin is stopping" : unsupported;
        } finally {
            lock.unlock();
        }
    }

    /** 第一次回调时建立通道；本平台连不了这种网络时记下原因，之后的回调都以它失败。 */
    private Optional<ManagedChannel> channel() {
        lock.lock();
        try {
            if (closed || unsupported != null) {
                return Optional.empty();
            }
            if (dialed == null) {
                try {
                    dialed = Transport.dial(endpoint, executor);
                } catch (UnsupportedOperationException | IllegalArgumentException cannot) {
                    unsupported = "'" + endpoint + "': " + cannot.getMessage();
                    return Optional.empty();
                }
            }
            return Optional.of(dialed.channel());
        } finally {
            lock.unlock();
        }
    }
}
