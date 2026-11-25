package com.xihanfun.framework.plugin;

import static com.xihanfun.framework.plugin.RunningPlugin.details;
import static com.xihanfun.framework.plugin.RunningPlugin.invokeRequest;
import static com.xihanfun.framework.plugin.RunningPlugin.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 调用与投递（{@code invocation.md} §1、§2、§4）：核对顺序、上下文的还原、流式调用的第一帧与按（主题，方式，类型）的投递。
 */
class PluginCallsTest {
    @TempDir
    Path directory;

    /** 请求。 */
    record EchoRequest(String text) {
    }

    /** 响应。 */
    record EchoResponse(String text) {
    }

    /** 没有成员的请求。 */
    record Empty() {
    }

    /** 上下文的快照。 */
    record Seen(String traceId, String deadline, Map<String, String> baggage, boolean host) {
    }

    private static OperationRegistry registry() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.echo"), EchoRequest.class, EchoResponse.class,
                (context, request) -> Result.ok(new EchoResponse(request.text())));
        registry.add(OperationContract.of("samples.fail"), Empty.class, Empty.class,
                (context, request) -> Result.fail(Error.validation("SAMPLES_VALIDATION_001", "samples.text.missing", Map.of("field", "text", "limit", 2.0))));
        registry.add(OperationContract.of("samples.context"), Empty.class, Seen.class, (context, request) -> Result.ok(new Seen(context.traceId(),
                context.deadline().map(Instant::toString).orElse(""), context.baggage(), Host.of(context).isPresent())));
        return registry;
    }

    @Test
    void invokeChecksTheDeclarationBeforeTheStateAndRunsThePipeline() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Count", (context, next) -> {
            observed.incrementAndGet();
            return next.invoke(context);
        }), PipelineStage.OBSERVE);
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()).pipeline(pipeline))) {
            plugin.handshake();
            // 操作是否声明先于就绪核对（invocation.md §1）
            StatusRuntimeException early = assertThrows(StatusRuntimeException.class, () -> plugin.stub().invoke(invokeRequest("samples.missing", "{}")));
            assertEquals(Status.Code.UNIMPLEMENTED, early.getStatus().getCode());
            assertUnavailable(plugin.stub().invoke(invokeRequest("samples.echo", "{\"text\":\"hi\"}")).getStatus(), "Resolved");
            assertTrue(plugin.configure("{}").getStatus().getOk());
            assertUnavailable(plugin.stub().invoke(invokeRequest("samples.echo", "{\"text\":\"hi\"}")).getStatus(), "Configured");
            assertEquals(0, observed.get(), "calls that are not served do not reach the pipeline");

            assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
            PluginProto.InvokeResponse echoed = plugin.stub().invoke(invokeRequest("samples.echo", "{\"text\":\"hi\"}"));
            assertTrue(echoed.getStatus().getOk());
            assertEquals("{\"text\":\"hi\"}", echoed.getPayload().toStringUtf8());
            assertEquals(1, observed.get(), "the call goes through the plugin's pipeline");

            // 空载荷等同 {}（invocation.md §1）：text 不是必填成员，读成 null
            PluginProto.InvokeResponse empty = plugin.stub().invoke(PluginProto.InvokeRequest.newBuilder().setOperation("samples.echo").build());
            assertTrue(empty.getStatus().getOk(), empty.toString());
            assertEquals("{\"text\":null}", empty.getPayload().toStringUtf8());

            PluginProto.InvokeResponse invalid = plugin.stub().invoke(invokeRequest("samples.echo", "{\"text\":1}"));
            assertEquals("KERNEL_VALIDATION_001", invalid.getStatus().getError().getCode());
            assertEquals("{\"id\":\"samples.echo\",\"reason\":\"$.text\"}", invalid.getStatus().getError().getDetails());

            PluginProto.InvokeResponse failed = plugin.stub().invoke(invokeRequest("samples.fail", "{}"));
            assertFalse(failed.getStatus().getOk());
            assertTrue(failed.getPayload().isEmpty());
            PluginProto.Error error = failed.getStatus().getError();
            assertEquals("SAMPLES_VALIDATION_001", error.getCode());
            assertEquals("samples.text.missing", error.getMessageKey());
            assertEquals("", error.getText(), "an error with a message key leaves text empty: the host translates it (invocation.md §3)");
            assertEquals("{\"field\":\"text\",\"limit\":2.0}", error.getDetails(), "details follow the value model");

            StatusRuntimeException unknown = assertThrows(StatusRuntimeException.class, () -> plugin.stub().invoke(invokeRequest("samples.missing", "{}")));
            assertEquals(Status.Code.UNIMPLEMENTED, unknown.getStatus().getCode());
        }
    }

    private static void assertUnavailable(PluginProto.Status status, String state) {
        assertFalse(status.getOk());
        assertEquals("PLUGINS_UNAVAILABLE_001", status.getError().getCode());
        assertEquals(PluginMessages.UNAVAILABLE, status.getError().getMessageKey());
        assertEquals("", status.getError().getText());
        assertEquals(object("id", "xihan.tests.sample", "state", state), details(status.getError()));
    }

    @Test
    void restoresTheInvocationContextFromTheMetadata() throws Exception {
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()))) {
            plugin.ready("{}");
            // deadline 以别的偏移写出，换算成 UTC；trace_state 不进上下文；只收已登记的 Baggage
            PluginProto.InvocationMetadata metadata = PluginProto.InvocationMetadata.newBuilder()
                    .setTraceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                    .setTraceState("vendor=opaque")
                    .setDeadline("2999-01-01T08:00:00.5+08:00")
                    .putBaggage(BaggageKeys.TENANT, "t1")
                    .putBaggage(BaggageKeys.CORRELATION, "c1")
                    .putBaggage("xihan.unregistered", "dropped")
                    .putBaggage("other", "dropped")
                    .build();
            Seen seen = context(plugin, metadata, null);
            assertEquals("0af7651916cd43dd8448eb211c80319c", seen.traceId());
            assertEquals("2999-01-01T00:00:00.500Z", seen.deadline());
            assertEquals(Map.of(BaggageKeys.TENANT, "t1", BaggageKeys.CORRELATION, "c1"), seen.baggage(), "only registered keys come in");
            assertTrue(seen.host(), "the host client is in the context");

            // 不合文法的 trace_parent 即没有父 Span；读不出的 deadline 当作没有，生效的是 gRPC 截止时间
            Instant before = Instant.now();
            Seen invalid = context(plugin, PluginProto.InvocationMetadata.newBuilder().setTraceParent("00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01")
                    .setDeadline("2999-01-01 00:00:00Z").build(), Duration.ofSeconds(30));
            assertNotEquals("0af7651916cd43dd8448eb211c80319c", invalid.traceId());
            Instant effective = Instant.parse(invalid.deadline());
            assertTrue(!effective.isBefore(before.plusSeconds(29)) && effective.isBefore(Instant.now().plusSeconds(31)), invalid.deadline());

            // 两者都有时取较早的
            Seen earlier = context(plugin, PluginProto.InvocationMetadata.newBuilder().setDeadline("2999-01-01T00:00:00Z").build(), Duration.ofSeconds(30));
            assertTrue(Instant.parse(earlier.deadline()).isBefore(Instant.now().plusSeconds(31)), earlier.deadline());
            Instant soon = Instant.now().plusSeconds(20);
            Seen metadataFirst = context(plugin, PluginProto.InvocationMetadata.newBuilder().setDeadline(Wire.formatDeadline(soon)).build(), Duration.ofSeconds(60));
            assertEquals(soon.toEpochMilli() / 100, Instant.parse(metadataFirst.deadline()).toEpochMilli() / 100, metadataFirst.deadline());

            Seen none = context(plugin, PluginProto.InvocationMetadata.getDefaultInstance(), null);
            assertEquals("", none.deadline(), "neither a deadline nor a gRPC deadline: no deadline");
        }
    }

    private static Seen context(RunningPlugin plugin, PluginProto.InvocationMetadata metadata, Duration grpc) throws Exception {
        var stub = grpc == null ? plugin.stub() : plugin.stub().withDeadlineAfter(grpc.toMillis(), TimeUnit.MILLISECONDS);
        PluginProto.InvokeResponse response = stub.invoke(invokeRequest("samples.context", "{}").toBuilder().setMetadata(metadata).build());
        assertTrue(response.getStatus().getOk(), response.toString());
        return RunningPlugin.JSON.readValue(response.getPayload().toByteArray(), Seen.class);
    }

    @Test
    void streamingCallsEndByTheFirstFrameWhateverTheState() throws Exception {
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()))) {
            plugin.handshake();
            assertTrue(plugin.configure("{}").getStatus().getOk());
            for (int round = 0; round < 2; round++) {
                // 不论操作是否声明、插件是否就绪（invocation.md §1）
                assertEquals(Status.Code.INVALID_ARGUMENT, RunningPlugin.stream(plugin.async(),
                        PluginProto.StreamFrame.newBuilder().setChunk(ByteString.copyFromUtf8("{}")).build()));
                assertEquals(Status.Code.INVALID_ARGUMENT, RunningPlugin.stream(plugin.async(), null), "the stream ended before its first frame");
                assertEquals(Status.Code.UNIMPLEMENTED, RunningPlugin.stream(plugin.async(),
                        PluginProto.StreamFrame.newBuilder().setStart(invokeRequest("samples.echo", "{}")).build()));
                assertEquals(Status.Code.UNIMPLEMENTED, RunningPlugin.stream(plugin.async(),
                        PluginProto.StreamFrame.newBuilder().setStart(invokeRequest("samples.missing", "{}")).build()));
                if (round == 0) {
                    assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
                }
            }
        }
    }

    @Test
    void deliversToEveryMatchingSubscriptionInDeclaredOrder() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        Error rejected = Error.validation("SAMPLES_VALIDATION_002", "samples.event.rejected");
        PluginOptions.Builder options = PluginOptions.builder()
                .subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> {
                    received.add("any:" + envelope.type() + ":" + attempt);
                    return Result.success();
                })
                .subscribe(Subscription.of("samples.events").withMessageType("samples.created"), (context, envelope, attempt) -> {
                    received.add("created:" + context.tenant().orElse(""));
                    return Result.success();
                })
                .subscribe(Subscription.of("samples.events").withMessageType("samples.updated").withMode(SubscriptionMode.BROADCAST),
                        (context, envelope, attempt) -> {
                            received.add("broadcast:" + envelope.dataText());
                            return Result.success();
                        })
                .subscribe(Subscription.of("samples.chain"), (context, envelope, attempt) -> Result.fail(rejected))
                .subscribe(Subscription.of("samples.chain").withMessageType("samples.created"), (context, envelope, attempt) -> {
                    received.add("after-failure");
                    return Result.success();
                })
                .subscribe(Subscription.of("samples.broken"), (context, envelope, attempt) -> {
                    throw new IllegalStateException("boom");
                });
        try (RunningPlugin plugin = RunningPlugin.start(directory, options)) {
            plugin.handshake();
            assertTrue(plugin.configure("{}").getStatus().getOk());
            // 找不到订阅先于就绪核对；找到了但不在可服务状态为 unavailable（invocation.md §4）
            assertUnsubscribed(plugin.deliver("samples.nobody", "x", ""), "samples.nobody", "x", "competing");
            assertUnavailable(plugin.deliver("samples.events", "samples.created", "competing"), "Configured");
            assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());

            assertTrue(plugin.deliver("samples.events", "samples.created", "competing").getOk());
            assertEquals(List.of("any:samples.created:1", "created:t1"), received, "every matching subscription, in declared order");
            received.clear();
            assertTrue(plugin.deliver("samples.events", "samples.created", "").getOk(), "an empty mode is competing");
            assertEquals(List.of("any:samples.created:1", "created:t1"), received);
            received.clear();
            assertTrue(plugin.deliver("samples.events", "samples.updated", "broadcast").getOk());
            assertEquals(List.of("broadcast:{\"n\":1}"), received, "a broadcast delivery goes only to broadcast subscriptions");
            received.clear();
            assertUnsubscribed(plugin.deliver("samples.events", "samples.created", "broadcast"), "samples.events", "samples.created", "broadcast");
            assertUnsubscribed(plugin.deliver("samples.nobody", "x", ""), "samples.nobody", "x", "competing");

            PluginProto.Status chain = plugin.deliver("samples.chain", "samples.created", "competing");
            assertEquals("SAMPLES_VALIDATION_002", chain.getError().getCode(), "the first failure is the result");
            assertEquals(List.of(), received, "the subscriptions after the failure are not called");
            PluginProto.Status broken = plugin.deliver("samples.broken", "x", "competing");
            assertEquals("KERNEL_UNEXPECTED_001", broken.getError().getCode());
            assertEquals("kernel.invocation.unhandled_exception", broken.getError().getMessageKey());

            StatusRuntimeException bogus = assertThrows(StatusRuntimeException.class,
                    () -> plugin.stub().handleEvent(RunningPlugin.eventRequest("samples.events", "x", "bogus")));
            assertEquals(Status.Code.INVALID_ARGUMENT, bogus.getStatus().getCode());
            StatusRuntimeException uppercase = assertThrows(StatusRuntimeException.class,
                    () -> plugin.stub().handleEvent(RunningPlugin.eventRequest("samples.events", "x", "Competing")));
            assertEquals(Status.Code.INVALID_ARGUMENT, uppercase.getStatus().getCode());
            StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                    () -> plugin.stub().handleEvent(RunningPlugin.eventRequest("samples.events", "x", "competing").toBuilder().clearEnvelope().build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, missing.getStatus().getCode());
            // 宿主写来的 time 读不出：不交给作者，也不当作没有时间；先于找订阅（invocation.md §4）
            for (String topic : List.of("samples.events", "samples.nobody")) {
                StatusRuntimeException badTime = assertThrows(StatusRuntimeException.class, () -> plugin.stub()
                        .handleEvent(withEnvelope(RunningPlugin.eventRequest(topic, "x", "competing"), "2026-11-25T03:25:00", "")));
                assertEquals(Status.Code.INVALID_ARGUMENT, badTime.getStatus().getCode(), topic);
            }
            assertEquals(List.of(), received, "an envelope whose time cannot be read reaches no handler");
        }
    }

    @Test
    void deliveredEnvelopesCarryTheTimeInUtcAndTheDataSchemaAsIs() throws Exception {
        List<Envelope> received = new CopyOnWriteArrayList<>();
        PluginOptions.Builder options = PluginOptions.builder().subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> {
            received.add(envelope);
            return Result.success();
        });
        try (RunningPlugin plugin = RunningPlugin.start(directory, options)) {
            plugin.handshake();
            assertTrue(plugin.configure("{}").getStatus().getOk());
            assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
            PluginProto.HandleEventRequest request = RunningPlugin.eventRequest("samples.events", "samples.created", "competing");
            String schema = " https://example.com/schemas/order.json ";
            assertTrue(plugin.stub().handleEvent(withEnvelope(request, "2026-11-25T11:25:00.1234567+08:00", schema)).getStatus().getOk());
            assertTrue(plugin.stub().handleEvent(request).getStatus().getOk());
        }
        assertEquals(2, received.size());
        // 换算成 UTC；dataschema 原样、不裁剪；空串是没有
        assertEquals(Optional.of(Instant.parse("2026-11-25T03:25:00.1234567Z")), received.get(0).time());
        assertEquals(Optional.of(" https://example.com/schemas/order.json "), received.get(0).dataSchema());
        assertEquals(Optional.empty(), received.get(1).time());
        assertEquals(Optional.empty(), received.get(1).dataSchema());
    }

    private static PluginProto.HandleEventRequest withEnvelope(PluginProto.HandleEventRequest request, String time, String dataSchema) {
        return request.toBuilder().setEnvelope(request.getEnvelope().toBuilder().setTime(time).setDataSchema(dataSchema)).build();
    }

    private static void assertUnsubscribed(PluginProto.Status status, String topic, String type, String mode) {
        assertFalse(status.getOk());
        assertEquals("KERNEL_NOTFOUND_001", status.getError().getCode());
        assertEquals(PluginMessages.EVENT_UNSUBSCRIBED, status.getError().getMessageKey());
        assertEquals("", status.getError().getText());
        assertEquals(object("id", "xihan.tests.sample", "topic", topic, "type", type, "mode", mode), details(status.getError()));
    }
}
