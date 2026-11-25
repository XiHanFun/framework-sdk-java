package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.xihanfun.framework.plugin.v1.PluginGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试夹具：在本进程里启动一个插件运行时，换掉环境变量与标准输入输出，再以宿主的身份连上它的端点。
 *
 * <p>运行时以 {@code exitProcess(false)} 启动：收尾到时只停下服务端，不结束测试进程，也不登记关闭钩子。
 */
final class RunningPlugin implements AutoCloseable {
    /** 会话令牌。 */
    static final String TOKEN = "dGVzdC1zZXNzaW9uLXRva2VuLTAxMjM0NTY3ODlhYmNkZWY";

    /** 读写细节与载荷。 */
    static final ObjectMapper JSON = new ObjectMapper();

    /** 测试清单声明的回调权限：宿主授予的权限等于它（{@code lifecycle.md} §2）。 */
    static final List<String> PERMISSIONS = List.of("capability:kernel.pipeline", "capability:messaging.queue");

    /** 测试用的清单：声明了测试会登记的操作、一个健康项、一个配置节与两项回调权限。 */
    static final String MANIFEST = """
            {
              "id": "xihan.tests.sample",
              "version": "1.0.0",
              "primitives": "1",
              "stability": "experimental",
              "kind": "plugin",
              "isolation": "out-of-process",
              "contributes": {
                "operations": [
                  "samples.echo", "samples.fail", "samples.relay", "samples.publish", "samples.context", "samples.wait", "samples.text"
                ],
                "config": { "section": "XiHan:Sample" },
                "health": [ "sample" ]
              },
              "permissions": [ "capability:kernel.pipeline", "capability:messaging.queue" ],
              "entry": "sample"
            }
            """;

    final PluginRuntime runtime;
    final String line;
    final ByteArrayOutputStream stderr;
    private final PipedOutputStream stdin;
    private final ManagedChannel channel;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private RunningPlugin(PluginRuntime runtime, String line, ByteArrayOutputStream stderr, PipedOutputStream stdin) {
        this.runtime = runtime;
        this.line = line;
        this.stderr = stderr;
        this.stdin = stdin;
        // 握手行按规范的文法解析（handshake.md §2），与宿主读到的一样
        Optional<Endpoint> endpoint = Endpoint.parseHandshakeLine(line);
        assertTrue(endpoint.isPresent(), "the handshake line must match the grammar: " + line);
        this.channel = Transport.dial(endpoint.get(), executor).channel();
    }

    /**
     * 写清单并启动运行时。
     */
    static RunningPlugin start(Path directory, String manifest, PluginOptions.Builder options, Map<String, String> environment) throws IOException {
        Path file = directory.resolve("xihan.manifest.json");
        Files.writeString(file, manifest, StandardCharsets.UTF_8);
        Map<String, String> variables = new HashMap<>(Map.of(
                PluginRuntime.ENV_SESSION, TOKEN,
                PluginRuntime.ENV_PROTOCOL_VERSIONS, "1",
                PluginRuntime.ENV_PRIMITIVES, "1",
                PluginRuntime.ENV_INSTANCE, "xihan.tests.sample#1",
                PluginRuntime.ENV_LOCALE, "en-US"));
        variables.putAll(environment);
        PipedOutputStream stdin = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(stdin);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PluginRuntime runtime = PluginRuntime.start(options.manifest(file).environment(variables).streams(input, stdout, stderr)
                .bridgePlatformLogging(false).exitProcess(false).build());
        String written = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(written.endsWith("\n") && written.indexOf('\n') == written.length() - 1, "stdout must hold exactly one line: " + written);
        return new RunningPlugin(runtime, written.substring(0, written.length() - 1), stderr, stdin);
    }

    static RunningPlugin start(Path directory, PluginOptions.Builder options) throws IOException {
        return start(directory, MANIFEST, options, Map.of());
    }

    /** 带本次会话令牌的阻塞客户端。 */
    PluginGrpc.PluginBlockingStub stub() {
        return stub(TOKEN);
    }

    /** 带给定令牌的阻塞客户端：每个元素是元数据 {@code xihan-plugin-session} 的一个值。 */
    PluginGrpc.PluginBlockingStub stub(String... tokens) {
        return PluginGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(tokens)));
    }

    /** 不带会话令牌的阻塞客户端。 */
    PluginGrpc.PluginBlockingStub withoutToken() {
        return PluginGrpc.newBlockingStub(channel);
    }

    /** 带本次会话令牌的异步客户端（流式调用用）。 */
    PluginGrpc.PluginStub async() {
        return PluginGrpc.newStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(TOKEN)));
    }

    /** 以宿主的身份握手。 */
    PluginProto.HandshakeResponse handshake() {
        return stub().handshake(PluginProto.HandshakeRequest.newBuilder().addProtocolVersions(1).setPrimitives(1).setInstanceId("test")
                .setDefaultLocale("en-US").build());
    }

    /** 握手、配置并启动。 */
    void ready(String configuration) {
        handshake();
        configureAndStart(configuration);
    }

    /** 以宿主的身份配置：授予的权限等于清单 {@code permissions}。 */
    PluginProto.ConfigureResponse configure(String configuration) {
        return stub().configure(PluginProto.ConfigureRequest.newBuilder().setConfiguration(configuration).addAllGrantedPermissions(PERMISSIONS)
                .build());
    }

    /** 握手之后配置并启动。 */
    void configureAndStart(String configuration) {
        PluginProto.ConfigureResponse configured = configure(configuration);
        assertTrue(configured.getStatus().getOk(), "Configure must succeed: " + configured);
        PluginProto.StartResponse started = stub().start(PluginProto.StartRequest.getDefaultInstance());
        assertTrue(started.getStatus().getOk(), "Start must succeed: " + started);
    }

    /** 关闭 stdin：运行时随之收尾。 */
    void closeStdin() throws IOException {
        stdin.close();
    }

    /** 运行时写到 stderr 的日志。 */
    String logs() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        boolean stopped;
        try {
            stdin.close();
            stopped = runtime.awaitStopped(Duration.ofSeconds(15));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the runtime to stop.", interrupted);
        } finally {
            channel.shutdownNow();
            executor.shutdown();
        }
        assertTrue(stopped, "the runtime must stop after stdin is closed");
    }

    /** 一次调用的请求。 */
    static PluginProto.InvokeRequest invokeRequest(String operation, String payload) {
        return PluginProto.InvokeRequest.newBuilder().setOperation(operation).setPayload(ByteString.copyFromUtf8(payload)).build();
    }

    /** 一次投递的请求：信封带 ID 与 {@code {"n":1}} 的数据，元数据带租户 {@code t1}。 */
    static PluginProto.HandleEventRequest eventRequest(String topic, String type, String mode) {
        return PluginProto.HandleEventRequest.newBuilder()
                .setEnvelope(PluginProto.Envelope.newBuilder().setTopic(topic).setId("e1").setSource("urn:test").setType(type).setSpecVersion("1.0")
                        .setData(ByteString.copyFromUtf8("{\"n\":1}")))
                .setMetadata(PluginProto.InvocationMetadata.newBuilder().putBaggage(BaggageKeys.TENANT, "t1"))
                .setAttempt(1)
                .setMode(mode)
                .build();
    }

    /** 投递一次，返回状态。 */
    PluginProto.Status deliver(String topic, String type, String mode) {
        return stub().handleEvent(eventRequest(topic, type, mode)).getStatus();
    }

    /** 把错误的 {@code details} 读成 JSON 值，按值比较。 */
    static JsonNode details(PluginProto.Error error) {
        try {
            return JSON.readTree(error.getDetails());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** 把期望的细节写成 JSON 值。 */
    static JsonNode object(String... pairs) {
        ObjectNode node = JSON.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) {
            node.put(pairs[index], pairs[index + 1]);
        }
        return node;
    }

    /** 开一个流式调用，发出 {@code first}（{@code null} 表示不发帧就关闭发送方向），返回流的结局。 */
    static Status.Code stream(PluginGrpc.PluginStub stub, PluginProto.StreamFrame first) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Status.Code> outcome = new AtomicReference<>();
        StreamObserver<PluginProto.StreamFrame> requests = stub.invokeStream(new StreamObserver<>() {
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
        if (first == null) {
            requests.onCompleted();
        } else {
            requests.onNext(first);
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "the stream must end");
        if (first != null) {
            requests.onCompleted();
        }
        return outcome.get();
    }

    private static Metadata headers(String... tokens) {
        Metadata headers = new Metadata();
        for (String token : tokens) {
            headers.put(SessionGuard.KEY, token);
        }
        return headers;
    }
}
