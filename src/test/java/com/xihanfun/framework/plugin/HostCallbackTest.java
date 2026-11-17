package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.operation.OperationResponse;
import com.xihanfun.framework.plugin.v1.HostGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 宿主回调（{@code invocation.md} §5）：本测试扮演宿主的回调服务，核对会话令牌、上下文、租户的限制、批量发布与 gRPC 状态的映射。
 */
class HostCallbackTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private Server server;

    /** 宿主收到的会话令牌。 */
    private final List<String> sessions = new CopyOnWriteArrayList<>();

    /** 宿主收到的回调元数据里的租户。 */
    private final List<String> tenants = new CopyOnWriteArrayList<>();

    /** 请求：经宿主调用 {@code operation}。 */
    record Relay(String operation, String tenant) {
    }

    /** 请求：发布这些主题，各带可选的租户。 */
    record Publish(List<String> topics, List<String> tenants) {
    }

    /** 响应：回调的结果。 */
    record Outcome(boolean ok, String code, String key, String payload) {
        static Outcome of(Result<Unit> result, String payload) {
            return result.error().map(error -> new Outcome(false, error.code(), error.messageKey().orElse(""), ""))
                    .orElseGet(() -> new Outcome(true, "", "", payload));
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
                tenants.add(request.getMetadata().getBaggageOrDefault(BaggageKeys.TENANT, ""));
                switch (request.getOperation()) {
                    case "host.denied" -> observer.onError(Status.PERMISSION_DENIED.withDescription("denied").asRuntimeException());
                    case "host.missing" -> observer.onError(Status.UNIMPLEMENTED.asRuntimeException());
                    case "host.failing" -> {
                        observer.onNext(PluginProto.InvokeResponse.newBuilder().setStatus(PluginProto.Status.newBuilder().setOk(false)
                                .setError(PluginProto.Error.newBuilder().setCode("ORDERS_VALIDATION_001").setMessageKey("orders.quantity.invalid")
                                        .setText("The quantity is invalid.").setDetails("{\"quantity\":0,\"ratio\":0.5}"))).build());
                        observer.onCompleted();
                    }
                    case "host.malformed" -> {
                        observer.onNext(PluginProto.InvokeResponse.newBuilder().setStatus(PluginProto.Status.newBuilder().setOk(false)
                                .setError(PluginProto.Error.newBuilder().setCode("ORDERS_VALIDATION_001").setText("x").setDetails("{\"a\":1,\"a\":2}"))).build());
                        observer.onCompleted();
                    }
                    default -> {
                        observer.onNext(PluginProto.InvokeResponse.newBuilder().setStatus(PluginProto.Status.newBuilder().setOk(true))
                                .setPayload(ByteString.copyFromUtf8("{\"echo\":\"" + request.getOperation() + "\"}")).build());
                        observer.onCompleted();
                    }
                }
            }

            @Override
            public void publish(PluginProto.PublishRequest request, StreamObserver<PluginProto.PublishResponse> observer) {
                PluginProto.PublishResponse.Builder response = PluginProto.PublishResponse.newBuilder();
                for (PluginProto.Envelope envelope : request.getEnvelopesList()) {
                    response.addResults(envelope.getTopic().isEmpty()
                            ? PluginProto.Status.newBuilder().setOk(false).setError(PluginProto.Error.newBuilder().setCode("MESSAGING_VALIDATION_002")
                                    .setMessageKey("messaging.envelope.invalid").setText("The envelope is invalid: topic").setDetails("{\"reason\":\"topic\"}"))
                            : PluginProto.Status.newBuilder().setOk(true));
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
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), InsecureServerCredentials.create())
                .addService(ServerInterceptors.intercept(host, capture)).build().start();
    }

    @AfterEach
    void stopHost() {
        server.shutdownNow();
    }

    private RunningPlugin start(String hostAddress) throws IOException {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.relay"), Relay.class, Outcome.class, (context, request) -> {
            Context call = request.tenant() == null ? context : context.withBaggage(BaggageKeys.TENANT, request.tenant());
            OperationResponse response = Host.of(call).orElseThrow().invoke(call, request.operation(), "{}".getBytes(StandardCharsets.UTF_8));
            return Result.ok(Outcome.of(response.outcome(), response.payloadText().orElse("")));
        });
        registry.add(OperationContract.of("samples.publish"), Publish.class, Published.class, (context, request) -> {
            List<Envelope> envelopes = new ArrayList<>();
            for (int index = 0; index < request.topics().size(); index++) {
                Envelope.Builder envelope = Envelope.builder(request.topics().get(index), "m" + index, "urn:test", "samples.created");
                String tenant = request.tenants().get(index);
                if (!tenant.isEmpty()) {
                    envelope.extension(Envelope.TENANT_EXTENSION, tenant);
                }
                envelopes.add(envelope.build());
            }
            List<Outcome> results = new ArrayList<>();
            Host.of(context).orElseThrow().publish(context, envelopes).forEach(result -> results.add(Outcome.of(result, "")));
            return Result.ok(new Published(results));
        });
        RunningPlugin plugin = RunningPlugin.start(directory, RunningPlugin.MANIFEST, PluginOptions.builder().operations(registry),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, hostAddress));
        plugin.ready("{}");
        return plugin;
    }

    private String hostAddress() {
        InetSocketAddress address = (InetSocketAddress) server.getListenSockets().get(0);
        String host = address.getAddress().getHostAddress();
        return "tcp " + (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    private static <T> T call(RunningPlugin plugin, String operation, Object request, String tenant, Class<T> type) throws IOException {
        PluginProto.InvocationMetadata.Builder metadata = PluginProto.InvocationMetadata.newBuilder();
        if (tenant != null) {
            metadata.putBaggage(BaggageKeys.TENANT, tenant);
        }
        PluginProto.InvokeResponse response = plugin.stub().invoke(PluginProto.InvokeRequest.newBuilder().setOperation(operation)
                .setPayload(ByteString.copyFrom(JSON.writeValueAsBytes(request))).setMetadata(metadata).build());
        assertTrue(response.getStatus().getOk(), response.toString());
        return JSON.readValue(response.getPayload().toByteArray(), type);
    }

    @Test
    void invokesTheHostWithTheSessionTokenAndTheHandedTenant() throws Exception {
        try (RunningPlugin plugin = start(hostAddress())) {
            Outcome relayed = call(plugin, "samples.relay", new Relay("orders.create", null), "t1", Outcome.class);
            assertEquals(new Outcome(true, "", "", "{\"echo\":\"orders.create\"}"), relayed);
            assertEquals(List.of(RunningPlugin.TOKEN), sessions, "every callback carries the session token");
            assertEquals(List.of("t1"), tenants, "the tenant the host handed to the plugin goes back with the callback");

            // 自报宿主没有交给插件的租户：本地拒绝，不发给宿主（ADR-0195）
            Outcome other = call(plugin, "samples.relay", new Relay("orders.create", "t2"), "t1", Outcome.class);
            assertEquals(new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED, ""), other);
            Outcome background = call(plugin, "samples.relay", new Relay("orders.create", "t1"), null, Outcome.class);
            assertEquals(PluginErrors.CALLBACK_DENIED, background.code(), "a call without a tenant cannot add one");
            assertEquals(1, sessions.size());
        }
    }

    @Test
    void mapsTransportFailuresAndHostErrors() throws Exception {
        try (RunningPlugin plugin = start(hostAddress())) {
            assertEquals(new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED, ""),
                    call(plugin, "samples.relay", new Relay("host.denied", null), null, Outcome.class));
            assertEquals(new Outcome(false, "KERNEL_NOTFOUND_001", "kernel.operation.unknown", ""),
                    call(plugin, "samples.relay", new Relay("host.missing", null), null, Outcome.class));
            assertEquals(new Outcome(false, "ORDERS_VALIDATION_001", "orders.quantity.invalid", ""),
                    call(plugin, "samples.relay", new Relay("host.failing", null), null, Outcome.class), "business failures come back as they are");
            assertEquals(new Outcome(false, PluginErrors.UNEXPECTED, PluginMessages.FAILED, ""),
                    call(plugin, "samples.relay", new Relay("host.malformed", null), null, Outcome.class), "an error with duplicate details members is rejected");
        }
    }

    @Test
    void publishesInOneBatchWithResultsInOrder() throws Exception {
        try (RunningPlugin plugin = start(hostAddress())) {
            Published published = call(plugin, "samples.publish", new Publish(List.of("samples.events", "", "samples.events"), List.of("", "", "t9")), "t1",
                    Published.class);
            assertEquals(List.of(
                    new Outcome(true, "", "", ""),
                    new Outcome(false, "MESSAGING_VALIDATION_002", "messaging.envelope.invalid", ""),
                    new Outcome(false, PluginErrors.CALLBACK_DENIED, PluginMessages.CALLBACK_TENANT_DENIED, "")), published.results());
            assertEquals(1, sessions.size(), "the envelopes go to the host in one call");
            Published nothing = call(plugin, "samples.publish", new Publish(List.of(), List.of()), null, Published.class);
            assertEquals(List.of(), nothing.results());
            assertEquals(1, sessions.size(), "an empty batch does not reach the host");
        }
    }

    @Test
    void unreachableOrUnsupportedHostEndpointsAreUnavailable() throws Exception {
        String unreachable = hostAddress();
        server.shutdownNow().awaitTermination();
        try (RunningPlugin plugin = start(unreachable)) {
            Outcome outcome = call(plugin, "samples.relay", new Relay("orders.create", null), null, Outcome.class);
            assertEquals(new Outcome(false, PluginErrors.UNAVAILABLE, PluginMessages.HOST_UNAVAILABLE, ""), outcome);
        }
        try (RunningPlugin plugin = start("npipe \\\\.\\pipe\\xihan-host")) {
            Outcome outcome = call(plugin, "samples.relay", new Relay("orders.create", null), null, Outcome.class);
            assertEquals(new Outcome(false, PluginErrors.UNAVAILABLE, PluginMessages.HOST_UNAVAILABLE, ""), outcome);
        }
    }

    @Test
    void protocolErrorsFollowTheWireEncoding() throws Exception {
        Wire wire = new Wire("xihan.tests.sample", List.of(), "de-DE");
        Error error = Error.unexpected("SAMPLES_UNEXPECTED_001", "samples.failed",
                Map.of("count", 3, "big", 9007199254740993L, "ratio", 2.0, "nan", Double.NaN, "nested", Map.of("list", List.of(1, 1.5))),
                Error.literal("SAMPLES_VALIDATION_002", "inner text"));
        PluginProto.Error encoded = wire.error(error);
        assertEquals("{\"big\":9007199254740993,\"count\":3,\"nan\":\"NaN\",\"nested\":{\"list\":[1,1.5]},\"ratio\":2.0}", encoded.getDetails());
        assertEquals("samples.failed", encoded.getMessageKey());
        assertFalse(encoded.getText().isEmpty(), "the wire message is required");
        assertEquals("inner text", encoded.getInner().getText());
        assertFalse(encoded.getInner().hasInner());

        Error restored = Wire.fromError(encoded);
        assertEquals("SAMPLES_UNEXPECTED_001", restored.code());
        assertEquals(9007199254740993L, ((Number) restored.details().get("big")).longValue(), "64-bit integers round-trip exactly");
        assertEquals(2.0, restored.details().get("ratio"));
        assertEquals("NaN", restored.details().get("nan"));
        assertEquals("SAMPLES_VALIDATION_002", restored.inner().orElseThrow().code());

        // 文案按插件进程的区域与内置目录解析：de-DE 的内置模板
        PluginProto.Error unavailable = wire.error(wire.unavailable(com.xihanfun.framework.primitives.LifecycleState.DRAINING));
        assertEquals("Plugin 'xihan.tests.sample' ist nicht verfügbar (Zustand: Draining).", unavailable.getText());
        JsonNode details = JSON.readTree(unavailable.getDetails());
        assertEquals("Draining", details.get("state").asText());
    }
}
