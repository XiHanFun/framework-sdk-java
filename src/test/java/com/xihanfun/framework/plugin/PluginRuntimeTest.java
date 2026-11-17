package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.operation.Expose;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.operation.OperationSchema;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.Unit;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件运行时：以宿主的身份连上本进程里启动的运行时，按 {@code handshake.md}、{@code lifecycle.md}、{@code invocation.md} 核对每个 RPC。
 */
class PluginRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** 配置节。 */
    record Settings(String greeting, int count, boolean enabled) {
    }

    private static OperationRegistry registry() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.echo").withStability(Stability.PREVIEW).withExpose(Expose.MCP, Expose.HTTP).withPermission("samples.read"),
                EchoRequest.class, EchoResponse.class, (context, request) -> Result.ok(new EchoResponse(request.text())),
                OperationSchema.of("{\"type\":\"object\"}", "{\"type\":\"object\",\"required\":[\"text\"]}"), "xihan.tests.sample");
        registry.add(OperationContract.of("samples.fail"), Empty.class, Empty.class,
                (context, request) -> Result.fail(Error.validation("SAMPLES_VALIDATION_001", "samples.text.missing", Map.of("field", "text", "limit", 2.0))));
        registry.add(OperationContract.of("samples.context"), Empty.class, Seen.class, (context, request) -> Result.ok(new Seen(context.traceId(),
                context.deadline().map(Instant::toString).orElse(""), context.baggage(), Host.of(context).isPresent())));
        return registry;
    }

    @Test
    void writesOneHandshakeLineAndRejectsCallsWithoutTheSessionToken() throws Exception {
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()))) {
            String expected = Transport.unixSupported() ? "unix" : "tcp";
            assertTrue(plugin.line.startsWith("xihan-plugin/1 " + expected + " "), plugin.line);
            if (expected.equals("tcp")) {
                assertTrue(plugin.line.matches("xihan-plugin/1 tcp 127\\.0\\.0\\.1:[0-9]+"), "tcp binds only the loopback address: " + plugin.line);
            }
            for (String token : new String[] {null, "not-the-session-token", RunningPlugin.TOKEN + "x"}) {
                StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, () -> plugin.stub(token).health(PluginProto.HealthRequest.getDefaultInstance()));
                assertEquals(Status.Code.UNAUTHENTICATED, refused.getStatus().getCode());
            }
            // 令牌不对的握手不执行：状态仍是 Declared，带对令牌照常握手
            assertThrows(StatusRuntimeException.class, () -> plugin.stub("wrong").handshake(PluginProto.HandshakeRequest.newBuilder().addProtocolVersions(1).build()));
            assertEquals(LifecycleState.DECLARED, plugin.runtime.lifecycle.state());
            assertEquals(1, plugin.handshake().getProtocolVersion());
        }
    }

    @Test
    void handshakeDeclaresTheManifestOperationsSubscriptionsAndCatalogs() throws Exception {
        PluginCatalog catalog = PluginCatalog.of("samples", Map.of("en-US", Map.of("samples.text.missing", "The {field} is required."),
                "zh-CN", Map.of("samples.text.missing", "{field} 不能为空。")));
        PluginOptions.Builder options = PluginOptions.builder().operations(registry())
                .subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> Result.success())
                .subscribe(Subscription.of("samples.events").withMessageType("samples.created").withMode(SubscriptionMode.BROADCAST),
                        (context, envelope, attempt) -> Result.success())
                .catalog(catalog);
        try (RunningPlugin plugin = RunningPlugin.start(directory, options)) {
            PluginProto.HandshakeResponse response = plugin.handshake();
            assertEquals(1, response.getProtocolVersion());
            assertEquals(1, response.getPrimitives());
            assertEquals(RunningPlugin.MANIFEST, response.getManifest(), "the manifest is the file text, unchanged");
            assertEquals("java", response.getSdk().getLanguage());
            assertEquals(List.of("samples.context", "samples.echo", "samples.fail"),
                    response.getOperationsList().stream().map(PluginProto.OperationDeclaration::getId).toList());
            PluginProto.OperationDeclaration echo = response.getOperations(1);
            assertEquals("preview", echo.getStability());
            assertEquals(List.of("http", "mcp"), echo.getExposeList());
            assertEquals("samples.read", echo.getPermission());
            assertEquals("{\"type\":\"object\"}", echo.getRequestSchema());
            assertFalse(echo.getStreaming());
            assertEquals(2, response.getSubscriptionsCount());
            assertEquals("competing", response.getSubscriptions(0).getMode());
            assertEquals("", response.getSubscriptions(0).getMessageType());
            assertEquals("broadcast", response.getSubscriptions(1).getMode());
            assertEquals("samples.created", response.getSubscriptions(1).getMessageType());
            assertEquals("samples", response.getCatalogs(0).getName());
            assertEquals(List.of("en-US", "zh-CN"), response.getCatalogs(0).getLocalesList().stream().map(PluginProto.LocaleTemplates::getLocale).toList());
            assertEquals(LifecycleState.RESOLVED, plugin.runtime.lifecycle.state());

            StatusRuntimeException again = assertThrows(StatusRuntimeException.class, plugin::handshake);
            assertEquals(Status.Code.FAILED_PRECONDITION, again.getStatus().getCode());
        }
    }

    @Test
    void negotiatesTheProtocolVersionByTheVectors() throws Exception {
        // protocol-versions.json：插件选宿主列表里最高的自己也支持的版本，没有交集即失败（handshake.md §3 第 1 条）
        for (JsonNode vector : Vectors.read("plugin/protocol-versions.json").get("cases")) {
            List<Integer> plugin = new ArrayList<>();
            vector.get("plugin").forEach(version -> plugin.add(version.intValue()));
            if (!plugin.equals(List.of(PluginRuntime.PROTOCOL_VERSION))) {
                continue;
            }
            List<Integer> host = new ArrayList<>();
            vector.get("host").forEach(version -> host.add(version.intValue()));
            Optional<Integer> selected = PluginRuntime.negotiate(host);
            if (vector.has("selected")) {
                assertEquals(Optional.of(vector.get("selected").intValue()), selected, vector.toString());
            } else {
                assertEquals(Optional.empty(), selected, vector.toString());
            }
        }
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder())) {
            StatusRuntimeException refused = assertThrows(StatusRuntimeException.class,
                    () -> plugin.stub().handshake(PluginProto.HandshakeRequest.newBuilder().addProtocolVersions(2).addProtocolVersions(3).build()));
            assertEquals(Status.Code.FAILED_PRECONDITION, refused.getStatus().getCode());
            assertEquals(LifecycleState.DECLARED, plugin.runtime.lifecycle.state());
            assertEquals(1, plugin.handshake().getProtocolVersion());
        }
    }

    @Test
    void configuresStartsReportsHealthAndStops() throws Exception {
        AtomicReference<PluginConfiguration> configured = new AtomicReference<>();
        AtomicReference<String> stopReason = new AtomicReference<>();
        PluginHooks hooks = new PluginHooks() {
            @Override
            public Result<Unit> configure(Context context, PluginConfiguration configuration) {
                configured.set(configuration);
                return Result.success();
            }

            @Override
            public Result<Unit> stop(Context context, String reason, Duration grace) {
                stopReason.set(reason);
                return Result.success();
            }

            @Override
            public Optional<PluginHealth> health() {
                return Optional.of(PluginHealth.of(LifecycleState.READY, HealthItem.of("sample", HealthStatus.HEALTHY),
                        HealthItem.of("undeclared", HealthStatus.UNHEALTHY)));
            }
        };
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks));
        try {
            plugin.handshake();
            StatusRuntimeException early = assertThrows(StatusRuntimeException.class, () -> plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()));
            assertEquals(Status.Code.FAILED_PRECONDITION, early.getStatus().getCode(), "Start requires Configure");
            PluginProto.ConfigureResponse response = plugin.stub().configure(PluginProto.ConfigureRequest.newBuilder()
                    .setConfiguration("{\"Greeting\":\"hi\",\"count\":\"7\",\"enabled\":\"true\",\"extra\":1}")
                    .addGrantedPermissions("capability:kernel.pipeline")
                    .addBindings(PluginProto.Binding.newBuilder().setName("main").setType("connection:relational").setValue("secret"))
                    .setWorkDirectory(directory.toString())
                    .build());
            assertTrue(response.getStatus().getOk());
            // 配置源的值是字符串：数字与布尔从字符串读出，成员名不区分大小写，未知成员忽略（lifecycle.md §2）
            assertEquals(new Settings("hi", 7, true), configured.get().decode(Settings.class));
            assertEquals(List.of("capability:kernel.pipeline"), configured.get().grantedPermissions());
            assertEquals(Optional.of(new ResolvedBinding("main", "connection:relational", "secret")), configured.get().findBinding("main"));
            assertEquals(Optional.of(directory), configured.get().workDirectory());
            assertFalse(configured.get().toString().contains("secret"), "connection strings stay out of logs");

            assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
            PluginProto.HealthResponse health = plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, health.getState());
            assertEquals(List.of("sample"), health.getItemsList().stream().map(PluginProto.HealthItem::getName).toList(),
                    "undeclared health items are dropped");
            assertTrue(plugin.logs().contains("\"item\":\"undeclared\""), plugin.logs());

            Iterator<PluginProto.HealthResponse> watch = plugin.stub().withDeadlineAfter(10, TimeUnit.SECONDS).watchHealth(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, watch.next().getState(), "WatchHealth pushes the current state first");

            PluginProto.StopResponse stopped = plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(2).setReason("unload").build());
            assertTrue(stopped.getStatus().getOk());
            assertEquals("unload", stopReason.get());
            // 开始停止时推最后一条 Draining 并结束长流
            List<PluginProto.LifecycleState> rest = new ArrayList<>();
            watch.forEachRemaining(next -> rest.add(next.getState()));
            assertEquals(List.of(PluginProto.LifecycleState.LIFECYCLE_STATE_DRAINING), rest);
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)), "Stop ends serve");
            assertEquals(LifecycleState.STOPPED, plugin.runtime.lifecycle.state());
        } finally {
            plugin.close();
        }
    }

    @Test
    void failedHooksMoveThePluginToFailedWithAnUnexpectedError() throws Exception {
        PluginHooks hooks = new PluginHooks() {
            @Override
            public Result<Unit> configure(Context context, PluginConfiguration configuration) {
                throw new IllegalStateException("boom");
            }
        };
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks))) {
            plugin.handshake();
            PluginProto.Status status = plugin.stub().configure(PluginProto.ConfigureRequest.getDefaultInstance()).getStatus();
            assertFalse(status.getOk());
            assertEquals("PLUGINS_UNEXPECTED_001", status.getError().getCode());
            assertEquals(PluginMessages.FAILED, status.getError().getMessageKey());
            assertEquals("Plugin 'xihan.tests.sample' failed during configure: java.lang.IllegalStateException", status.getError().getText());
            assertEquals("{\"id\":\"xihan.tests.sample\",\"phase\":\"configure\",\"reason\":\"java.lang.IllegalStateException\"}", status.getError().getDetails());
            PluginProto.HealthResponse health = plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_FAILED, health.getState());
            assertEquals("PLUGINS_UNEXPECTED_001", health.getError().getCode());
            assertTrue(plugin.logs().contains("\"level\":\"error\""), plugin.logs());
        }
    }

    @Test
    void invokesOperationsThroughTheInvokerAndThePluginPipeline() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Count", (context, next) -> {
            observed.incrementAndGet();
            return next.invoke(context);
        }), PipelineStage.OBSERVE);
        PluginCatalog catalog = PluginCatalog.of("samples", Map.of("en-US", Map.of("samples.text.missing", "The {field} is required (limit {limit:F1}).")));
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()).pipeline(pipeline).catalog(catalog))) {
            plugin.handshake();
            PluginProto.InvokeResponse early = plugin.stub().invoke(invoke("samples.echo", "{\"text\":\"hi\"}"));
            assertEquals("PLUGINS_UNAVAILABLE_001", early.getStatus().getError().getCode(), "calls before Start are unavailable");
            assertEquals("Plugin 'xihan.tests.sample' is not available (state: Resolved).", early.getStatus().getError().getText());

            plugin.configureAndStart("{}");
            PluginProto.InvokeResponse echoed = plugin.stub().invoke(invoke("samples.echo", "{\"text\":\"hi\"}"));
            assertTrue(echoed.getStatus().getOk());
            assertEquals("{\"text\":\"hi\"}", echoed.getPayload().toStringUtf8());
            assertEquals(1, observed.get(), "the call goes through the plugin's pipeline");

            // 空载荷等价于 {}（invocation.md §1）：text 不是必填成员，读成 null
            PluginProto.InvokeResponse empty = plugin.stub().invoke(PluginProto.InvokeRequest.newBuilder().setOperation("samples.echo").build());
            assertTrue(empty.getStatus().getOk(), empty.toString());
            assertEquals("{\"text\":null}", empty.getPayload().toStringUtf8());

            PluginProto.InvokeResponse invalid = plugin.stub().invoke(invoke("samples.echo", "{\"text\":1}"));
            assertEquals("KERNEL_VALIDATION_001", invalid.getStatus().getError().getCode());
            assertEquals("{\"id\":\"samples.echo\",\"reason\":\"$.text\"}", invalid.getStatus().getError().getDetails());

            PluginProto.InvokeResponse failed = plugin.stub().invoke(invoke("samples.fail", "{}"));
            assertFalse(failed.getStatus().getOk());
            assertTrue(failed.getPayload().isEmpty());
            PluginProto.Error error = failed.getStatus().getError();
            assertEquals("SAMPLES_VALIDATION_001", error.getCode());
            assertEquals("samples.text.missing", error.getMessageKey());
            assertEquals("The text is required (limit 2.0).", error.getText(), "the plugin's own catalog resolves the text");
            assertEquals("{\"field\":\"text\",\"limit\":2.0}", error.getDetails(), "details follow the value model");

            StatusRuntimeException unknown = assertThrows(StatusRuntimeException.class, () -> plugin.stub().invoke(invoke("samples.missing", "{}")));
            assertEquals(Status.Code.UNIMPLEMENTED, unknown.getStatus().getCode());
        }
    }

    @Test
    void restoresTheInvocationContextFromTheMetadata() throws Exception {
        try (RunningPlugin plugin = RunningPlugin.start(directory, RunningPlugin.MANIFEST, PluginOptions.builder().operations(registry()),
                Map.of(PluginRuntime.ENV_HOST_ADDRESS, "tcp 127.0.0.1:1"))) {
            plugin.ready("{}");
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30)).truncatedTo(ChronoUnit.MILLIS);
            PluginProto.InvocationMetadata metadata = PluginProto.InvocationMetadata.newBuilder()
                    .setTraceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
                    .setDeadline(deadline.toString())
                    .putBaggage(BaggageKeys.TENANT, "t1")
                    .putBaggage(BaggageKeys.CORRELATION, "c1")
                    .putBaggage("xihan.unregistered", "dropped")
                    .putBaggage("other", "dropped")
                    .build();
            PluginProto.InvokeResponse response = plugin.stub().invoke(invoke("samples.context", "{}").toBuilder().setMetadata(metadata).build());
            assertTrue(response.getStatus().getOk(), response.toString());
            Seen seen = JSON.readValue(response.getPayload().toByteArray(), Seen.class);
            assertEquals("0af7651916cd43dd8448eb211c80319c", seen.traceId());
            assertEquals(deadline.toString(), seen.deadline());
            assertEquals(Map.of(BaggageKeys.TENANT, "t1", BaggageKeys.CORRELATION, "c1"), seen.baggage(), "only registered keys come in");
            assertTrue(seen.host(), "the host client is in the context");
        }
    }

    @Test
    void streamingCallsCheckTheFrameOrder() throws Exception {
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()))) {
            plugin.ready("{}");
            assertEquals(Status.Code.INVALID_ARGUMENT, stream(plugin, PluginProto.StreamFrame.newBuilder().setChunk(ByteString.copyFromUtf8("{}")).build()));
            assertEquals(Status.Code.UNIMPLEMENTED, stream(plugin, PluginProto.StreamFrame.newBuilder().setStart(invoke("samples.echo", "{}")).build()),
                    "unary operations are not served by InvokeStream");
        }
    }

    @Test
    void deliversEventsByTopicModeAndType() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        PluginOptions.Builder options = PluginOptions.builder()
                .subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> {
                    received.add("competing:" + envelope.type() + ":" + attempt);
                    return Result.success();
                })
                .subscribe(Subscription.of("samples.events").withMessageType("samples.created").withMode(SubscriptionMode.BROADCAST),
                        (context, envelope, attempt) -> {
                            received.add("broadcast:" + envelope.dataText() + ":" + context.tenant().orElse(""));
                            return Result.success();
                        })
                .subscribe(Subscription.of("samples.broken"), (context, envelope, attempt) -> {
                    throw new IllegalStateException("boom");
                });
        try (RunningPlugin plugin = RunningPlugin.start(directory, options)) {
            plugin.ready("{}");
            assertTrue(deliver(plugin, "samples.events", "samples.created", "competing").getOk());
            assertTrue(deliver(plugin, "samples.events", "samples.created", "").getOk(), "an empty mode is competing");
            assertTrue(deliver(plugin, "samples.events", "samples.created", "broadcast").getOk());
            PluginProto.Status missing = deliver(plugin, "samples.events", "samples.deleted", "broadcast");
            assertFalse(missing.getOk(), "a broadcast delivery is not handed to the competing handler");
            assertEquals("KERNEL_NOTFOUND_001", missing.getError().getCode());
            assertEquals(PluginMessages.EVENT_UNSUBSCRIBED, missing.getError().getMessageKey());
            assertEquals("Plugin 'xihan.tests.sample' has no broadcast subscription to topic 'samples.events' for message type 'samples.deleted'.",
                    missing.getError().getText());
            assertEquals("KERNEL_UNEXPECTED_001", deliver(plugin, "samples.broken", "x", "competing").getError().getCode());
            assertEquals(List.of("competing:samples.created:1", "competing:samples.created:1", "broadcast:{\"n\":1}:t1"), received);
        }
    }

    @Test
    void stopsWhenStdinCloses() throws Exception {
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder());
        try {
            plugin.ready("{}");
            plugin.closeStdin();
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
            assertEquals(LifecycleState.STOPPED, plugin.runtime.lifecycle.state());
            assertTrue(plugin.logs().contains("stdin closed"), plugin.logs());
        } finally {
            plugin.close();
        }
    }

    @Test
    void stopWaitsForInFlightCallsAndRejectsNewOnes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.wait"), Empty.class, Empty.class, (context, request) -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Result.ok(request);
        });
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry));
        try {
            plugin.ready("{}");
            AtomicReference<PluginProto.InvokeResponse> slow = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> slow.set(plugin.stub().invoke(invoke("samples.wait", "{}"))));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            AtomicReference<PluginProto.StopResponse> stopped = new AtomicReference<>();
            Thread stopper = Thread.ofVirtual().start(() -> stopped.set(plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(5).build())));
            // Stop 先进入 Draining，再等在途的调用；此时到达的调用不执行
            assertTrue(plugin.runtime.lifecycle.awaitState(LifecycleState.DRAINING, Duration.ofSeconds(10)));
            assertEquals("PLUGINS_UNAVAILABLE_001", plugin.stub().invoke(invoke("samples.wait", "{}")).getStatus().getError().getCode());
            assertEquals(null, stopped.get(), "Stop returns after the in-flight call");
            release.countDown();
            caller.join();
            stopper.join();
            assertTrue(slow.get().getStatus().getOk(), "the in-flight call completes");
            assertTrue(stopped.get().getStatus().getOk());
        } finally {
            plugin.close();
        }
    }

    @Test
    void refusesMisconfiguredPluginsBeforeListening() throws Exception {
        assertEquals(PluginErrors.INVALID_SOURCE, failure(RunningPlugin.MANIFEST.replace("\"kind\": \"plugin\"", "\"kind\": \"provider\"")
                .replace("  \"isolation\": \"out-of-process\",\n", "").replace("  \"permissions\": [ \"capability:kernel.pipeline\", \"capability:messaging.queue\" ],\n", ""),
                PluginOptions.builder(), Map.of()));
        assertEquals(PluginErrors.INVALID_SOURCE, failure(RunningPlugin.MANIFEST.replace("\"health\": [ \"sample\" ]",
                "\"health\": [ \"sample\" ], \"pipeline\": [ { \"middleware\": \"Audit\", \"stage\": \"observe\" } ]"), PluginOptions.builder(), Map.of()));
        assertEquals(PluginErrors.INVALID_SOURCE, failure(RunningPlugin.MANIFEST.replace("  \"entry\": \"sample\"\n", "  \"activation\": [ \"onStartup\" ]\n"),
                PluginOptions.builder(), Map.of()));
        OperationRegistry undeclared = new OperationRegistry();
        undeclared.add(OperationContract.of("samples.undeclared"), Empty.class, Empty.class, (context, request) -> Result.ok(request));
        assertEquals(PluginErrors.HANDSHAKE_MISMATCH, failure(RunningPlugin.MANIFEST, PluginOptions.builder().operations(undeclared), Map.of()));
        PluginOptions.Builder twice = PluginOptions.builder()
                .subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> Result.success())
                .subscribe(Subscription.of("samples.events").withMode(SubscriptionMode.BROADCAST), (context, envelope, attempt) -> Result.success());
        assertEquals(PluginErrors.HANDSHAKE_MISMATCH, failure(RunningPlugin.MANIFEST, twice, Map.of()), "one (topic, type) pair cannot use two modes");
        assertEquals("PRIMITIVES_VALIDATION_001", failure(RunningPlugin.MANIFEST, PluginOptions.builder(), Map.of(PluginRuntime.ENV_SESSION, "")));
        assertEquals("PRIMITIVES_VALIDATION_001", failure(RunningPlugin.MANIFEST, PluginOptions.builder(), Map.of(PluginRuntime.ENV_PROTOCOL_VERSIONS, "1,x")));
        assertEquals("PRIMITIVES_VALIDATION_001", failure(RunningPlugin.MANIFEST, PluginOptions.builder(), Map.of(PluginRuntime.ENV_HOST_ADDRESS, "pipe")));
        assertThrows(IllegalArgumentException.class, () -> Subscription.of("Bad Topic"));
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.builder().network(Network.NPIPE));
    }

    private String failure(String manifest, PluginOptions.Builder options, Map<String, String> environment) throws Exception {
        Path file = directory.resolve("xihan.manifest.json");
        Files.writeString(file, manifest, StandardCharsets.UTF_8);
        Map<String, String> variables = new java.util.HashMap<>(Map.of(PluginRuntime.ENV_SESSION, RunningPlugin.TOKEN, PluginRuntime.ENV_PROTOCOL_VERSIONS, "1"));
        variables.putAll(environment);
        FrameworkException failed = assertThrows(FrameworkException.class, () -> PluginRuntime.start(options.manifest(file).environment(variables)
                .streams(java.io.InputStream.nullInputStream(), java.io.OutputStream.nullOutputStream(), java.io.OutputStream.nullOutputStream())
                .bridgePlatformLogging(false).build()));
        return failed.code();
    }

    private static PluginProto.InvokeRequest invoke(String operation, String payload) {
        return PluginProto.InvokeRequest.newBuilder().setOperation(operation).setPayload(ByteString.copyFromUtf8(payload)).build();
    }

    private static PluginProto.Status deliver(RunningPlugin plugin, String topic, String type, String mode) {
        return plugin.stub().handleEvent(PluginProto.HandleEventRequest.newBuilder()
                .setEnvelope(PluginProto.Envelope.newBuilder().setTopic(topic).setId("e1").setSource("urn:test").setType(type).setSpecVersion("1.0")
                        .setData(ByteString.copyFromUtf8("{\"n\":1}")))
                .setMetadata(PluginProto.InvocationMetadata.newBuilder().putBaggage(BaggageKeys.TENANT, "t1"))
                .setAttempt(1)
                .setMode(mode)
                .build()).getStatus();
    }

    /** 发一帧，返回流的结局。 */
    private static Status.Code stream(RunningPlugin plugin, PluginProto.StreamFrame first) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Status.Code> outcome = new AtomicReference<>();
        StreamObserver<PluginProto.StreamFrame> requests = plugin.async().invokeStream(new StreamObserver<>() {
            @Override
            public void onNext(PluginProto.StreamFrame frame) {
            }

            @Override
            public void onError(Throwable failure) {
                outcome.set(Status.fromThrowable(failure).getCode());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                outcome.set(Status.Code.OK);
                done.countDown();
            }
        });
        requests.onNext(first);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        requests.onCompleted();
        return outcome.get();
    }
}
