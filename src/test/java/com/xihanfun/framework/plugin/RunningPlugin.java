package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.plugin.v1.PluginGrpc;
import com.xihanfun.framework.plugin.v1.PluginProto;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试夹具：在本进程里启动一个插件运行时，换掉环境变量与标准输入输出，再以宿主的身份连上它的端点。
 */
final class RunningPlugin implements AutoCloseable {
    /** 会话令牌。 */
    static final String TOKEN = "dGVzdC1zZXNzaW9uLXRva2VuLTAxMjM0NTY3ODlhYmNkZWY";

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
                "operations": [ "samples.echo", "samples.fail", "samples.relay", "samples.publish", "samples.context", "samples.wait" ],
                "config": { "section": "XiHan:Sample" },
                "health": [ "sample" ]
              },
              "permissions": [ "capability:kernel.pipeline", "capability:messaging.queue" ],
              "entry": "sample"
            }
            """;

    /** 握手行的文法（handshake.md §2）。 */
    static final Pattern HANDSHAKE_LINE = Pattern.compile("^xihan-plugin/1 (unix|tcp|npipe) (\\S+)$");

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
        Matcher matcher = HANDSHAKE_LINE.matcher(line);
        assertTrue(matcher.matches(), "the handshake line must match the grammar: " + line);
        Endpoint endpoint = new Endpoint(Network.parse(matcher.group(1)).orElseThrow(), matcher.group(2));
        this.channel = Transport.dial(endpoint, executor).channel();
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
                .bridgePlatformLogging(false).build());
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

    /** 带给定令牌的阻塞客户端；为 {@code null} 时不带令牌。 */
    PluginGrpc.PluginBlockingStub stub(String token) {
        PluginGrpc.PluginBlockingStub stub = PluginGrpc.newBlockingStub(channel);
        return token == null ? stub : stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(token)));
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

    /** 握手之后配置并启动。 */
    void configureAndStart(String configuration) {
        PluginProto.ConfigureResponse configured = stub().configure(PluginProto.ConfigureRequest.newBuilder().setConfiguration(configuration).build());
        assertTrue(configured.getStatus().getOk(), "Configure must succeed: " + configured);
        PluginProto.StartResponse started = stub().start(PluginProto.StartRequest.getDefaultInstance());
        assertTrue(started.getStatus().getOk(), "Start must succeed: " + started);
    }

    /** 关闭 stdin，等运行时停下。 */
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

    private static Metadata headers(String token) {
        Metadata headers = new Metadata();
        headers.put(SessionGuard.KEY, token);
        return headers;
    }
}
