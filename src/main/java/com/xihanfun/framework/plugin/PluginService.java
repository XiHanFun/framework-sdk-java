package com.xihanfun.framework.plugin;

import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.CancellableContext;
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
import com.xihanfun.framework.primitives.JsonText;
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
 * {@code xihan.plugin.v1.Plugin} 服务的实现（{@code handshake.md} §3、{@code lifecycle.md} §7、{@code invocation.md} §1–§4）。
 *
 * <p>每个方法在虚拟线程上执行；会话令牌已由 {@link SessionGuard} 先于一切核对过（含流式调用读第一帧与次序核对）。业务失败写进
 * {@code Status.error}，gRPC 状态只用于协议层的失败：次序不对是 {@code FailedPrecondition}，未声明的操作与流式调用是 {@code Unimplemented}，
 * 帧序与投递不合法是 {@code InvalidArgument}。
 */
final class PluginService extends PluginGrpc.PluginImplBase {
    private final PluginRuntime runtime;

    PluginService(PluginRuntime runtime) {
        this.runtime = runtime;
    }

    // ---- 握手、配置与生命周期 ----

    @Override
    public void handshake(PluginProto.HandshakeRequest request, StreamObserver<PluginProto.HandshakeResponse> observer) {
        // 只在 Declared 接受一次（lifecycle.md §7.1）；没有共同版本的握手不改变状态，宿主可以再握手
        if (!runtime.lifecycle.is(LifecycleState.DECLARED) || !runtime.handshaking.compareAndSet(false, true)) {
            refuse(observer, "Handshake is accepted only once, in the state Declared.");
            return;
        }
        Optional<Integer> selected = PluginRuntime.negotiate(request.getProtocolVersionsList());
        if (selected.isEmpty()) {
            runtime.handshaking.set(false);
            observer.onError(Status.FAILED_PRECONDITION.withDescription("No common protocol version: the host supports "
                    + request.getProtocolVersionsList() + ", the plugin supports [" + PluginRuntime.PROTOCOL_VERSION + "].")
                    .asRuntimeException());
            return;
        }
        PluginProto.HandshakeResponse.Builder response = PluginProto.HandshakeResponse.newBuilder()
                .setProtocolVersion(selected.get())
                // primitives 写清单的值：启动前已核对它等于 SDK 支持的原语层大版本（handshake.md §3 第 2 条）
                .setPrimitives(runtime.manifest.primitives())
                .setManifest(runtime.manifestText)
                .setSdk(PluginProto.SdkInfo.newBuilder()
                        .setName(FrameworkInfo.SDK_NAME)
                        .setVersion(FrameworkInfo.SDK_VERSION)
                        .setLanguage(FrameworkInfo.SDK_LANGUAGE));
        for (OperationDescriptor descriptor : runtime.invoker.registry().list()) {
            response.addOperations(declaration(descriptor));
        }
        // 按声明的顺序写订阅，方式总是写 competing 或 broadcast（第 5 条）
        for (PluginOptions.SubscriptionHandler handler : runtime.options.subscriptions()) {
            Subscription subscription = handler.subscription();
            PluginProto.Subscription.Builder encoded = PluginProto.Subscription.newBuilder()
                    .setTopic(subscription.topic())
                    .setMode(subscription.mode().wireName());
            subscription.messageType().ifPresent(encoded::setMessageType);
            response.addSubscriptions(encoded);
        }
        // 只发作者自己的目录：运行时的消息键由宿主提供，不随握手发送（第 6 条）
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

    /** 操作声明：契约与 Schema（RFC-0011）；插件只声明一元操作，流式操作等流式契约的 RFC（{@code handshake.md} §3 第 4 条）。 */
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
        if (!runtime.lifecycle.is(LifecycleState.RESOLVED) || !runtime.configuring.compareAndSet(false, true)) {
            refuse(observer, "Configure is accepted only once, in the state Resolved.");
            return;
        }
        // 空等同 {}；不是 JSON 对象时不调配置回调（lifecycle.md §7.2）
        String section = request.getConfiguration().isEmpty() ? "{}" : request.getConfiguration();
        Result<Unit> outcome;
        if (!isJsonObject(section)) {
            outcome = Result.fail(runtime.wire.failed("configure", "the configuration is not a JSON object"));
        } else {
            List<ResolvedBinding> bindings = new ArrayList<>();
            for (PluginProto.Binding binding : request.getBindingsList()) {
                bindings.add(new ResolvedBinding(binding.getName(), binding.getType(), binding.getValue()));
            }
            Optional<Path> workDirectory = request.getWorkDirectory().isEmpty()
                    ? Optional.empty()
                    : Optional.of(Path.of(request.getWorkDirectory()));
            PluginConfiguration configuration = new PluginConfiguration(section, request.getGrantedPermissionsList(), bindings, workDirectory);
            // 回调前的权限核对以授予的权限为准（invocation.md §5）
            runtime.host.grant(request.getGrantedPermissionsList());
            outcome = lifecycleCall(context -> runtime.options.hooks().configure(context, configuration), "configure");
        }
        if (outcome.isOk()) {
            runtime.lifecycle.transition(LifecycleState.CONFIGURED, null);
        } else {
            fail("configure", outcome);
        }
        observer.onNext(PluginProto.ConfigureResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
    }

    /** 文本通过 RFC-0001 的共同 JSON 读取检查，且第一个非空白字符是左花括号：检查通过的文本只有一个值，它的类型由第一个字符决定。 */
    static boolean isJsonObject(String text) {
        if (JsonText.check(text).isPresent()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return c == '{';
            }
        }
        return false;
    }

    @Override
    public void start(PluginProto.StartRequest request, StreamObserver<PluginProto.StartResponse> observer) {
        if (!runtime.lifecycle.is(LifecycleState.CONFIGURED) || !runtime.starting.compareAndSet(false, true)) {
            refuse(observer, "Start is accepted only once, in the state Configured.");
            return;
        }
        Result<Unit> outcome = lifecycleCall(context -> runtime.options.hooks().start(context), "start");
        if (outcome.isOk()) {
            runtime.startSucceeded.set(true);
            runtime.lifecycle.transition(LifecycleState.STARTED, null);
            // 应答之前求一次健康，按结果进入 Ready、Degraded 或 Failed（lifecycle.md §7.3）
            runtime.settle();
            runtime.logger.information("started", runtime.summary());
        } else {
            fail("start", outcome);
        }
        observer.onNext(PluginProto.StartResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
    }

    @Override
    public void stop(PluginProto.StopRequest request, StreamObserver<PluginProto.StopResponse> observer) {
        if (!runtime.claimStop()) {
            refuse(observer, "Stop is not accepted before the handshake or while the plugin is stopping.");
            return;
        }
        // grace_seconds 是无符号的；0 按 5 秒（lifecycle.md §7.5）
        long seconds = Integer.toUnsignedLong(request.getGraceSeconds());
        Duration grace = seconds > 0 ? Duration.ofSeconds(seconds) : PluginRuntime.DEFAULT_STOP_GRACE;
        Result<Unit> outcome = runtime.stop(request.getReason(), grace);
        observer.onNext(PluginProto.StopResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
        // 应答送出之后再停服务端，先结束健康推送的长流；这个线程退出后进程随 serve 返回而结束
        Thread.ofVirtual().name("xihan-plugin-stop").start(runtime::finishStop);
    }

    /** 配置或启动失败即进入 Failed，原因记进状态，健康应答里带上它。 */
    private void fail(String phase, Result<Unit> outcome) {
        Error error = outcome.error().orElseThrow();
        runtime.lifecycle.transition(LifecycleState.FAILED, error);
        runtime.logger.error(phase + " failed", null, PluginRuntime.describe(error));
    }

    /** 以 gRPC 调用的截止时间与取消为上下文执行一个生命周期回调。 */
    private Result<Unit> lifecycleCall(Function<Context, Result<Unit>> body, String phase) {
        return runtime.withScope(null, (CancellableContext scope) -> runtime.hook(phase,
                () -> body.apply(scope.context().withValue(Host.KEY, runtime.host))));
    }

    /** 不在次序表里的组合：什么都不做、状态不变（{@code lifecycle.md} §7.1）。 */
    private static void refuse(StreamObserver<?> observer, String description) {
        observer.onError(Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException());
    }

    // ---- 健康 ----

    @Override
    public void health(PluginProto.HealthRequest request, StreamObserver<PluginProto.HealthResponse> observer) {
        observer.onNext(Wire.health(runtime.health(true)));
        observer.onCompleted();
    }

    /**
     * 先推当前状态，之后状态、健康项或错误变化时推送（{@code lifecycle.md} §7.4）：状态迁移即时唤醒，可服务期间每 10 秒求一次健康回调；
     * 进入 {@code Draining} 时推一次，停止应答之后（或收尾结束时）推最后的变化并结束流，长流不拖住服务端的停止。
     */
    @Override
    public void watchHealth(PluginProto.HealthRequest request, StreamObserver<PluginProto.HealthResponse> observer) {
        ServerCallStreamObserver<PluginProto.HealthResponse> stream = (ServerCallStreamObserver<PluginProto.HealthResponse>) observer;
        io.grpc.Context transport = io.grpc.Context.current();
        // 宿主取消订阅时立即唤醒等待：取消回调在 gRPC 的取消线程上执行，不经本调用被占住的执行队列
        io.grpc.Context.CancellationListener wake = cancelled -> runtime.lifecycle.touch();
        transport.addListener(wake, Runnable::run);
        try {
            PluginHealth last = runtime.health(false);
            stream.onNext(Wire.health(last));
            long seen = runtime.lifecycle.version();
            long nextAsk = System.nanoTime() + PluginRuntime.HEALTH_INTERVAL.toNanos();
            while (!stream.isCancelled() && !transport.isCancelled() && !runtime.closing()) {
                seen = runtime.lifecycle.awaitChange(seen, Duration.ofNanos(Math.max(0, nextAsk - System.nanoTime())));
                boolean ask = !runtime.closing() && System.nanoTime() >= nextAsk;
                if (ask) {
                    nextAsk = System.nanoTime() + PluginRuntime.HEALTH_INTERVAL.toNanos();
                }
                PluginHealth current = runtime.health(ask);
                if (!current.equals(last) && !stream.isCancelled()) {
                    last = current;
                    stream.onNext(Wire.health(current));
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

    /**
     * 一元调用（{@code invocation.md} §1）依次核对：操作在握手声明里，否则 {@code Unimplemented}；插件在可服务状态，否则
     * {@code PLUGINS_UNAVAILABLE_001}；然后还原上下文，经调用器与完整管道执行，{@code payload} 为空等同 {@code {}}。
     */
    @Override
    public void invoke(PluginProto.InvokeRequest request, StreamObserver<PluginProto.InvokeResponse> observer) {
        Optional<OperationDescriptor> descriptor = runtime.invoker.registry().find(request.getOperation());
        if (descriptor.isEmpty()) {
            observer.onError(Status.UNIMPLEMENTED
                    .withDescription("The operation '" + request.getOperation() + "' is not provided by this plugin.")
                    .asRuntimeException());
            return;
        }
        PluginProto.InvokeResponse response;
        if (!runtime.servable()) {
            response = failedInvoke(runtime.wire.unavailable(runtime.lifecycle.state()));
        } else {
            response = runtime.call(request.getMetadata(), context -> {
                OperationRequest xgi = OperationRequest.of(request.getOperation(), request.getPayload().toByteArray());
                OperationResponse invoked = runtime.invoker.invoke(context, xgi);
                PluginProto.InvokeResponse.Builder builder = PluginProto.InvokeResponse.newBuilder()
                        .setStatus(runtime.wire.status(invoked.outcome()));
                invoked.payload().ifPresent(payload -> builder.setPayload(ByteString.copyFrom(payload)));
                return builder.build();
            }, () -> failedInvoke(runtime.wire.unavailable(runtime.lifecycle.state())));
        }
        observer.onNext(response);
        observer.onCompleted();
    }

    private PluginProto.InvokeResponse failedInvoke(Error error) {
        return PluginProto.InvokeResponse.newBuilder().setStatus(runtime.wire.status(Result.fail(error))).build();
    }

    /**
     * 流式调用（{@code invocation.md} §1）：在流式操作契约的 RFC 之前插件不实现流式调用。读第一帧：流在第一帧之前结束或第一帧不是 {@code start}
     * 以 {@code InvalidArgument} 结束流，第一帧是 {@code start} 以 {@code Unimplemented} 结束流，不论操作是否声明、插件是否就绪。
     */
    @Override
    public StreamObserver<PluginProto.StreamFrame> invokeStream(StreamObserver<PluginProto.StreamFrame> observer) {
        return new StreamObserver<>() {
            /** 流已经以某种结局结束，之后的帧丢弃；gRPC 依次调用这些方法，不会并发。 */
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
                observer.onError(Status.UNIMPLEMENTED.withDescription("The plugin does not implement streaming operations; '"
                        + frame.getStart().getOperation() + "' cannot be invoked as a stream.").asRuntimeException());
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

    /**
     * 投递（{@code invocation.md} §4）依次核对：没有信封或方式不是空、{@code competing}、{@code broadcast} 以 {@code InvalidArgument} 拒绝；
     * 按（主题，方式，类型）找订阅，一个也没有为 {@code KERNEL_NOTFOUND_001} + {@value PluginMessages#EVENT_UNSUBSCRIBED}；插件不在可服务状态为
     * {@code PLUGINS_UNAVAILABLE_001}；然后还原上下文，匹配的订阅按声明顺序依次执行，第一个失败即结果。
     */
    @Override
    public void handleEvent(PluginProto.HandleEventRequest request, StreamObserver<PluginProto.HandleEventResponse> observer) {
        Optional<SubscriptionMode> mode = SubscriptionMode.parse(request.getMode());
        if (!request.hasEnvelope() || mode.isEmpty()) {
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("A delivery needs an envelope and a mode of '', 'competing' or 'broadcast'.")
                    .asRuntimeException());
            return;
        }
        Envelope envelope = Wire.envelope(request.getEnvelope());
        // 只交给以这种方式声明、类型为空或等于信封 type 的订阅，按握手里的声明顺序；找不到时不改交给另一种方式的处理器
        List<EventHandler> handlers = runtime.options.subscriptions().stream()
                .filter(candidate -> candidate.subscription().accepts(envelope, mode.get()))
                .map(PluginOptions.SubscriptionHandler::handler)
                .toList();
        Result<Unit> outcome;
        if (handlers.isEmpty()) {
            outcome = Result.fail(Error.notFound(OperationErrors.NOT_FOUND, PluginMessages.EVENT_UNSUBSCRIBED, Map.of("id", runtime.manifest.id(),
                    "topic", envelope.topic(), "type", envelope.type(), "mode", mode.get().wireName())));
        } else if (!runtime.servable()) {
            outcome = Result.fail(runtime.wire.unavailable(runtime.lifecycle.state()));
        } else {
            int attempt = Math.max(1, request.getAttempt());
            outcome = runtime.call(request.getMetadata(), context -> deliver(handlers, context, envelope, attempt),
                    () -> Result.fail(runtime.wire.unavailable(runtime.lifecycle.state())));
        }
        observer.onNext(PluginProto.HandleEventResponse.newBuilder().setStatus(runtime.wire.status(outcome)).build());
        observer.onCompleted();
    }

    /** 依次交给各处理器，第一个失败即终止并作为结果（与 RFC-0009「投递」第 2 条的派发器相同）。 */
    private Result<Unit> deliver(List<EventHandler> handlers, Context context, Envelope envelope, int attempt) {
        for (EventHandler handler : handlers) {
            Result<Unit> outcome = handle(handler, context, envelope, attempt);
            if (outcome.isFailed()) {
                return outcome;
            }
        }
        return Result.success();
    }

    /** 执行一个处理器：抛出为 {@code KERNEL_UNEXPECTED_001} + {@code kernel.invocation.unhandled_exception}（RFC-0011 规则 5）。 */
    private Result<Unit> handle(EventHandler handler, Context context, Envelope envelope, int attempt) {
        try {
            Result<Unit> outcome = handler.handle(context, envelope, attempt);
            return outcome == null ? Result.fail(InvocationErrors.unhandledException()) : outcome;
        } catch (FrameworkException failed) {
            runtime.logger.error("the event handler threw", failed, Map.of("topic", envelope.topic(), "id", envelope.id()));
            return Result.fail(InvocationErrors.unhandledException(failed.toError()));
        } catch (RuntimeException failed) {
            // 取消引起的异常按令牌归因（RFC-0003 规则 1），其余翻译为未处理异常（RFC-0011 规则 5）
            Optional<Error> cancellation = context.cancellationError();
            if (cancellation.isPresent()) {
                return Result.fail(cancellation.get());
            }
            runtime.logger.error("the event handler threw", failed, Map.of("topic", envelope.topic(), "id", envelope.id()));
            return Result.fail(InvocationErrors.unhandledException());
        }
    }
}
