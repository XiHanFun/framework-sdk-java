package com.xihanfun.framework.plugin;

import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.operation.Expose;
import com.xihanfun.framework.operation.OperationDescriptor;
import com.xihanfun.framework.operation.OperationErrors;
import com.xihanfun.framework.operation.OperationRequest;
import com.xihanfun.framework.operation.OperationResponse;
import com.xihanfun.framework.plugin.v1.PluginGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.FrameworkInfo;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code xihan.plugin.v1.Plugin} 服务的实现（{@code handshake.md} §3、{@code lifecycle.md}、{@code invocation.md}）。
 *
 * <p>每个方法在虚拟线程上执行；会话令牌已由 {@link SessionGuard} 核对过。业务失败写进 {@code Status.error}，gRPC 状态只用于协议层的失败：
 * 次序不对是 {@code FailedPrecondition}，未声明的操作是 {@code Unimplemented}，帧序不对是 {@code InvalidArgument}。
 */
final class PluginService extends PluginGrpc.PluginImplBase {
    private final PluginRuntime runtime;

    PluginService(PluginRuntime runtime) {
        this.runtime = runtime;
    }

    // ---- 握手、配置与生命周期 ----

    @Override
    public void handshake(PluginProto.HandshakeRequest request, StreamObserver<PluginProto.HandshakeResponse> observer) {
        if (!runtime.lifecycle.is(LifecycleState.DECLARED, LifecycleState.FAILED)) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("The plugin has already completed its handshake.")
                    .asRuntimeException());
            return;
        }
        Optional<Integer> selected = PluginRuntime.negotiate(request.getProtocolVersionsList());
        if (selected.isEmpty()) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("No common protocol version: the host supports "
                    + request.getProtocolVersionsList() + ", the plugin supports [" + PluginRuntime.PROTOCOL_VERSION + "].")
                    .asRuntimeException());
            return;
        }
        PluginProto.HandshakeResponse.Builder response = PluginProto.HandshakeResponse.newBuilder()
                .setProtocolVersion(selected.get())
                .setPrimitives(runtime.manifest.primitives())
                .setManifest(runtime.manifestText)
                .setSdk(PluginProto.SdkInfo.newBuilder()
                        .setName(FrameworkInfo.SDK_NAME)
                        .setVersion(FrameworkInfo.SDK_VERSION)
                        .setLanguage(FrameworkInfo.SDK_LANGUAGE));
        for (OperationDescriptor descriptor : runtime.invoker.registry().list()) {
            response.addOperations(declaration(descriptor));
        }
        for (PluginOptions.SubscriptionHandler handler : runtime.options.subscriptions()) {
            Subscription subscription = handler.subscription();
            PluginProto.Subscription.Builder encoded = PluginProto.Subscription.newBuilder()
                    .setTopic(subscription.topic())
                    .setMode(subscription.mode().wireName());
            subscription.messageType().ifPresent(encoded::setMessageType);
            response.addSubscriptions(encoded);
        }
        for (PluginCatalog catalog : runtime.options.catalogs()) {
            PluginProto.MessageCatalog.Builder encoded = PluginProto.MessageCatalog.newBuilder().setName(catalog.name());
            catalog.locales().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> encoded.addLocales(
                    PluginProto.LocaleTemplates.newBuilder().setLocale(entry.getKey()).putAllTemplates(entry.getValue())));
            response.addCatalogs(encoded);
        }
        runtime.lifecycle.transition(LifecycleState.RESOLVED, null);
        runtime.logger.information("handshake completed", Map.of("protocolVersion", selected.get(), "host", request.getHost().getSdk(),
                "instance", request.getInstanceId()));
        observer.onNext(response.build());
        observer.onCompleted();
    }

    /** 操作声明：契约与 Schema（RFC-0011），本 SDK 没有流式操作。 */
    private static PluginProto.OperationDeclaration declaration(OperationDescriptor descriptor) {
        PluginProto.OperationDeclaration.Builder declaration = PluginProto.OperationDeclaration.newBuilder()
                .setId(descriptor.contract().id())
                .setStability(descriptor.contract().stability().wireName())
                .setStreaming(false);
        descriptor.contract().expose().stream().sorted(Comparator.naturalOrder()).map(Expose::wireName).forEach(declaration::addExpose);
        descriptor.contract().permission().ifPresent(declaration::setPermission);
        descriptor.contract().policy().ifPresent(declaration::setPolicy);
        descriptor.schema().ifPresent(schema -> declaration.setRequestSchema(schema.request()).setResponseSchema(schema.response()));
        return declaration.build();
    }

    @Override
    public void configure(PluginProto.ConfigureRequest request, StreamObserver<PluginProto.ConfigureResponse> observer) {
        if (!runtime.lifecycle.is(LifecycleState.RESOLVED)) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("Configure requires a completed handshake.").asRuntimeException());
            return;
        }
        List<ResolvedBinding> bindings = new ArrayList<>();
        for (PluginProto.Binding binding : request.getBindingsList()) {
            bindings.add(new ResolvedBinding(binding.getName(), binding.getType(), binding.getValue()));
        }
        Optional<Path> workDirectory = request.getWorkDirectory().isEmpty()
                ? Optional.empty()
                : Optional.of(Path.of(request.getWorkDirectory()));
        PluginConfiguration configuration = new PluginConfiguration(request.getConfiguration(), request.getGrantedPermissionsList(),
                bindings, workDirectory);
        Result<Unit> outcome = lifecycleCall(context -> runtime.options.hooks().configure(context, configuration), "configure");
        // 回调执行过就要在停止时给它释放资源的机会，失败的配置也可能已经占了资源
        runtime.configured.set(true);
        if (outcome.isOk()) {
            runtime.lifecycle.transition(LifecycleState.CONFIGURED, null);
        } else {
            fail("configure", outcome);
        }
        observer.onNext(PluginProto.ConfigureResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
    }

    @Override
    public void start(PluginProto.StartRequest request, StreamObserver<PluginProto.StartResponse> observer) {
        if (!runtime.lifecycle.is(LifecycleState.CONFIGURED)) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("Start requires a successful Configure.").asRuntimeException());
            return;
        }
        Result<Unit> outcome = lifecycleCall(context -> runtime.options.hooks().start(context), "start");
        if (outcome.isOk()) {
            // 没有自报健康的插件在 Start 后直接报 Ready（lifecycle.md §1）
            runtime.lifecycle.transition(LifecycleState.STARTED, null);
            runtime.lifecycle.transition(LifecycleState.READY, null);
            runtime.logger.information("started", runtime.summary());
        } else {
            fail("start", outcome);
        }
        observer.onNext(PluginProto.StartResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
    }

    @Override
    public void stop(PluginProto.StopRequest request, StreamObserver<PluginProto.StopResponse> observer) {
        Duration grace = request.getGraceSeconds() > 0 ? Duration.ofSeconds(request.getGraceSeconds()) : PluginRuntime.DEFAULT_STOP_GRACE;
        String reason = request.getReason().isEmpty() ? "shutdown" : request.getReason();
        Result<Unit> outcome = runtime.beginStop(reason, grace);
        if (outcome.isFailed()) {
            runtime.logger.warning("the stop hook failed", Map.of("code", outcome.error().orElseThrow().code()));
        }
        observer.onNext(PluginProto.StopResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
        // 应答送出之后再停服务端：优雅停止会等本次调用结束；这个线程退出后进程随 serve 返回而结束
        Thread.ofVirtual().name("xihan-plugin-stop").start(() -> runtime.finishStop(grace));
    }

    /** 配置或启动失败即进入 Failed，原因记进状态，健康应答里带上它。 */
    private void fail(String phase, Result<Unit> outcome) {
        Error error = outcome.error().orElseThrow();
        runtime.lifecycle.transition(LifecycleState.FAILED, error);
        runtime.logger.error(phase + " failed", null, Map.of("code", error.code(), "message", runtime.wire.text(error)));
    }

    /** 以 gRPC 调用的截止时间与取消为上下文执行一个生命周期回调。 */
    private Result<Unit> lifecycleCall(Function<Context, Result<Unit>> body, String phase) {
        return runtime.withContext(null, context -> runtime.hook(phase, () -> body.apply(context)));
    }

    // ---- 健康 ----

    @Override
    public void health(PluginProto.HealthRequest request, StreamObserver<PluginProto.HealthResponse> observer) {
        observer.onNext(runtime.wire.health(runtime.health()));
        observer.onCompleted();
    }

    /**
     * 先推当前状态，之后状态或健康项变化时各推一条（{@code lifecycle.md} §4）：状态迁移即时唤醒，回调报的健康每秒核对一次；
     * 开始停止时推最后一条并结束，长流不拖住服务端的优雅停止。
     */
    @Override
    public void watchHealth(PluginProto.HealthRequest request, StreamObserver<PluginProto.HealthResponse> observer) {
        ServerCallStreamObserver<PluginProto.HealthResponse> stream = (ServerCallStreamObserver<PluginProto.HealthResponse>) observer;
        io.grpc.Context transport = io.grpc.Context.current();
        // 宿主取消订阅时立即唤醒等待：取消回调在 gRPC 的取消线程上执行，不经本调用被占住的执行队列
        io.grpc.Context.CancellationListener wake = cancelled -> runtime.lifecycle.touch();
        transport.addListener(wake, Runnable::run);
        try {
            PluginHealth last = runtime.health();
            stream.onNext(runtime.wire.health(last));
            long seen = runtime.lifecycle.version();
            while (!stream.isCancelled() && !transport.isCancelled()) {
                boolean draining = runtime.calls.isDraining();
                if (!draining) {
                    seen = runtime.lifecycle.awaitChange(seen, PluginRuntime.HEALTH_POLL);
                }
                PluginHealth current = runtime.health();
                if (!current.equals(last) && !stream.isCancelled()) {
                    last = current;
                    stream.onNext(runtime.wire.health(current));
                }
                if (draining) {
                    break;
                }
            }
            if (!stream.isCancelled()) {
                stream.onCompleted();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (StatusRuntimeException cancelled) {
            // 宿主在推送途中取消了订阅：流已经结束
        } finally {
            transport.removeListener(wake);
        }
    }

    // ---- 调用 ----

    @Override
    public void invoke(PluginProto.InvokeRequest request, StreamObserver<PluginProto.InvokeResponse> observer) {
        if (!runtime.serving()) {
            observer.onNext(failedInvoke(runtime.wire.unavailable(runtime.lifecycle.state())));
            observer.onCompleted();
            return;
        }
        Optional<OperationDescriptor> descriptor = runtime.invoker.registry().find(request.getOperation());
        if (descriptor.isEmpty()) {
            observer.onError(Status.UNIMPLEMENTED
                    .withDescription("The operation '" + request.getOperation() + "' is not provided by this plugin.")
                    .asRuntimeException());
            return;
        }
        PluginProto.InvokeResponse response = runtime.withContext(request.getMetadata(), context -> {
            Optional<Calls.Call> call = runtime.calls.enter(context.tenant());
            if (call.isEmpty()) {
                return failedInvoke(runtime.wire.unavailable(runtime.lifecycle.state()));
            }
            try {
                OperationRequest xgi = OperationRequest.of(request.getOperation(), request.getPayload().toByteArray());
                OperationResponse invoked = runtime.invoker.invoke(context, xgi);
                PluginProto.InvokeResponse.Builder builder = PluginProto.InvokeResponse.newBuilder()
                        .setStatus(runtime.wire.status(invoked.outcome()));
                invoked.payload().ifPresent(payload -> builder.setPayload(ByteString.copyFrom(payload)));
                return builder.build();
            } finally {
                call.get().close();
            }
        });
        observer.onNext(response);
        observer.onCompleted();
    }

    private PluginProto.InvokeResponse failedInvoke(Error error) {
        return PluginProto.InvokeResponse.newBuilder().setStatus(runtime.wire.status(Result.fail(error))).build();
    }

    /**
     * 流式调用（{@code invocation.md} §1）：第一帧必须是 {@code start}，否则 {@code InvalidArgument}；本 SDK 的操作都是一元的，
     * 所以 {@code start} 点名的操作一律 {@code Unimplemented}（流式操作的契约尚未定义，RFC-0017「非目标」）。
     */
    @Override
    public StreamObserver<PluginProto.StreamFrame> invokeStream(StreamObserver<PluginProto.StreamFrame> observer) {
        return new StreamObserver<>() {
            /** 流已经以某种结局结束，之后的帧丢弃。 */
            private boolean ended;

            @Override
            public void onNext(PluginProto.StreamFrame frame) {
                if (ended) {
                    return;
                }
                ended = true;
                if (!frame.hasStart()) {
                    observer.onError(Status.INVALID_ARGUMENT.withDescription("The first frame of a stream must be 'start'.")
                            .asRuntimeException());
                    return;
                }
                if (!runtime.serving()) {
                    Error unavailable = runtime.wire.unavailable(runtime.lifecycle.state());
                    observer.onNext(PluginProto.StreamFrame.newBuilder().setEnd(runtime.wire.status(Result.fail(unavailable))).build());
                    observer.onCompleted();
                    return;
                }
                observer.onError(Status.UNIMPLEMENTED.withDescription("The operation '" + frame.getStart().getOperation()
                        + "' is not a streaming operation of this plugin.").asRuntimeException());
            }

            @Override
            public void onError(Throwable failure) {
                ended = true;
            }

            @Override
            public void onCompleted() {
                if (!ended) {
                    ended = true;
                    observer.onError(Status.INVALID_ARGUMENT.withDescription("The stream ended before its 'start' frame.")
                            .asRuntimeException());
                }
            }
        };
    }

    // ---- 事件 ----

    @Override
    public void handleEvent(PluginProto.HandleEventRequest request, StreamObserver<PluginProto.HandleEventResponse> observer) {
        observer.onNext(PluginProto.HandleEventResponse.newBuilder().setStatus(runtime.wire.status(deliver(request))).build());
        observer.onCompleted();
    }

    /**
     * 把一条消息交给以投递的方式订阅了该主题、类型也匹配的处理器（{@code invocation.md} §4）；找不到时返回错误，
     * 不改交给另一种方式的处理器。
     */
    private Result<Unit> deliver(PluginProto.HandleEventRequest request) {
        if (!runtime.serving()) {
            return Result.fail(runtime.wire.unavailable(runtime.lifecycle.state()));
        }
        Envelope envelope = Wire.envelope(request.getEnvelope());
        Optional<SubscriptionMode> mode = SubscriptionMode.parse(request.getMode());
        Optional<EventHandler> handler = mode.flatMap(delivered -> runtime.options.subscriptions().stream()
                .filter(candidate -> candidate.subscription().accepts(envelope, delivered))
                .map(PluginOptions.SubscriptionHandler::handler)
                .findFirst());
        if (handler.isEmpty()) {
            String delivered = mode.map(SubscriptionMode::wireName).orElse(request.getMode());
            return Result.fail(Error.notFound(OperationErrors.NOT_FOUND, PluginMessages.EVENT_UNSUBSCRIBED,
                    Map.of("id", runtime.manifest.id(), "topic", envelope.topic(), "type", envelope.type(), "mode", delivered)));
        }
        return runtime.withContext(request.getMetadata(), context -> {
            Optional<Calls.Call> call = runtime.calls.enter(context.tenant());
            if (call.isEmpty()) {
                return Result.fail(runtime.wire.unavailable(runtime.lifecycle.state()));
            }
            try {
                Result<Unit> outcome = handler.get().handle(context, envelope, Math.max(1, request.getAttempt()));
                return outcome == null ? Result.fail(InvocationErrors.unhandledException()) : outcome;
            } catch (FrameworkException failed) {
                runtime.logger.error("the event handler threw", failed, Map.of("topic", envelope.topic(), "id", envelope.id()));
                return Result.fail(InvocationErrors.unhandledException(failed.toError()));
            } catch (RuntimeException failed) {
                // 取消引起的异常按令牌归因（RFC-0003 规则 1），其余翻译为未处理异常（RFC-0011 规则 5）
                Optional<Error> cancellation = context.cancellation();
                if (cancellation.isPresent()) {
                    return Result.fail(cancellation.get());
                }
                runtime.logger.error("the event handler threw", failed, Map.of("topic", envelope.topic(), "id", envelope.id()));
                return Result.fail(InvocationErrors.unhandledException());
            } finally {
                call.get().close();
            }
        });
    }
}
