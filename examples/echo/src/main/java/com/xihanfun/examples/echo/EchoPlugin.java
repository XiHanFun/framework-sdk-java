package com.xihanfun.examples.echo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.operation.Expose;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.operation.OperationResponse;
import com.xihanfun.framework.operation.OperationSchema;
import com.xihanfun.framework.plugin.Envelope;
import com.xihanfun.framework.plugin.HealthItem;
import com.xihanfun.framework.plugin.HealthStatus;
import com.xihanfun.framework.plugin.Host;
import com.xihanfun.framework.plugin.PluginCatalog;
import com.xihanfun.framework.plugin.PluginConfiguration;
import com.xihanfun.framework.plugin.PluginHealth;
import com.xihanfun.framework.plugin.PluginHooks;
import com.xihanfun.framework.plugin.PluginOptions;
import com.xihanfun.framework.plugin.PluginRuntime;
import com.xihanfun.framework.plugin.Subscription;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 回声插件：用本 SDK 写的进程外插件示例，也是一致性测试用黑盒运行器验证的对象。
 *
 * <p>把编译结果、依赖与 {@code xihan.manifest.json} 放进插件目录，清单的 {@code entry} 指向启动它的脚本，宿主即可加载：
 * <ul>
 *   <li>{@code echoes.create}：回声文本，附上配置节 {@code XiHan:Echo} 的问候语与上下文里的租户、主体、关联 ID；文本为空是业务失败</li>
 *   <li>{@code echoes.relay}：经宿主回调调用另一个操作，把宿主的应答原样返回</li>
 *   <li>{@code echoes.crash}：故意让进程异常退出而不应答，宿主应记为崩溃并按退避重启；一致性测试排除它</li>
 *   <li>{@code events.list}：列出收到的 {@code echo.events} 事件的 ID（只留最近的 {@value #EVENT_CAPACITY} 个）</li>
 * </ul>
 * 健康项 {@code echo} 总是健康。
 */
public final class EchoPlugin {
    /** 文本为空。 */
    static final String TEXT_MISSING = "ECHOJAVA_VALIDATION_002";

    /** 配置节不合法。 */
    static final String CONFIGURATION_INVALID = "ECHOJAVA_VALIDATION_001";

    /** 转发请求没有给操作 ID。 */
    static final String OPERATION_MISSING = "ECHOJAVA_VALIDATION_003";

    /** 宿主没有给出回调端点。 */
    static final String HOST_MISSING = "ECHOJAVA_UNAVAILABLE_001";

    /** 宿主转发回来的载荷不是 JSON。 */
    static final String RELAY_INVALID = "ECHOJAVA_UNEXPECTED_001";

    /** 操作的归属：插件的清单 ID。 */
    static final String OWNER = "xihan.samples.echo-java";

    /** 记住的事件 ID 个数上限：投递只增不减，表不能无界增长。 */
    static final int EVENT_CAPACITY = 1000;

    /** 解析宿主转发回来的载荷。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 问候语与事件 ID 在同一把锁里读写。 */
    private final ReentrantLock lock = new ReentrantLock();
    private String greeting = "";
    private final ArrayDeque<String> events = new ArrayDeque<>();

    /** {@code echoes.create} 的请求。 */
    record CreateRequest(String text) {
    }

    /** {@code echoes.create} 的响应：没有的成员不写。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateResponse(String text, String greeting, String tenant, String principal, String correlation) {
    }

    /** {@code echoes.relay} 的请求：经宿主调用 {@code operation}，载荷是 {@code payload}。 */
    record RelayRequest(String operation, JsonNode payload) {
    }

    /** 没有成员的请求。 */
    record Empty() {
    }

    /** {@code events.list} 的响应。 */
    record EventList(List<String> ids) {
    }

    /** 配置节 {@code XiHan:Echo}。 */
    record Settings(String greeting) {
    }

    private EchoPlugin() {
    }

    /**
     * 以进程外插件的身份运行到停止；工作目录是插件目录，清单从那里读。
     *
     * @param args 不用
     * @throws Exception 配置错误或监听失败
     */
    public static void main(String[] args) throws Exception {
        PluginRuntime.serve(options());
    }

    /**
     * 组装运行时的选项：操作、订阅、消息目录与生命周期回调。
     *
     * @return 选项
     * @throws IOException 消息目录读不出
     */
    static PluginOptions options() throws IOException {
        EchoPlugin echo = new EchoPlugin();
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("echoes.create").withStability(Stability.EXPERIMENTAL).withExpose(Expose.HTTP, Expose.MCP),
                CreateRequest.class, CreateResponse.class, echo::create,
                OperationSchema.of("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}",
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"},"
                                + "\"greeting\":{\"type\":\"string\"},\"tenant\":{\"type\":\"string\"},"
                                + "\"principal\":{\"type\":\"string\"},\"correlation\":{\"type\":\"string\"}},"
                                + "\"required\":[\"text\"]}"),
                OWNER);
        registry.add(OperationContract.of("echoes.relay").withStability(Stability.EXPERIMENTAL).withExpose(Expose.HTTP),
                RelayRequest.class, JsonNode.class, echo::relay, OWNER);
        registry.add(OperationContract.of("echoes.crash").withStability(Stability.EXPERIMENTAL),
                Empty.class, Empty.class, EchoPlugin::crash, OWNER);
        registry.add(OperationContract.of("events.list").withStability(Stability.EXPERIMENTAL).withExpose(Expose.HTTP),
                Empty.class, EventList.class, echo::list, OWNER);

        PluginCatalog catalog = PluginCatalog.load("echojava",
                locale -> EchoPlugin.class.getResourceAsStream("Localization/" + locale + ".json"));
        return PluginOptions.builder()
                .operations(registry)
                .subscribe(Subscription.of("echo.events"), echo::record)
                .catalog(catalog)
                .hooks(echo.hooks())
                .build();
    }

    private PluginHooks hooks() {
        return new PluginHooks() {
            @Override
            public Result<Unit> configure(Context context, PluginConfiguration configuration) {
                Settings settings;
                try {
                    settings = configuration.decode(Settings.class);
                } catch (IOException invalid) {
                    return Result.fail(Error.validation(CONFIGURATION_INVALID, "echojava.configuration.invalid", Map.of("reason", "$")));
                }
                lock.lock();
                try {
                    greeting = settings.greeting() == null ? "" : settings.greeting();
                } finally {
                    lock.unlock();
                }
                return Result.success();
            }

            @Override
            public Optional<PluginHealth> health() {
                return Optional.of(PluginHealth.of(LifecycleState.READY, HealthItem.of("echo", HealthStatus.HEALTHY)));
            }
        };
    }

    /** 回声文本并附上下文；文本为空是业务失败，宿主用握手时拿到的目录翻译它。 */
    private Result<CreateResponse> create(Context context, CreateRequest request) {
        if (request.text() == null || request.text().isEmpty()) {
            return Result.fail(Error.validation(TEXT_MISSING, "echojava.text.missing", Map.of("operation", "echoes.create")));
        }
        String current;
        lock.lock();
        try {
            current = greeting.isEmpty() ? null : greeting;
        } finally {
            lock.unlock();
        }
        return Result.ok(new CreateResponse(request.text(), current, context.tenant().orElse(null), context.principal().orElse(null),
                context.correlation().orElse(null)));
    }

    /** 经宿主回调调用另一个操作并原样返回应答；没有宿主回调端点时失败。 */
    private Result<JsonNode> relay(Context context, RelayRequest request) {
        if (request.operation() == null || request.operation().isEmpty()) {
            return Result.fail(Error.validation(OPERATION_MISSING, "echojava.relay.operation_missing",
                    Map.of("operation", "echoes.relay")));
        }
        Optional<Host> host = Host.of(context);
        if (host.isEmpty()) {
            return Result.fail(Error.unavailable(HOST_MISSING, "echojava.host.unavailable"));
        }
        byte[] payload = request.payload() == null ? new byte[] {'{', '}'} : request.payload().toString().getBytes(StandardCharsets.UTF_8);
        OperationResponse response = host.get().invoke(context, request.operation(), payload);
        if (!response.isOk()) {
            return Result.fail(response.outcome().error().orElseThrow());
        }
        try {
            return Result.ok(JSON.readTree(response.payload().orElse(new byte[] {'{', '}'})));
        } catch (IOException invalid) {
            return Result.fail(Error.unexpected(RELAY_INVALID, "echojava.relay.invalid_response",
                    Map.of("operation", request.operation())));
        }
    }

    /** 故意让进程异常退出而不应答：halt 不执行关闭钩子，宿主看到的就是崩溃。 */
    private static Result<Empty> crash(Context context, Empty request) {
        Runtime.getRuntime().halt(3);
        return Result.ok(request);
    }

    /** 列出收到的事件 ID。 */
    private Result<EventList> list(Context context, Empty request) {
        lock.lock();
        try {
            return Result.ok(new EventList(List.copyOf(events)));
        } finally {
            lock.unlock();
        }
    }

    /** 记录投递的事件；投递至少一次，同一个 ID 只记一次。 */
    private Result<Unit> record(Context context, Envelope envelope, int attempt) {
        lock.lock();
        try {
            if (!events.contains(envelope.id())) {
                if (events.size() == EVENT_CAPACITY) {
                    events.removeFirst();
                }
                events.addLast(envelope.id());
            }
        } finally {
            lock.unlock();
        }
        return Result.success();
    }
}
