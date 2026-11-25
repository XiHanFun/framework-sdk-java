package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.ByteString;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.invocation.CancellableContext;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.DeadlineSignals;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationErrors;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.operation.OperationRequest;
import com.xihanfun.framework.operation.OperationResponse;
import com.xihanfun.framework.plugin.v1.HostGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import io.grpc.Deadline;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 宿主回调（{@code invocation.md} §5）：本测试扮演宿主的回调服务，核对端点的选择、本地核对（权限与租户）、元数据、批量发布、
 * gRPC 状态的映射（回放 {@code callback-status.json}）与宿主写来的错误的读入（§3）。
 */
class HostCallbackTest {
    @TempDir
    Path directory;

    private Server server;

    /** 宿主收到的会话令牌：每次回调一项。 */
    private final List<String> sessions = new CopyOnWriteArrayList<>();

    /** 宿主收到的回调元数据。 */
    private final List<PluginProto.InvocationMetadata> metadata = new CopyOnWriteArrayList<>();

    /** 宿主收到的回调各自的 gRPC 截止时间（没有为 {@code null}）。 */
    private final List<Instant> deadlines = new CopyOnWriteArrayList<>();

    /** 宿主收到的发布请求。 */
    private final List<PluginProto.PublishRequest> published = new CopyOnWriteArrayList<>();

    /** 宿主收到的 {@code Host.Invoke} 载荷。 */
    private final List<ByteString> payloads = new CopyOnWriteArrayList<>();

    /** 请求：经宿主调用 {@code operation}，{@code tenant} 不为空时先往上下文里加这个租户。 */
    record Relay(String operation, String tenant) {
    }

    /**
     * 请求：在给定状态的上下文里经宿主调用 {@code operation}：{@code expired} 是截止时间已过，{@code cancelled} 是已被取消，
     * {@code timeout} 是 300 毫秒后到截止时间，{@code cancel-later} 是 300 毫秒后取消，{@code signalled} 是截止令牌已触发而
     * 墙上时钟离截止时间还有 5 分钟。
     */
    record Guarded(String operation, String state) {
    }

    /**
     * 请求：以 JSON 文本 {@code {"title":"…"}} 经宿主调用 {@code operation}；{@code text} 是 {@code high}、{@code low}、{@code reversed}
     * （孤立的代理项）或 {@code pair}（成对的代理项）；{@code state} 为 {@code expired} 或 {@code cancelled} 时上下文先到截止时间或先被取消，
     * {@code tenant} 不为空时先往上下文里加这个租户。
     */
    record Text(String operation, String text, String state, String tenant) {
    }

    /** 请求：发布这些主题，各带可选的 {@code xihantenant}；{@code tenant} 不为空时先往上下文里加这个租户。 */
    record Publish(List<String> topics, List<String> tenants, String tenant) {
    }

    /** {@code samples.publish} 发布的第一项信封的时间：东八区的时刻，写出时换算成 UTC。 */
    private static final Instant PUBLISHED_TIME = OffsetDateTime.parse("2026-11-25T11:25:00.5+08:00").toInstant();

    /** 响应：回调的结果。 */
    record Outcome(boolean ok, String code, String key, String details, String payload) {
        static Outcome of(Result<Unit> result, String payload) {
            return result.error().map(Outcome::failed).orElseGet(() -> new Outcome(true, "", "", "", payload));
        }

        static Outcome failed(Error error) {
            return new Outcome(false, error.code(), error.messageKey().orElse(""), Wire.error(error).getDetails(), error.text().orElse(""));
        }
    }

    /** 响应：逐项的发布结果。 */
    record Published(List<Outcome> results) {
    }

    @BeforeEach
    void startHost() throws IOException {
        HostGrpc.HostImplBase host = new HostGrpc.HostImplBase() {
            @Override
            public void invoke(PluginProto.InvokeRequest request, StreamObserver<PluginProto.InvokeResponse> observer) {
                metadata.add(request.getMetadata());
                payloads.add(request.getPayload());
                Deadline deadline = io.grpc.Context.current().getDeadline();
                deadlines.add(deadline == null ? Instant.EPOCH : Instant.now().plusNanos(deadline.timeRemaining(TimeUnit.NANOSECONDS)));
                String operation = request.getOperation();
                if (operation.equals("host.slow")) {
                    // 不应答，直到调用方的截止时间或取消结束这次调用（最多 5 秒）
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!io.grpc.Context.current().isCancelled() && System.nanoTime() < until) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (io.grpc.Context.current().isCancelled()) {
                        return;
                    }
                }
                if (operation.startsWith("host.status.")) {
                    observer.onError(Status.fromCodeValue(Integer.parseInt(operation.substring("host.status.".length()))).withDescription("boom")
                            .asRuntimeException());
                    return;
                }
                PluginProto.Status.Builder status = PluginProto.Status.newBuilder().setOk(false);
                switch (operation) {
                    case "host.failing" -> status.setError(PluginProto.Error.newBuilder().setCode("ORDERS_VALIDATION_001").setMessageKey("orders.quantity.invalid")
                            .setText("The quantity is invalid.").setDetails("{\"quantity\":0,\"ratio\":0.5}"));
                    case "host.bad-details" -> status.setError(PluginProto.Error.newBuilder().setCode("ORDERS_VALIDATION_001").setText("x")
                            .setDetails("{\"a\":1,\"a\":2}"));
                    case "host.bad-code" -> status.setError(PluginProto.Error.newBuilder().setCode("bad").setMessageKey("orders.quantity.invalid"));
                    case "host.bad-key" -> status.setError(PluginProto.Error.newBuilder().setCode("ORDERS_VALIDATION_001").setMessageKey("Bad Key"));
                    case "host.no-error" -> {
                        // ok 为假却没有 error
                    }
                    default -> status.setOk(true);
                }
                PluginProto.InvokeResponse.Builder response = PluginProto.InvokeResponse.newBuilder().setStatus(status);
                if (status.getOk()) {
                    response.setPayload(ByteString.copyFromUtf8("{\"echo\":\"" + operation + "\"}"));
                }
                observer.onNext(response.build());
                observer.onCompleted();
            }

            @Override
            public void publish(PluginProto.PublishRequest request, StreamObserver<PluginProto.PublishResponse> observer) {
                published.add(request);
                metadata.add(request.getMetadata());
                if (request.getEnvelopesList().stream().anyMatch(envelope -> envelope.getTopic().equals("fail.rpc"))) {
                    observer.onError(Status.INTERNAL.withDescription("boom").asRuntimeException());
                    return;
                }
                PluginProto.PublishResponse.Builder response = PluginProto.PublishResponse.newBuilder();
                for (PluginProto.Envelope envelope : request.getEnvelopesList()) {
                    if (envelope.getTopic().equals("short")) {
                        // 少给一项结果：插件按结果数与信封数不同处理
                        continue;
                    }
                    PluginProto.Status.Builder result = PluginProto.Status.newBuilder().setOk(true);
                    if (envelope.getTopic().isEmpty()) {
                        result.setOk(false).setError(PluginProto.Error.newBuilder().setCode("MESSAGING_VALIDATION_002")
                                .setMessageKey("messaging.envelope.invalid").setDetails("{\"reason\":\"topic\"}"));
                    } else if (envelope.getExtensionsOrDefault(Envelope.TENANT_EXTENSION, "").equals("t9")) {
                        // 信封的 xihantenant 由宿主逐项判定
                        result.setOk(false).setError(PluginProto.Error.newBuilder().setCode("PLUGINS_DENIED_002")
                                .setMessageKey(PluginMessages.CALLBACK_TENANT_DENIED).setDetails("{\"id\":\"xihan.tests.sample\",\"tenant\":\"t9\"}"));
                    }
                    response.addResults(result);
                }
                observer.onNext(response.build());
                observer.onCompleted();
            }
        };
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                sessions.add(String.valueOf(headers.get(SessionGuard.KEY)));
                return next.startCall(call, headers);
            }
        };
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), InsecureServerCredentials.create())
                .addService(ServerInterceptors.intercept(host, capture)).build().start();
    }

    @AfterEach
    void stopHost() {
        server.shutdownNow();
    }

    private static OperationRegistry registry() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.relay"), Relay.class, Outcome.class, (context, request) -> {
            Context call = request.tenant() == null ? context : context.withBaggage(BaggageKeys.TENANT, request.tenant());
            OperationResponse response = Host.of(call).orElseThrow().invoke(call, request.operation(), "{}".getBytes(StandardCharsets.UTF_8));
            return Result.ok(response.outcome().error().map(Outcome::failed).orElseGet(() -> Outcome.of(response.outcome(), response.payloadText().orElse(""))));
        });
        registry.add(OperationContract.of("samples.wait"), Guarded.class, Outcome.class, (context, request) -> {
            CancellableContext scope = switch (request.state()) {
                case "expired" -> context.withTimeout(Duration.ZERO);
                case "timeout" -> context.withTimeout(Duration.ofMillis(300));
                case "signalled" -> context.withTimeout(Duration.ofMinutes(5));
                default -> context.withCancellation();
            };
            try (scope) {
                if (request.state().equals("cancelled")) {
                    scope.cancel();
                } else if (request.state().equals("signalled")) {
                    DeadlineSignals.fire(scope.context());
                } else if (request.state().equals("cancel-later")) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(300);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        scope.cancel();
                    });
                }
                OperationResponse response = Host.of(scope.context()).orElseThrow().invoke(scope.context(), request.operation(),
                        "{}".getBytes(StandardCharsets.UTF_8));
                return Result.ok(response.outcome().error().map(Outcome::failed).orElseGet(() -> Outcome.of(response.outcome(), "")));
            }
        });
        registry.add(OperationContract.of("samples.text"), Text.class, Outcome.class, (context, request) -> {
            Context call = request.tenant() == null ? context : context.withBaggage(BaggageKeys.TENANT, request.tenant());
            String text = switch (request.text()) {
                case "high" -> String.valueOf((char) 0xD800);
                case "low" -> String.valueOf((char) 0xDC00);
                case "reversed" -> String.valueOf((char) 0xDC00) + (char) 0xD800;
                default -> new String(Character.toChars(0x1F600));
            };
            OperationRequest xgi = OperationRequest.of(request.operation(), "{\"title\":\"" + text + "\"}");
            CancellableContext scope = "expired".equals(request.state()) ? call.withTimeout(Duration.ZERO) : call.withCancellation();
            try (scope) {
                if ("cancelled".equals(request.state())) {
                    scope.cancel();
                }
                OperationResponse response = Host.of(scope.context()).orElseThrow().invoke(scope.context(), xgi);
                return Result.ok(response.outcome().error().map(Outcome::failed)
                        .orElseGet(() -> Outcome.of(response.outcome(), response.payloadText().orElse(""))));
            }
        });
        registry.add(OperationContract.of("samples.publish"), Publish.class, Published.class, (context, request) -> {
            Context call = request.tenant() == null ? context : context.withBaggage(BaggageKeys.TENANT, request.tenant());
            List<Envelope> envelopes = new ArrayList<>();
            for (int index = 0; index < request.topics().size(); index++) {
                Envelope.Builder envelope = Envelope.builder(request.topics().get(index), "m" + index, "urn:test", "samples.created");
                if (index == 0) {
                    // 第一项带时间（东八区的时刻）与 dataschema，其余没有
                    envelope.time(PUBLISHED_TIME).dataSchema("urn:schema:samples");
                }
                String tenant = request.tenants().get(index);
                if (!tenant.isEmpty()) {
                    envelope.extension(Envelope.TENANT_EXTENSION, tenant);
                }
                envelopes.add(envelope.build());
            }
            List<Outcome> results = new ArrayList<>();
            Host.of(call).orElseThrow().publish(call, envelopes).forEach(result -> results.add(Outcome.of(result, "")));
            return Result.ok(new Published(results));
        });
        return registry;
    }

    private RunningPlugin start(Path root, String manifest, Map<String, String> environment) throws IOException {
        RunningPlugin plugin = RunningPlugin.start(root, manifest, PluginOptions.builder().operations(registry()), environment);
        plugin.ready("{}");
        return plugin;
    }

    private RunningPlugin start(String hostAddress) throws IOException {
        return start(directory, RunningPlugin.MANIFEST, Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress));
    }

    private String hostAddress() {
        return "tcp 127.0.0.1:" + ((InetSocketAddress) server.getListenSockets().get(0)).getPort();
    }

    private static <T> T call(RunningPlugin plugin, String operation, Object request, String tenant, Class<T> type) throws IOException {
        PluginProto.InvocationMetadata.Builder carried = PluginProto.InvocationMetadata.newBuilder()
                .setTraceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        if (tenant != null) {
            carried.putBaggage(BaggageKeys.TENANT, tenant);
        }
        PluginProto.InvokeResponse response = plugin.stub().withDeadlineAfter(20, TimeUnit.SECONDS).invoke(PluginProto.InvokeRequest.newBuilder()
                .setOperation(operation).setPayload(ByteString.copyFrom(RunningPlugin.JSON.writeValueAsBytes(request))).setMetadata(carried).build());
        assertTrue(response.getStatus().getOk(), response.toString());
        return RunningPlugin.JSON.readValue(response.getPayload().toByteArray(), type);
    }

    private static Outcome relay(RunningPlugin plugin, String operation) throws IOException {
        return call(plugin, "samples.relay", new Relay(operation, null), null, Outcome.class);
    }

    @Test
    void invokesTheHostWithTheSessionTokenTheCallMetadataAndTheHandedTenant() throws Exception {
        try (RunningPlugin plugin = start(hostAddress())) {
            Outcome relayed = call(plugin, "samples.relay", new Relay("orders.create", null), "t1", Outcome.class);
            assertEquals(new Outcome(true, "", "", "", "{\"echo\":\"orders.create\"}"), relayed);
            assertEquals(List.of(RunningPlugin.TOKEN), sessions, "every callback carries the session token");
            PluginProto.InvocationMetadata sent = metadata.get(0);
            assertEquals("t1", sent.getBaggageOrThrow(BaggageKeys.TENANT), "the tenant the host handed to the plugin goes back with the callback");
            assertTrue(sent.getTraceParent().startsWith("00-0af7651916cd43dd8448eb211c80319c-"), sent.getTraceParent());
            assertEquals("", sent.getTraceState(), "trace_state is written empty");
            // deadline 用 UTC、七位小数、+00:00；gRPC 截止时间与它相同（invocation.md §2、§5）
            assertTrue(sent.getDeadline().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{7}\\+00:00"), sent.getDeadline());
            Instant written = Wire.parseTimestamp(sent.getDeadline()).orElseThrow();
            assertTrue(Math.abs(written.toEpochMilli() - deadlines.get(0).toEpochMilli()) < 1000, written + " vs " + deadlines.get(0));

            // 带了宿主没有交给插件的租户：本地拒绝，不发给宿主（invocation.md §5，ADR-0195）
            Outcome other = call(plugin, "samples.relay", new Relay("orders.create", "t2"), "t1", Outcome.class);
            assertEquals(new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED,
                    "{\"id\":\"xihan.tests.sample\",\"tenant\":\"t2\"}", ""), other);
            Outcome background = call(plugin, "samples.relay", new Relay("orders.create", "t1"), null, Outcome.class);
            assertEquals(PluginMessages.CALLBACK_TENANT_DENIED, background.key(), "a call without a tenant cannot add one");
            assertEquals(1, sessions.size(), "refused callbacks do not reach the host");
        }
    }

    @Test
    void callbacksNeedThePermissionDeclaredAndGranted() throws Exception {
        // 声明了、没有授予
        Path first = Files.createDirectories(directory.resolve("ungranted"));
        RunningPlugin ungranted = RunningPlugin.start(first, RunningPlugin.MANIFEST, PluginOptions.builder().operations(registry()),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress()));
        try {
            ungranted.handshake();
            assertTrue(ungranted.stub().configure(PluginProto.ConfigureRequest.newBuilder().setConfiguration("{}")
                    .addGrantedPermissions(Host.PERMISSION_PUBLISH).build()).getStatus().getOk());
            assertTrue(ungranted.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
            assertEquals(new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                    "{\"id\":\"xihan.tests.sample\",\"permission\":\"capability:kernel.pipeline\"}", ""), relay(ungranted, "orders.create"));
        } finally {
            ungranted.close();
        }
        // 授予了、清单没有声明：清单是插件权限的唯一事实源
        Path second = Files.createDirectories(directory.resolve("undeclared"));
        try (RunningPlugin undeclared = start(second, RunningPlugin.MANIFEST.replace(", \"capability:messaging.queue\"", ""),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress()))) {
            Published refused = call(undeclared, "samples.publish", new Publish(List.of("samples.events", "samples.events"), List.of("", ""), null), null,
                    Published.class);
            Outcome denied = new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                    "{\"id\":\"xihan.tests.sample\",\"permission\":\"capability:messaging.queue\"}", "");
            assertEquals(List.of(denied, denied), refused.results(), "every envelope gets the batch's error");
        }
        assertEquals(List.of(), sessions, "nothing reaches the host");
    }

    @Test
    void readsHostErrorsAndMapsGrpcStatusesByTheRules() throws Exception {
        try (RunningPlugin plugin = start(hostAddress())) {
            // 带键的错误按键处理，text 不留：交回宿主时由宿主翻译
            Outcome failing = relay(plugin, "host.failing");
            assertEquals("ORDERS_VALIDATION_001", failing.code());
            assertEquals("orders.quantity.invalid", failing.key());
            assertEquals("{\"quantity\":0,\"ratio\":0.5}", failing.details());
            assertEquals("", failing.payload());
            // details 通不过共同 JSON 读取检查：当作没有细节，错误本身照收
            assertEquals(new Outcome(false, "ORDERS_VALIDATION_001", "", "", "x"), relay(plugin, "host.bad-details"));
            assertEquals(new Outcome(false, PluginErrors.UNEXPECTED, PluginMessages.FAILED,
                    "{\"id\":\"xihan.tests.sample\",\"phase\":\"invoke\",\"reason\":\"invalid error code 'bad'\"}", ""), relay(plugin, "host.bad-code"));
            assertEquals(new Outcome(false, "ORDERS_VALIDATION_001", "", "", "ORDERS_VALIDATION_001"), relay(plugin, "host.bad-key"),
                    "a key that is not a message key makes a free-text error, its text the code when text is empty");
            assertEquals(new Outcome(false, PluginErrors.UNEXPECTED, PluginMessages.FAILED,
                    "{\"id\":\"xihan.tests.sample\",\"phase\":\"host.no-error\",\"reason\":\"the host reported a failure without an error\"}", ""),
                    relay(plugin, "host.no-error"));

            // gRPC 状态经真实的回调映射（全表见 replaysTheCallbackStatusVector）
            assertEquals(new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.SESSION_INVALID, "", ""), relay(plugin, "host.status.16"));
            assertEquals(new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                    "{\"id\":\"xihan.tests.sample\",\"permission\":\"capability:kernel.pipeline\"}", ""), relay(plugin, "host.status.7"));
            assertEquals(new Outcome(false, "KERNEL_NOTFOUND_001", "kernel.operation.unknown", "{\"id\":\"host.status.12\"}", ""),
                    relay(plugin, "host.status.12"));
            assertEquals(new Outcome(false, "KERNEL_VALIDATION_001", "kernel.operation.invalid_payload", "{\"id\":\"host.status.3\",\"reason\":\"boom\"}", ""),
                    relay(plugin, "host.status.3"));
            assertEquals(new Outcome(false, PluginErrors.UNEXPECTED, PluginMessages.FAILED,
                    "{\"id\":\"xihan.tests.sample\",\"phase\":\"host.status.13\",\"reason\":\"Internal: boom\"}", ""), relay(plugin, "host.status.13"));
            assertEquals(new Outcome(false, PluginErrors.UNAVAILABLE, PluginMessages.HOST_UNAVAILABLE,
                    "{\"id\":\"xihan.tests.sample\",\"reason\":\"Unavailable: boom\"}", ""), relay(plugin, "host.status.14"));
        }
    }

    @Test
    void publishesInOneBatchAndLeavesEnvelopeTenantsToTheHost() throws Exception {
        try (RunningPlugin plugin = start(hostAddress())) {
            Published batch = call(plugin, "samples.publish", new Publish(List.of("samples.events", "", "samples.events"), List.of("", "", "t9"), null), "t1",
                    Published.class);
            assertEquals(List.of(
                    new Outcome(true, "", "", "", ""),
                    new Outcome(false, "MESSAGING_VALIDATION_002", "messaging.envelope.invalid", "{\"reason\":\"topic\"}", ""),
                    new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED, "{\"id\":\"xihan.tests.sample\",\"tenant\":\"t9\"}", "")),
                    batch.results());
            assertEquals(1, published.size(), "the envelopes go to the host in one call");
            assertEquals(3, published.get(0).getEnvelopesCount(), "the envelope's xihantenant is not checked locally");
            // time 换算成 UTC、以 Z 结尾，dataschema 原样；没有的写空串
            List<String> written = published.get(0).getEnvelopesList().stream().map(sent -> sent.getTime() + "|" + sent.getDataSchema()).toList();
            assertEquals(List.of("2026-11-25T03:25:00.5Z|urn:schema:samples", "|", "|"), written);
            assertEquals("t1", published.get(0).getMetadata().getBaggageOrThrow(BaggageKeys.TENANT), "the host builds the context from the metadata");

            assertEquals(List.of(), call(plugin, "samples.publish", new Publish(List.of(), List.of(), null), null, Published.class).results());
            assertEquals(1, published.size(), "an empty batch does not reach the host");

            // 上下文的租户不属于在途调用：整批不发，每一项都是这个错误
            Outcome tenantDenied = new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED,
                    "{\"id\":\"xihan.tests.sample\",\"tenant\":\"t2\"}", "");
            assertEquals(List.of(tenantDenied, tenantDenied),
                    call(plugin, "samples.publish", new Publish(List.of("samples.events", "samples.events"), List.of("", ""), "t2"), "t1", Published.class)
                            .results());
            assertEquals(1, published.size());

            Outcome mismatch = new Outcome(false, PluginErrors.UNEXPECTED, PluginMessages.FAILED,
                    "{\"id\":\"xihan.tests.sample\",\"phase\":\"publish\",\"reason\":\"the host returned 1 results for 2 envelopes\"}", "");
            assertEquals(List.of(mismatch, mismatch),
                    call(plugin, "samples.publish", new Publish(List.of("short", "samples.events"), List.of("", ""), null), null, Published.class).results());
            Outcome failed = new Outcome(false, PluginErrors.UNEXPECTED, PluginMessages.FAILED,
                    "{\"id\":\"xihan.tests.sample\",\"phase\":\"publish\",\"reason\":\"Internal: boom\"}", "");
            assertEquals(List.of(failed, failed),
                    call(plugin, "samples.publish", new Publish(List.of("fail.rpc", "samples.events"), List.of("", ""), null), null, Published.class).results(),
                    "an RPC failure gives every envelope the same error");
        }
    }

    @Test
    void anExpiredOrCancelledContextSendsNoCallback() throws Exception {
        // invocation.md §5 第 ④ 步：截止时间已过按 DeadlineExceeded，已被取消按取消信号已触发的 Cancelled，不发请求
        try (RunningPlugin plugin = start(hostAddress())) {
            assertEquals(new Outcome(false, "KERNEL_TIMEOUT_001", "kernel.invocation.deadline_exceeded", "", ""),
                    call(plugin, "samples.wait", new Guarded("orders.create", "expired"), null, Outcome.class));
            assertEquals(new Outcome(false, "KERNEL_CANCELLED_001", "kernel.invocation.cancelled", "", ""),
                    call(plugin, "samples.wait", new Guarded("orders.create", "cancelled"), null, Outcome.class));
            // 截止令牌按 nanoTime 由定时器触发，墙上时钟可能还没到截止时间：归因看令牌，仍是 DeadlineExceeded
            assertEquals(new Outcome(false, "KERNEL_TIMEOUT_001", "kernel.invocation.deadline_exceeded", "", ""),
                    call(plugin, "samples.wait", new Guarded("orders.create", "signalled"), null, Outcome.class));
            assertEquals(List.of(), sessions, "no request reaches the host");
            // 在途的回调：截止时间到了由 grpc-java 以 DeadlineExceeded 结束，调用方取消以 Cancelled 结束
            assertEquals(new Outcome(false, "KERNEL_TIMEOUT_001", "kernel.invocation.deadline_exceeded", "", ""),
                    call(plugin, "samples.wait", new Guarded("host.slow", "timeout"), null, Outcome.class));
            assertEquals(new Outcome(false, "KERNEL_CANCELLED_001", "kernel.invocation.cancelled", "", ""),
                    call(plugin, "samples.wait", new Guarded("host.slow", "cancel-later"), null, Outcome.class));
        }
        // 先后：端点在前，其次权限，最后才是截止时间与取消
        Path missing = Files.createDirectories(directory.resolve("missing"));
        try (RunningPlugin plugin = start(missing, RunningPlugin.MANIFEST, Map.of())) {
            assertEquals(PluginMessages.HOST_UNAVAILABLE, call(plugin, "samples.wait", new Guarded("orders.create", "expired"), null, Outcome.class).key());
        }
        Path undeclared = Files.createDirectories(directory.resolve("undeclared"));
        try (RunningPlugin plugin = start(undeclared, RunningPlugin.MANIFEST.replace("\"capability:kernel.pipeline\", ", ""),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress()))) {
            assertEquals(PluginMessages.CALLBACK_DENIED, call(plugin, "samples.wait", new Guarded("orders.create", "cancelled"), null, Outcome.class).key());
        }
    }

    @Test
    void aTextPayloadThatCannotBeEncodedFailsLocallyAfterTheOtherChecks() throws Exception {
        // invocation.md §5（ADR-0312）：以字符串给出的载荷发出前严格编码成 UTF-8，孤立的代理项编不成即不发请求，与宿主读到不是 JSON 时相同
        Outcome invalid = new Outcome(false, "KERNEL_VALIDATION_001", "kernel.operation.invalid_payload",
                "{\"id\":\"orders.create\",\"reason\":\"$\"}", "");
        try (RunningPlugin plugin = start(hostAddress())) {
            for (String text : List.of("high", "low", "reversed")) {
                assertEquals(invalid, call(plugin, "samples.text", new Text("orders.create", text, null, null), null, Outcome.class), text);
            }
            assertEquals(List.of(), sessions, "a payload that cannot be encoded is not sent");
            // 成对的代理项是一个字符：照常发出，宿主收到它的 UTF-8
            Outcome paired = call(plugin, "samples.text", new Text("orders.create", "pair", null, null), null, Outcome.class);
            assertEquals(new Outcome(true, "", "", "", "{\"echo\":\"orders.create\"}"), paired);
            assertEquals(List.of(ByteString.copyFromUtf8("{\"title\":\"" + new String(Character.toChars(0x1F600)) + "\"}")), payloads);
            // 先后：租户、截止时间与取消先失败的仍报它们（宿主在管道的 Validate 阶段才解码载荷）
            assertEquals(PluginMessages.CALLBACK_TENANT_DENIED,
                    call(plugin, "samples.text", new Text("orders.create", "high", null, "t2"), "t1", Outcome.class).key());
            assertEquals(new Outcome(false, "KERNEL_TIMEOUT_001", "kernel.invocation.deadline_exceeded", "", ""),
                    call(plugin, "samples.text", new Text("orders.create", "high", "expired", null), null, Outcome.class));
            assertEquals(new Outcome(false, "KERNEL_CANCELLED_001", "kernel.invocation.cancelled", "", ""),
                    call(plugin, "samples.text", new Text("orders.create", "low", "cancelled", null), null, Outcome.class));
            assertEquals(1, sessions.size(), "only the paired payload reaches the host");
        }
        // 端点与权限同样在前
        Path missing = Files.createDirectories(directory.resolve("missing"));
        try (RunningPlugin plugin = start(missing, RunningPlugin.MANIFEST, Map.of())) {
            assertEquals(PluginMessages.HOST_UNAVAILABLE,
                    call(plugin, "samples.text", new Text("orders.create", "high", null, null), null, Outcome.class).key());
        }
        Path undeclared = Files.createDirectories(directory.resolve("undeclared"));
        try (RunningPlugin plugin = start(undeclared, RunningPlugin.MANIFEST.replace("\"capability:kernel.pipeline\", ", ""),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress()))) {
            assertEquals(PluginMessages.CALLBACK_DENIED,
                    call(plugin, "samples.text", new Text("orders.create", "high", null, null), null, Outcome.class).key());
        }
        assertEquals(1, sessions.size());
    }

    @Test
    void theFirstEndpointTheSdkCanDialIsChosenAndKept() throws Exception {
        // 宿主的偏好在前：命名管道本 SDK 连不了，取后面的回环 TCP（handshake.md §4）
        try (RunningPlugin plugin = start("npipe \\\\.\\pipe\\xihan-host-test;" + hostAddress())) {
            assertEquals(Network.TCP, plugin.runtime.host.endpoint().orElseThrow().network());
            assertTrue(relay(plugin, "orders.create").ok());
        }
    }

    @Test
    void withoutASupportedEndpointEveryCallbackFailsWithoutARequest() throws Exception {
        Outcome unavailable = new Outcome(false, PluginErrors.UNAVAILABLE, PluginMessages.HOST_UNAVAILABLE,
                "{\"id\":\"xihan.tests.sample\",\"reason\":\"no supported callback endpoint\"}", "");
        int round = 0;
        for (Map<String, String> environment : List.of(Map.<String, String>of(), Map.of(PluginRuntime.ENV_HOST_ADDRESS, "npipe \\\\.\\pipe\\xihan-host"),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress() + ";"))) {
            Path root = Files.createDirectories(directory.resolve("r" + round++));
            try (RunningPlugin plugin = start(root, RunningPlugin.MANIFEST, environment)) {
                assertEquals(unavailable, relay(plugin, "orders.create"), environment.toString());
                assertEquals(List.of(unavailable, unavailable),
                        call(plugin, "samples.publish", new Publish(List.of("samples.events", "samples.events"), List.of("", ""), null), null, Published.class)
                                .results());
            }
        }
        assertEquals(List.of(), sessions, "no request is sent");
    }

    @Test
    void anUnreachableHostIsUnavailable() throws Exception {
        String unreachable = hostAddress();
        server.shutdownNow().awaitTermination();
        try (RunningPlugin plugin = start(unreachable)) {
            Outcome outcome = relay(plugin, "orders.create");
            assertEquals(PluginErrors.UNAVAILABLE, outcome.code());
            assertEquals(PluginMessages.HOST_UNAVAILABLE, outcome.key());
            assertTrue(RunningPlugin.JSON.readTree(outcome.details()).get("reason").asText().startsWith("Unavailable: "), outcome.details());
        }
    }

    @Test
    void replaysTheCallbackStatusVector() throws Exception {
        JsonNode vectors = Vectors.read("plugin/callback-status.json");
        Wire wire = new Wire(vectors.get("pluginId").asText());
        String operation = vectors.get("operation").asText();
        for (JsonNode vector : vectors.get("cases")) {
            int code = vector.get("statusCode").intValue();
            assertEquals(vector.get("status").asText(), Host.statusName(code), vector.toString());
            Error error = Host.mapStatus(wire, vector.get("rpc").asText(), code, vector.get("message").asText(), vector.get("contextCancelled").booleanValue(),
                    operation);
            assertEquals(vector.get("code").asText(), error.code(), vector.toString());
            assertEquals(vector.get("key").asText(), error.messageKey().orElseThrow(), vector.toString());
            JsonNode expected = vector.get("details");
            if (expected.isNull()) {
                assertTrue(error.details().isEmpty(), vector.toString());
            } else {
                assertEquals(expected, RunningPlugin.JSON.readTree(Wire.error(error).getDetails()), vector.toString());
            }
            assertEquals("", Wire.error(error).getText(), "the mapped errors carry message keys");
        }
        assertEquals("17", Host.statusName(17), "codes outside 0-16 are written as numbers");
    }

    @Test
    void deniedAndUnknownCallbacksNameThePermissionAndTheOperation() {
        // 「回调被拒」与「操作未登记」各只拼一次（PluginErrors.callbackDenied、OperationErrors.notFound）：发出之前的本地核对与宿主的
        // gRPC 状态给出同一个错误，渲染出的文案带插件 ID、权限与操作 ID
        Wire wire = new Wire("xihan.tests.sample");
        Error denied = PluginErrors.callbackDenied("xihan.tests.sample", Host.PERMISSION_INVOKE);
        assertEquals(PluginErrors.CALLBACK_DENIED, denied.code());
        assertEquals(Optional.of(PluginMessages.CALLBACK_DENIED), denied.messageKey());
        assertEquals(Map.of("id", "xihan.tests.sample", "permission", "capability:kernel.pipeline"), denied.details());
        assertEquals(Optional.of("Plugin 'xihan.tests.sample' may not use permission 'capability:kernel.pipeline': "
                + "it must be declared in the manifest and granted by the host."),
                PluginMessages.catalog().messages().resolve(PluginMessages.CALLBACK_DENIED, "en-US", denied.details()));
        int permissionDenied = Status.Code.PERMISSION_DENIED.value();
        assertEquals(denied, Host.mapStatus(wire, Host.RPC_INVOKE, permissionDenied, "denied", false, "orders.create"));
        assertEquals(PluginErrors.callbackDenied("xihan.tests.sample", Host.PERMISSION_PUBLISH),
                Host.mapStatus(wire, Host.RPC_PUBLISH, permissionDenied, "denied", false, "orders.create"));

        Error unknown = Host.mapStatus(wire, Host.RPC_INVOKE, Status.Code.UNIMPLEMENTED.value(), "", false, "orders.missing");
        assertEquals(OperationErrors.notFound("orders.missing"), unknown);
        assertEquals("Operation 'orders.missing' is not registered.", unknown.message());
        Error invalid = Host.mapStatus(wire, Host.RPC_INVOKE, Status.Code.INVALID_ARGUMENT.value(), "$.quantity", false, "orders.create");
        assertEquals(OperationErrors.invalidPayload("orders.create", "$.quantity"), invalid);
        assertEquals("The payload of operation 'orders.create' is not valid: $.quantity", invalid.message());
    }

    @Test
    @SuppressWarnings("deprecation")
    void theDeprecatedPermissionNamesAreAliases() {
        // PERMISSION_INVOKE / PERMISSION_PUBLISH 与其他语言的 SDK 同名；INVOKE_PERMISSION / PUBLISH_PERMISSION 是保留的旧名
        assertEquals("capability:kernel.pipeline", Host.PERMISSION_INVOKE);
        assertEquals("capability:messaging.queue", Host.PERMISSION_PUBLISH);
        assertEquals(Host.PERMISSION_INVOKE, Host.INVOKE_PERMISSION);
        assertEquals(Host.PERMISSION_PUBLISH, Host.PUBLISH_PERMISSION);
    }

    @Test
    void replaysTheErrorReadingVector() throws Exception {
        // error-reading.json：插件读宿主写来的 Error 与没有 error 的 Status（invocation.md §3）
        JsonNode vectors = Vectors.read("plugin/error-reading.json");
        Wire wire = new Wire(vectors.get("pluginId").asText());
        for (JsonNode vector : vectors.get("cases")) {
            assertDecoded(vector.get("expected"), wire.readError(proto(vector.get("error"))), vector.get("name").asText());
        }
        int replayed = 0;
        for (JsonNode vector : vectors.get("statusWithoutError")) {
            if (!vector.get("reader").asText().equals("plugin")) {
                continue;
            }
            String phase = vector.get("rpc").asText().equals("publish") ? Host.RPC_PUBLISH : vector.get("operation").asText();
            // ok 为假却没有 error，与整个 status 缺失（协议里缺失的消息读成缺省实例）相同
            for (PluginProto.Status status : java.util.Arrays.asList(PluginProto.Status.newBuilder().setOk(false).build(),
                    PluginProto.Status.getDefaultInstance(), null)) {
                assertDecoded(vector.get("expected"), wire.readStatus(status, phase).error().orElseThrow(), vector.toString());
            }
            replayed++;
        }
        assertEquals(2, replayed);
    }

    private static PluginProto.Error proto(JsonNode error) {
        PluginProto.Error.Builder builder = PluginProto.Error.newBuilder().setCode(error.get("code").asText())
                .setMessageKey(error.get("message_key").asText()).setText(error.get("text").asText()).setDetails(error.get("details").asText());
        if (error.hasNonNull("inner")) {
            builder.setInner(proto(error.get("inner")));
        }
        return builder.build();
    }

    private static void assertDecoded(JsonNode expected, Error actual, String name) throws IOException {
        assertEquals(expected.get("code").asText(), actual.code(), name);
        assertEquals(expected.get("key").isNull() ? java.util.Optional.empty() : java.util.Optional.of(expected.get("key").asText()), actual.messageKey(), name);
        assertEquals(expected.get("text").isNull() ? java.util.Optional.empty() : java.util.Optional.of(expected.get("text").asText()), actual.text(), name);
        if (expected.get("details").isNull()) {
            assertTrue(actual.details().isEmpty(), name + ": " + actual.details());
        } else {
            assertEquals(expected.get("details"), RunningPlugin.JSON.readTree(Wire.error(actual).getDetails()), name);
        }
        if (expected.get("inner").isNull()) {
            assertTrue(actual.inner().isEmpty(), name);
        } else {
            assertDecoded(expected.get("inner"), actual.inner().orElseThrow(), name + " / inner");
        }
    }

    @Test
    void errorsAreWrittenAndReadByTheWireRules() throws Exception {
        Wire wire = new Wire("xihan.tests.sample");
        Error error = Error.unexpected("SAMPLES_UNEXPECTED_001", "samples.failed",
                Map.of("count", 3, "big", 9007199254740993L, "ratio", 2.0, "nan", Double.NaN, "nested", Map.of("list", List.of(1, 1.5))),
                Error.literal("SAMPLES_VALIDATION_002", "inner text"));
        PluginProto.Error encoded = Wire.error(error);
        assertEquals("{\"big\":9007199254740993,\"count\":3,\"nan\":\"NaN\",\"nested\":{\"list\":[1,1.5]},\"ratio\":2.0}", encoded.getDetails());
        assertEquals("samples.failed", encoded.getMessageKey());
        assertEquals("", encoded.getText(), "an error with a message key leaves text empty (invocation.md §3)");
        assertEquals("inner text", encoded.getInner().getText(), "a free-text error writes text");
        assertEquals("", encoded.getInner().getMessageKey());
        assertEquals("", encoded.getInner().getDetails(), "no details: empty");
        assertFalse(encoded.getInner().hasInner());

        Error restored = wire.readError(encoded);
        assertEquals("SAMPLES_UNEXPECTED_001", restored.code());
        assertEquals(9007199254740993L, ((Number) restored.details().get("big")).longValue(), "64-bit integers round-trip exactly");
        assertEquals(2.0, restored.details().get("ratio"));
        assertEquals("NaN", restored.details().get("nan"));
        assertEquals("SAMPLES_VALIDATION_002", restored.inner().orElseThrow().code());
        assertEquals("inner text", restored.inner().orElseThrow().text().orElseThrow());

        // details 为空、通不过检查、不是对象：当作没有细节；inner 同此
        for (String details : List.of("", "{\"a\":1,\"a\":2}", "[1]", "{\"a\":", "1e400", "{\"a\":1e400}")) {
            Error read = wire.readError(PluginProto.Error.newBuilder().setCode("SAMPLES_VALIDATION_001").setMessageKey("samples.failed").setDetails(details)
                    .setInner(PluginProto.Error.newBuilder().setCode("SAMPLES_VALIDATION_002").setDetails(details)).build());
            assertTrue(read.details().isEmpty(), details);
            assertEquals("samples.failed", read.messageKey().orElseThrow());
            assertTrue(read.inner().orElseThrow().details().isEmpty(), details);
            assertEquals("SAMPLES_VALIDATION_002", read.inner().orElseThrow().text().orElseThrow(), "a free-text error without text uses the code");
        }
        Error badInner = wire.readError(PluginProto.Error.newBuilder().setCode("SAMPLES_VALIDATION_001").setText("outer")
                .setInner(PluginProto.Error.newBuilder().setCode("bad")).build());
        assertEquals(PluginErrors.UNEXPECTED, badInner.inner().orElseThrow().code(), "the inner error follows the same rules");
        assertEquals(java.util.Optional.of(wire.failed("publish", "the host reported a failure without an error")),
                wire.readStatus(PluginProto.Status.newBuilder().setOk(false).build(), "publish").error());
        assertTrue(wire.readStatus(PluginProto.Status.newBuilder().setOk(true).build(), "publish").isOk());

        PluginProto.Error unavailable = Wire.error(wire.unavailable(LifecycleState.DRAINING));
        assertEquals("", unavailable.getText());
        assertEquals("{\"id\":\"xihan.tests.sample\",\"state\":\"Draining\"}", unavailable.getDetails());
    }

    @Test
    void detailsWithLoneSurrogatesAreNoDetails() {
        // 以字符串交来的 JSON 文本严格编码成 UTF-8：孤立的代理项编不成，与非法的字节一样当作没有细节，不先换成 ? 再读（RFC-0001「JSON 文本」，ADR-0301）；
        // protobuf 从线上解出的字符串没有孤立的代理项，这里直接给 readError 一个进程内建的消息
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);
        Wire wire = new Wire("xihan.tests.sample");
        for (String details : List.of("{\"a\":\"" + high + "\"}", "{\"a\":\"" + low + "\"}", "{\"" + high + "\":1}", "{\"a\":\"" + low + high + "\"}")) {
            assertEquals(Map.of(), Wire.readDetails(details), details);
            Error read = wire.readError(PluginProto.Error.newBuilder().setCode("SAMPLES_VALIDATION_001").setMessageKey("samples.failed")
                    .setDetails(details).build());
            assertTrue(read.details().isEmpty(), details);
            assertEquals("samples.failed", read.messageKey().orElseThrow(), "the error itself is still read");
        }
        String pair = new String(Character.toChars(0x1F600));
        assertEquals(Map.of("a", pair), Wire.readDetails("{\"a\":\"" + pair + "\"}"), "a surrogate pair is one character");
    }
}
