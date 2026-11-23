package com.xihanfun.framework.plugin;

import static com.xihanfun.framework.plugin.RunningPlugin.details;
import static com.xihanfun.framework.plugin.RunningPlugin.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.internal.Whitespace;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.operation.Expose;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.operation.OperationSchema;
import com.xihanfun.framework.plugin.v1.PluginGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.Unit;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件运行时的握手、次序、配置、启动、健康与启动前的核对：以宿主的身份连上本进程里启动的运行时，按 {@code handshake.md}、
 * {@code lifecycle.md} §7 核对每个 RPC。
 */
class PluginRuntimeTest {
    @TempDir
    Path directory;

    /** 请求。 */
    record EchoRequest(String text) {
    }

    /** 响应。 */
    record EchoResponse(String text) {
    }

    /** 配置节。 */
    record Settings(String greeting, int count, boolean enabled) {
    }

    /** 各种数字与布尔的配置节。 */
    record Typed(int count, long big, double ratio, boolean enabled, Boolean flag, BigDecimal amount) {
    }

    /** 只有一个整数的配置节。 */
    record Count(int count) {
    }

    /** 只有一个布尔的配置节。 */
    record Flag(boolean flag) {
    }

    /** 成员名匹配用的配置节。 */
    record Keyed(String key, int count) {
    }

    /** 属性名是非 ASCII 字母的配置节。 */
    record Unicode(String été) {
    }

    /** 嵌套的配置节。 */
    record Nested(Keyed inner, List<Keyed> list, Map<String, Keyed> map, Keyed[] array) {
    }

    private static OperationRegistry registry() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.echo").withStability(Stability.PREVIEW).withExpose(Expose.MCP, Expose.HTTP).withPermission("samples.read"),
                EchoRequest.class, EchoResponse.class, (context, request) -> Result.ok(new EchoResponse(request.text())),
                OperationSchema.of("{\"type\":\"object\"}", "{\"type\":\"object\",\"required\":[\"text\"]}"), "xihan.tests.sample");
        return registry;
    }

    /** 嵌套类型的异常：简单名不带外层类型。 */
    static final class NestedFailure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        NestedFailure(String message) {
            super(message);
        }
    }

    /** 健康回调可在测试里换掉，并记下调用次数。 */
    static final class SwitchableHealth implements PluginHooks {
        final AtomicReference<Supplier<Optional<PluginHealth>>> health = new AtomicReference<>(Optional::empty);
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<PluginHealth> health() {
            calls.incrementAndGet();
            return health.get().get();
        }
    }

    @Test
    void writesOneHandshakeLineAndRejectsCallsWithoutExactlyOneSessionToken() throws Exception {
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()))) {
            Endpoint endpoint = Endpoint.parseHandshakeLine(plugin.line).orElseThrow();
            assertEquals(Transport.unixSupported() ? Network.UNIX : Network.TCP, endpoint.network());
            if (endpoint.network() == Network.TCP) {
                assertTrue(endpoint.address().matches("127\\.0\\.0\\.1:[1-9][0-9]*"), "tcp binds only 127.0.0.1: " + plugin.line);
            }
            assertFalse(Whitespace.containsWhitespace(endpoint.address()));
            List<PluginGrpc.PluginBlockingStub> refused = List.of(plugin.withoutToken(), plugin.stub("not-the-session-token"),
                    plugin.stub(RunningPlugin.TOKEN + "x"), plugin.stub(RunningPlugin.TOKEN, "wrong"), plugin.stub("wrong", RunningPlugin.TOKEN),
                    plugin.stub(RunningPlugin.TOKEN, RunningPlugin.TOKEN));
            for (PluginGrpc.PluginBlockingStub stub : refused) {
                StatusRuntimeException health = assertThrows(StatusRuntimeException.class, () -> stub.health(PluginProto.HealthRequest.getDefaultInstance()));
                assertEquals(Status.Code.UNAUTHENTICATED, health.getStatus().getCode());
                // 令牌先于次序核对：令牌不对的握手不执行，状态仍是 Declared
                StatusRuntimeException handshake = assertThrows(StatusRuntimeException.class,
                        () -> stub.handshake(PluginProto.HandshakeRequest.newBuilder().addProtocolVersions(1).build()));
                assertEquals(Status.Code.UNAUTHENTICATED, handshake.getStatus().getCode());
                // 令牌先于流式调用读第一帧：阻塞客户端的通道已经带着它的拦截器
                assertEquals(Status.Code.UNAUTHENTICATED, RunningPlugin.stream(PluginGrpc.newStub(stub.getChannel()),
                        PluginProto.StreamFrame.newBuilder().setStart(RunningPlugin.invokeRequest("samples.echo", "{}")).build()));
            }
            assertEquals(LifecycleState.DECLARED, plugin.runtime.lifecycle.state());
            assertEquals(1, plugin.handshake().getProtocolVersion());
        }
    }

    @Test
    void handshakeDeclaresTheManifestOperationsSubscriptionsAndOnlyTheAuthorsCatalogs() throws Exception {
        PluginCatalog catalog = PluginCatalog.of("samples", Map.of("en-US", Map.of("samples.text.missing", "The {field} is required."),
                "zh-CN", Map.of("samples.text.missing", "{field} 不能为空。")));
        PluginOptions.Builder options = PluginOptions.builder().operations(registry())
                .subscribe(Subscription.of("samples.events").withMessageType("samples.created").withMode(SubscriptionMode.BROADCAST),
                        (context, envelope, attempt) -> Result.success())
                .subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> Result.success())
                .catalog(catalog);
        // 清单文件以 UTF-8 BOM 开头：握手写去掉 BOM 的原文（handshake.md §3 第 3 条）
        try (RunningPlugin plugin = RunningPlugin.start(directory, "\uFEFF" + RunningPlugin.MANIFEST, options, Map.of())) {
            assertEquals(0xEF, Files.readAllBytes(directory.resolve("xihan.manifest.json"))[0] & 0xFF);
            PluginProto.HandshakeResponse response = plugin.handshake();
            assertEquals(1, response.getProtocolVersion());
            assertEquals(1, response.getPrimitives(), "primitives is the manifest's");
            assertEquals(RunningPlugin.MANIFEST, response.getManifest(), "the manifest is the file text without the BOM");
            assertEquals("java", response.getSdk().getLanguage());
            assertEquals(List.of("samples.echo"), response.getOperationsList().stream().map(PluginProto.OperationDeclaration::getId).toList());
            PluginProto.OperationDeclaration echo = response.getOperations(0);
            assertEquals("preview", echo.getStability());
            assertEquals(List.of("http", "mcp"), echo.getExposeList());
            assertEquals("samples.read", echo.getPermission());
            assertEquals("{\"type\":\"object\"}", echo.getRequestSchema());
            assertFalse(echo.getStreaming(), "plugins declare only unary operations");
            // 按声明的顺序，方式总是写出
            assertEquals(2, response.getSubscriptionsCount());
            assertEquals("broadcast", response.getSubscriptions(0).getMode());
            assertEquals("samples.created", response.getSubscriptions(0).getMessageType());
            assertEquals("competing", response.getSubscriptions(1).getMode());
            assertEquals("", response.getSubscriptions(1).getMessageType());
            // 运行时自己的目录（plugins.protocol）不随握手发送
            assertEquals(List.of("samples"), response.getCatalogsList().stream().map(PluginProto.MessageCatalog::getName).toList());
            assertEquals(List.of("en-US", "zh-CN"), response.getCatalogs(0).getLocalesList().stream().map(PluginProto.LocaleTemplates::getLocale).toList());
            assertEquals(LifecycleState.RESOLVED, plugin.runtime.lifecycle.state());
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
            assertEquals(LifecycleState.DECLARED, plugin.runtime.lifecycle.state(), "a refused handshake leaves the state unchanged");
            assertEquals(1, plugin.handshake().getProtocolVersion());
        }
    }

    @Test
    void callsOutOfOrderAreRefusedAndHealthBeforeStartReportsTheLocalState() throws Exception {
        SwitchableHealth hooks = new SwitchableHealth();
        hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.READY, HealthItem.of("sample", HealthStatus.HEALTHY))));
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks))) {
            // 握手之前（lifecycle.md §7.1）
            expectRefused(() -> plugin.configure("{}"), "Configure before Handshake");
            expectRefused(() -> plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()), "Start before Handshake");
            expectRefused(() -> plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(5).build()), "Stop before Handshake");
            expectHealth(plugin, LifecycleState.DECLARED);
            plugin.handshake();
            expectRefused(plugin::handshake, "a second Handshake");
            expectRefused(() -> plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()), "Start without Configure");
            expectHealth(plugin, LifecycleState.RESOLVED);
            assertTrue(plugin.configure("{}").getStatus().getOk());
            expectHealth(plugin, LifecycleState.CONFIGURED);
            expectRefused(() -> plugin.configure("{}"), "a second Configure");
            assertEquals(0, hooks.calls.get(), "the health hook is not called before Start (lifecycle.md §7.4)");
            assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
            assertEquals(1, hooks.calls.get(), "Start asks the health hook once before it answers (lifecycle.md §7.3)");
            expectRefused(() -> plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()), "a second Start");
            PluginProto.HealthResponse health = plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, health.getState());
            assertEquals(1, health.getItemsCount());
            assertEquals(LifecycleState.READY, plugin.runtime.lifecycle.state());
        }
    }

    private static void expectRefused(Runnable call, String what) {
        StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, call::run, what);
        assertEquals(Status.Code.FAILED_PRECONDITION, refused.getStatus().getCode(), what);
    }

    private static void expectHealth(RunningPlugin plugin, LifecycleState state) {
        PluginProto.HealthResponse health = plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
        assertEquals(state.number(), health.getStateValue(), "Health before Start reports " + state);
        assertEquals(0, health.getItemsCount(), "no health items before Start");
        assertEquals(state, plugin.runtime.lifecycle.state());
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
            PluginProto.ConfigureResponse response = plugin.stub().configure(PluginProto.ConfigureRequest.newBuilder()
                    .setConfiguration("{\"Greeting\":\"hi\",\"count\":\"7\",\"enabled\":\"TRUE\",\"extra\":1}")
                    .addGrantedPermissions("capability:kernel.pipeline")
                    .addBindings(PluginProto.Binding.newBuilder().setName("main").setType("connection:relational").setValue("secret"))
                    .setWorkDirectory(directory.toString())
                    .build());
            assertTrue(response.getStatus().getOk());
            // 宿主写出的叶子是字符串：数字与布尔从字符串读出，成员名不区分大小写，未知成员忽略（lifecycle.md §7.2）
            assertEquals(new Settings("hi", 7, true), configured.get().decode(Settings.class));
            assertEquals("{\"Greeting\":\"hi\",\"count\":\"7\",\"enabled\":\"TRUE\",\"extra\":1}", configured.get().section(), "the section as it came");
            assertEquals(List.of("capability:kernel.pipeline"), configured.get().grantedPermissions());
            assertEquals(Optional.of(new ResolvedBinding("main", "connection:relational", "secret")), configured.get().findBinding("main"));
            assertEquals(Optional.of(directory), configured.get().workDirectory());
            assertFalse(configured.get().toString().contains("secret"), "connection strings stay out of logs");

            assertTrue(plugin.stub().start(PluginProto.StartRequest.getDefaultInstance()).getStatus().getOk());
            assertEquals(LifecycleState.READY, plugin.runtime.lifecycle.state(), "Start answers after the plugin is ready");
            PluginProto.HealthResponse health = plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, health.getState());
            assertEquals(List.of("sample"), health.getItemsList().stream().map(PluginProto.HealthItem::getName).toList(),
                    "undeclared health items are dropped");
            plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(1, count(plugin.logs(), "\"item\":\"undeclared\""), "one warning per undeclared name: " + plugin.logs());

            Iterator<PluginProto.HealthResponse> watch = plugin.stub().withDeadlineAfter(10, TimeUnit.SECONDS).watchHealth(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, watch.next().getState(), "WatchHealth pushes the current state first");

            PluginProto.StopResponse stopped = plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(2).setReason("unload").build());
            assertTrue(stopped.getStatus().getOk());
            assertEquals("unload", stopReason.get(), "the reason goes to the stop hook as it came");
            // 进入 Draining 时推一次，应答 Stop 之后结束流
            List<PluginProto.LifecycleState> rest = new ArrayList<>();
            watch.forEachRemaining(next -> rest.add(next.getState()));
            assertEquals(List.of(PluginProto.LifecycleState.LIFECYCLE_STATE_DRAINING), rest);
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)), "Stop ends serve");
            assertEquals(LifecycleState.STOPPED, plugin.runtime.lifecycle.state());
        } finally {
            plugin.close();
        }
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            found++;
        }
        return found;
    }

    @Test
    void startEntersTheStateTheHealthHookReports() throws Exception {
        // lifecycle.md §7.3：Ready、Degraded、Failed 照办；抛出时这一次的结果是 Degraded；其他状态与没有健康回调都是 Ready
        record Scenario(String name, Supplier<Optional<PluginHealth>> health, LifecycleState expected) {
        }
        Error degraded = Error.unavailable("SAMPLES_UNAVAILABLE_001", "samples.dependency.down");
        List<Scenario> scenarios = List.of(
                new Scenario("none", Optional::empty, LifecycleState.READY),
                new Scenario("ready", () -> Optional.of(PluginHealth.of(LifecycleState.READY)), LifecycleState.READY),
                new Scenario("degraded", () -> Optional.of(PluginHealth.of(LifecycleState.DEGRADED).withError(degraded)), LifecycleState.DEGRADED),
                new Scenario("failed", () -> Optional.of(PluginHealth.of(LifecycleState.FAILED)), LifecycleState.FAILED),
                new Scenario("other", () -> Optional.of(PluginHealth.of(LifecycleState.STARTED)), LifecycleState.READY),
                new Scenario("throws", () -> {
                    throw new IllegalStateException("health is broken");
                }, LifecycleState.DEGRADED));
        for (Scenario scenario : scenarios) {
            SwitchableHealth hooks = new SwitchableHealth();
            hooks.health.set(scenario.health());
            Path root = Files.createDirectories(directory.resolve(scenario.name()));
            try (RunningPlugin plugin = RunningPlugin.start(root, PluginOptions.builder().hooks(hooks))) {
                plugin.ready("{}");
                assertEquals(scenario.expected(), plugin.runtime.lifecycle.state(), scenario.name());
                if (scenario.name().equals("throws")) {
                    PluginProto.HealthResponse health = plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
                    assertEquals("PLUGINS_UNEXPECTED_001", health.getError().getCode());
                    assertEquals(object("id", "xihan.tests.sample", "phase", "health", "reason", "IllegalStateException"), details(health.getError()));
                }
                if (scenario.name().equals("degraded")) {
                    assertEquals("SAMPLES_UNAVAILABLE_001", plugin.runtime.lifecycle.error().orElseThrow().code());
                }
            }
        }
    }

    @Test
    void healthAdoptsReportedStatesAndNeverLeavesFailed() throws Exception {
        SwitchableHealth hooks = new SwitchableHealth();
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(registry()).hooks(hooks))) {
            plugin.ready("{}");
            assertEquals(LifecycleState.READY, plugin.runtime.lifecycle.state());
            Iterator<PluginProto.HealthResponse> watch = plugin.stub().withDeadlineAfter(20, TimeUnit.SECONDS).watchHealth(PluginProto.HealthRequest.getDefaultInstance());
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, watch.next().getState());

            Error down = Error.unavailable("SAMPLES_UNAVAILABLE_001", "samples.dependency.down");
            hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.DEGRADED, HealthItem.of("sample", HealthStatus.DEGRADED)).withError(down)));
            PluginProto.HealthResponse degraded = health(plugin);
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_DEGRADED, degraded.getState());
            assertEquals("SAMPLES_UNAVAILABLE_001", degraded.getError().getCode());
            assertEquals("", degraded.getError().getText(), "an error with a message key leaves text empty");
            assertEquals(LifecycleState.DEGRADED, plugin.runtime.lifecycle.state());
            // 状态变化即时推送
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_DEGRADED, watch.next().getState());

            hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.READY)));
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, health(plugin).getState());

            // 抛出：这一次的结果是 Degraded、没有健康项，状态与报了 Degraded 一样迁到 Degraded（lifecycle.md §7.4）
            hooks.health.set(() -> {
                throw new UnsupportedOperationException("not now");
            });
            PluginProto.HealthResponse threw = health(plugin);
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_DEGRADED, threw.getState());
            assertEquals(0, threw.getItemsCount());
            assertEquals(object("id", "xihan.tests.sample", "phase", "health", "reason", "UnsupportedOperationException"), details(threw.getError()));
            assertEquals(LifecycleState.DEGRADED, plugin.runtime.lifecycle.state());
            assertEquals("PLUGINS_UNEXPECTED_001", plugin.runtime.lifecycle.error().orElseThrow().code());

            // 其他状态不改变状态；之后的结果照常决定状态
            hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.DRAINING)));
            health(plugin);
            assertEquals(LifecycleState.DEGRADED, plugin.runtime.lifecycle.state());
            hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.READY)));
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_READY, health(plugin).getState());
            assertEquals(LifecycleState.READY, plugin.runtime.lifecycle.state());
            assertTrue(plugin.runtime.lifecycle.error().isEmpty(), "Ready clears the error");

            hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.FAILED)));
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_FAILED, health(plugin).getState());
            int asked = hooks.calls.get();
            hooks.health.set(() -> Optional.of(PluginHealth.of(LifecycleState.READY)));
            assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_FAILED, health(plugin).getState(), "Failed does not come back");
            assertEquals(asked, hooks.calls.get(), "the health hook is not called after Failed");
            // Failed 不可服务
            PluginProto.InvokeResponse refused = plugin.stub().invoke(RunningPlugin.invokeRequest("samples.echo", "{}"));
            assertEquals("PLUGINS_UNAVAILABLE_001", refused.getStatus().getError().getCode());
        }
    }

    private static PluginProto.HealthResponse health(RunningPlugin plugin) {
        return plugin.stub().health(PluginProto.HealthRequest.getDefaultInstance());
    }

    @Test
    void hooksThatThrowFailWithTheSimpleTypeNameOnly() throws Exception {
        record Scenario(String name, RuntimeException thrown, String reason) {
        }
        // 匿名类没有简单名：取去掉包名的二进制名
        RuntimeException anonymous = new RuntimeException("anonymous") {
        };
        String binary = anonymous.getClass().getName();
        assertTrue(binary.startsWith(PluginRuntimeTest.class.getName() + "$"), binary);
        List<Scenario> scenarios = List.of(
                new Scenario("plain", new IllegalStateException("C:\\secret\\path"), "IllegalStateException"),
                new Scenario("framework", new FrameworkException("SAMPLES_UNEXPECTED_001", "connection string"), "FrameworkException"),
                new Scenario("nested", new NestedFailure("nested"), "NestedFailure"),
                new Scenario("anonymous", anonymous, binary.substring(binary.lastIndexOf('.') + 1)));
        for (Scenario scenario : scenarios) {
            PluginHooks hooks = new PluginHooks() {
                @Override
                public Result<Unit> configure(Context context, PluginConfiguration configuration) {
                    throw scenario.thrown();
                }
            };
            Path root = Files.createDirectories(directory.resolve(scenario.name()));
            try (RunningPlugin plugin = RunningPlugin.start(root, PluginOptions.builder().hooks(hooks))) {
                plugin.handshake();
                PluginProto.Status status = plugin.configure("{}").getStatus();
                assertFalse(status.getOk());
                assertEquals("PLUGINS_UNEXPECTED_001", status.getError().getCode());
                assertEquals(PluginMessages.FAILED, status.getError().getMessageKey());
                assertEquals("", status.getError().getText(), "the runtime does not render a key-bearing error");
                assertEquals(object("id", "xihan.tests.sample", "phase", "configure", "reason", scenario.reason()), details(status.getError()));
                assertFalse(status.getError().hasInner(), "the exception text does not reach the host");
                PluginProto.HealthResponse health = health(plugin);
                assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_FAILED, health.getState());
                assertEquals("PLUGINS_UNEXPECTED_001", health.getError().getCode());
                assertTrue(plugin.logs().contains("\"level\":\"error\"") && plugin.logs().contains(scenario.thrown().getMessage().replace("\\", "\\\\")),
                        "the exception goes to stderr: " + plugin.logs());
            }
        }
    }

    @Test
    void aConfigurationThatIsNotAnObjectFailsWithoutCallingTheHook() throws Exception {
        for (String configuration : List.of("[]", "{\"a\":", "\"text\"", "{\"a\":1,\"a\":2}", " null")) {
            AtomicInteger configures = new AtomicInteger();
            AtomicInteger stops = new AtomicInteger();
            PluginHooks hooks = new PluginHooks() {
                @Override
                public Result<Unit> configure(Context context, PluginConfiguration ignored) {
                    configures.incrementAndGet();
                    return Result.success();
                }

                @Override
                public Result<Unit> stop(Context context, String reason, Duration grace) {
                    stops.incrementAndGet();
                    return Result.success();
                }
            };
            Path root = Files.createDirectories(directory.resolve("c" + Integer.toHexString(configuration.hashCode())));
            RunningPlugin plugin = RunningPlugin.start(root, PluginOptions.builder().hooks(hooks));
            try {
                plugin.handshake();
                PluginProto.Status status = plugin.configure(configuration).getStatus();
                assertEquals("PLUGINS_UNEXPECTED_001", status.getError().getCode(), configuration);
                assertEquals(PluginMessages.FAILED, status.getError().getMessageKey());
                assertEquals("", status.getError().getText());
                assertEquals(object("id", "xihan.tests.sample", "phase", "configure", "reason", "the configuration is not a JSON object"),
                        details(status.getError()));
                assertEquals(0, configures.get(), "the configure hook is not called");
                assertEquals(PluginProto.LifecycleState.LIFECYCLE_STATE_FAILED, health(plugin).getState());
                // 启动回调没有成功过：Stop 应答 ok、不调停止回调、退出（lifecycle.md §7.5）
                assertTrue(plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(5).build()).getStatus().getOk());
                assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
                assertEquals(0, stops.get());
            } finally {
                plugin.close();
            }
        }
        // 空配置节等同 {}
        AtomicReference<String> section = new AtomicReference<>();
        PluginHooks hooks = new PluginHooks() {
            @Override
            public Result<Unit> configure(Context context, PluginConfiguration configuration) {
                section.set(configuration.section());
                return Result.success();
            }
        };
        try (RunningPlugin plugin = RunningPlugin.start(Files.createDirectories(directory.resolve("empty")), PluginOptions.builder().hooks(hooks))) {
            plugin.handshake();
            assertTrue(plugin.configure("").getStatus().getOk());
            assertEquals("{}", section.get());
        }
    }

    @Test
    void typedConfigurationReadsStringLeavesAsJsonNumbersAndBooleans() throws Exception {
        Typed typed = configuration("{\"COUNT\":\"7\",\"Big\":\"9007199254740993\",\"ratio\":\"2.5e1\",\"enabled\":\"tRuE\",\"flag\":\"False\",\"amount\":\"1.50\"}")
                .decode(Typed.class);
        assertEquals(new Typed(7, 9007199254740993L, 25.0, true, false, new BigDecimal("1.50")), typed);
        assertEquals(new Count(7), configuration("{\"count\":7}").decode(Count.class), "a JSON number reads as usual");
        assertEquals(new Count(-3), configuration("{\"count\":\"-3\"}").decode(Count.class));
        for (String invalid : List.of(" 7", "7 ", "+7", "07", "0x10", "NaN", "Infinity", "7.", ".5", "1e", "", "\uFF17")) {
            assertThrows(IOException.class, () -> configuration("{\"count\":\"" + invalid + "\"}").decode(Count.class), "'" + invalid + "'");
        }
        for (String invalid : List.of("yes", "1", "0", "t", "", " true", "fal\u017Fe", "tr\u0130e")) {
            assertThrows(IOException.class, () -> configuration("{\"flag\":\"" + invalid + "\"}").decode(Flag.class), "'" + invalid + "'");
        }
        assertEquals(new Flag(true), configuration("{\"FLAG\":\"TRUE\"}").decode(Flag.class));
        assertThrows(IOException.class, () -> configuration("{\"count\":1,\"count\":2}").decode(Count.class), "the common JSON check comes first");
        assertTrue(ConfigurationNames.asciiEqualsIgnoreCase("FaLsE", "false"));
        assertFalse(ConfigurationNames.asciiEqualsIgnoreCase("fal\u017Fe", "false"), "the long s is not an ASCII letter");
        assertFalse(ConfigurationNames.asciiEqualsIgnoreCase("\u212Aey", "key"), "the Kelvin sign is not an ASCII letter");
    }

    @Test
    void configurationMemberNamesMatchByAsciiCaseOnly() throws Exception {
        // lifecycle.md §7.2：成员名按 ASCII 不区分大小写；U+212A（开尔文符号）在 Unicode 里小写成 k，按 ASCII 不算
        assertEquals(new Keyed("v", 0), configuration("{\"KEY\":\"v\"}").decode(Keyed.class));
        assertEquals(new Keyed(null, 0), configuration("{\"\u212Aey\":\"v\"}").decode(Keyed.class), "the Kelvin sign is not 'k'");
        assertEquals(new Keyed(null, 0), configuration("{\"\u212AEY\":\"v\"}").decode(Keyed.class));
        assertEquals(new Unicode(null), configuration("{\"\u00C9t\u00C9\":\"v\"}").decode(Unicode.class), "non-ASCII letters match only as they are");
        assertEquals(new Unicode("v"), configuration("{\"\u00E9t\u00E9\":\"v\"}").decode(Unicode.class));
        assertEquals(new Unicode("v"), configuration("{\"\u00E9T\u00E9\":\"v\"}").decode(Unicode.class), "ASCII letters still fold");
        // 原样相同的属性优先，其次 ASCII 不区分大小写
        assertEquals(new Keyed("exact", 2), configuration("{\"key\":\"exact\",\"COUNT\":\"2\"}").decode(Keyed.class));
        // 嵌套的记录、列表、数组与字典的值逐层改写；字典的键原样
        Nested nested = configuration("{\"INNER\":{\"Key\":\"a\",\"Count\":\"1\"},\"LIST\":[{\"KEY\":\"b\"}],"
                + "\"Map\":{\"Name\":{\"kEy\":\"c\"}},\"ARRAY\":[{\"COUNT\":3}],\"\u212Anown\":1}").decode(Nested.class);
        assertEquals(new Keyed("a", 1), nested.inner());
        assertEquals(List.of(new Keyed("b", 0)), nested.list());
        assertEquals(Map.of("Name", new Keyed("c", 0)), nested.map());
        assertEquals(List.of(new Keyed(null, 3)), List.of(nested.array()));
        // 数字原文照读：超出双精度的小数与 BigDecimal 的精度不因改写而变
        assertEquals(new Typed(1, 9007199254740993L, 0.1, false, null, new BigDecimal("1.50")),
                configuration("{\"COUNT\":1,\"BIG\":9007199254740993,\"Ratio\":0.1,\"Amount\":1.50}").decode(Typed.class));
    }

    private static PluginConfiguration configuration(String section) {
        return new PluginConfiguration(section, List.of(), List.of(), Optional.empty());
    }

    @Test
    void refusesMisconfiguredPluginsBeforeListening() throws Exception {
        assertRefused(PluginErrors.INVALID_SOURCE, RunningPlugin.MANIFEST.replace("\"kind\": \"plugin\"", "\"kind\": \"provider\"")
                .replace("  \"isolation\": \"out-of-process\",\n", "").replace("  \"permissions\": [ \"capability:kernel.pipeline\", \"capability:messaging.queue\" ],\n", ""),
                PluginOptions.builder(), Map.of());
        assertRefused(PluginErrors.INVALID_SOURCE, RunningPlugin.MANIFEST.replace("\"health\": [ \"sample\" ]",
                "\"health\": [ \"sample\" ], \"pipeline\": [ { \"middleware\": \"Audit\", \"stage\": \"observe\" } ]"), PluginOptions.builder(), Map.of());
        assertRefused(PluginErrors.INVALID_SOURCE, RunningPlugin.MANIFEST.replace("  \"entry\": \"sample\"\n", "  \"activation\": [ \"onStartup\" ]\n"),
                PluginOptions.builder(), Map.of());
        assertRefused(PluginErrors.INVALID_SOURCE, RunningPlugin.MANIFEST.replace("\"primitives\": \"1\"", "\"primitives\": \"2\""), PluginOptions.builder(),
                Map.of());
        OperationRegistry undeclared = new OperationRegistry();
        undeclared.add(OperationContract.of("samples.undeclared"), EchoRequest.class, EchoResponse.class, (context, request) -> Result.ok(new EchoResponse("")));
        assertRefused(PluginErrors.HANDSHAKE_MISMATCH, RunningPlugin.MANIFEST, PluginOptions.builder().operations(undeclared), Map.of());
        PluginOptions.Builder twice = PluginOptions.builder()
                .subscribe(Subscription.of("samples.events"), (context, envelope, attempt) -> Result.success())
                .subscribe(Subscription.of("samples.events").withMode(SubscriptionMode.BROADCAST), (context, envelope, attempt) -> Result.success());
        assertRefused(PluginErrors.HANDSHAKE_MISMATCH, RunningPlugin.MANIFEST, twice, Map.of());
        assertRefused("PRIMITIVES_VALIDATION_001", RunningPlugin.MANIFEST, PluginOptions.builder(), Map.of(PluginRuntime.ENV_SESSION, ""));
        assertRefused("PRIMITIVES_VALIDATION_001", RunningPlugin.MANIFEST, PluginOptions.builder(), Map.of(PluginRuntime.ENV_PROTOCOL_VERSIONS, "1,x"));
        assertThrows(IllegalArgumentException.class, () -> Subscription.of("Bad Topic"));
        assertThrows(IllegalArgumentException.class, () -> PluginOptions.builder().network(Network.NPIPE));
    }

    /** 启动被拒：不写握手行，stderr 有一条 error，异常带给定的错误码（handshake.md §1 末段）。 */
    private void assertRefused(String code, String manifest, PluginOptions.Builder options, Map<String, String> environment) throws Exception {
        Path file = directory.resolve("xihan.manifest.json");
        Files.writeString(file, manifest, StandardCharsets.UTF_8);
        Map<String, String> variables = new java.util.HashMap<>(Map.of(PluginRuntime.ENV_SESSION, RunningPlugin.TOKEN, PluginRuntime.ENV_PROTOCOL_VERSIONS, "1"));
        variables.putAll(environment);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        FrameworkException failed = assertThrows(FrameworkException.class, () -> PluginRuntime.start(options.manifest(file).environment(variables)
                .streams(InputStream.nullInputStream(), stdout, stderr).bridgePlatformLogging(false).exitProcess(false).build()));
        assertEquals(code, failed.code());
        assertEquals(0, stdout.size(), "no handshake line");
        String logs = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(1, logs.lines().count(), logs);
        JsonNode line = RunningPlugin.JSON.readTree(logs);
        assertEquals("error", line.get("level").asText());
        assertEquals(code, line.get("code").asText());
    }

    @Test
    void anInvalidOrUnsupportedHostAddressOnlyWarns() throws Exception {
        for (String value : new String[] {"pipe", "npipe \\\\.\\pipe\\xihan-host", "tcp 10.0.0.1:5000", "unix /tmp/a;"}) {
            Path root = Files.createDirectories(directory.resolve("h" + Integer.toHexString(value.hashCode())));
            try (RunningPlugin plugin = RunningPlugin.start(root, RunningPlugin.MANIFEST, PluginOptions.builder(), Map.of(PluginRuntime.ENV_HOST_ADDRESS, value))) {
                assertTrue(plugin.logs().contains("\"level\":\"warning\"") && plugin.logs().contains(Host.NO_ENDPOINT), plugin.logs());
                assertEquals(Optional.empty(), plugin.runtime.host.endpoint());
                plugin.ready("{}");
                assertEquals(LifecycleState.READY, plugin.runtime.lifecycle.state(), "the plugin serves as usual");
            }
        }
        // 变量缺失同样只告警
        try (RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder())) {
            assertTrue(plugin.logs().contains(Host.NO_ENDPOINT), plugin.logs());
        }
    }

    @Test
    void theStopHookRunsAtMostOnceAndAStopWhileStoppingIsRefused() throws Exception {
        CountDownLatch inStop = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger stops = new AtomicInteger();
        PluginHooks hooks = new PluginHooks() {
            @Override
            public Result<Unit> stop(Context context, String reason, Duration grace) {
                stops.incrementAndGet();
                inStop.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Result.fail(Error.unavailable("SAMPLES_UNAVAILABLE_001", "samples.flush.failed"));
            }
        };
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks));
        try {
            plugin.ready("{}");
            AtomicReference<PluginProto.StopResponse> first = new AtomicReference<>();
            Thread stopper = Thread.ofVirtual().start(() -> first.set(plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(5).build())));
            assertTrue(inStop.await(10, TimeUnit.SECONDS));
            expectRefused(() -> plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(5).build()), "Stop while stopping");
            release.countDown();
            stopper.join();
            // 停止回调的失败写进 StopResponse.status，照常退出
            assertEquals("SAMPLES_UNAVAILABLE_001", first.get().getStatus().getError().getCode());
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
            assertEquals(1, stops.get());
        } finally {
            plugin.close();
        }
        assertEquals(1, stops.get(), "closing stdin afterwards does not call the stop hook again");
    }
}
