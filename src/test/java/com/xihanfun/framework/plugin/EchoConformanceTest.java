package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.examples.echo.EchoPlugin;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.manifest.Manifest;
import com.xihanfun.framework.manifest.ManifestJson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一致性：把示例插件 echo 放进临时目录，用规范仓库的黑盒运行器（{@code spec/conformance/cmd/xihan-conformance}，版本即锁定的 submodule）
 * 跑全部用例，断言全部通过、没有跳过（RFC-0017「一致性」，{@code invocation.md} §7）。
 *
 * <p>运行器是 Go 程序：没有 Go 时本测试失败，只有设了环境变量 {@code XIHAN_CONFORMANCE_REQUIRED=0} 才跳过。
 * 清单的 {@code entry} 按当前平台指向生成的启动脚本（Unix 上是带 shebang 的 sh 脚本，Windows 上是 {@code .cmd}）；
 * 宿主只继承 {@code handshake.md} §1 列出的环境变量，所以脚本里写 {@code java} 的绝对路径，类路径写进 Java 的参数文件。
 */
class EchoConformanceTest {
    /** 运行器的全部 14 个用例，按执行顺序（{@code spec/conformance/README.md}）。 */
    private static final List<String> CASES = List.of("handshake", "refusal", "session", "lifecycle", "order", "unknown-operation",
            "declared-operations", "unavailable", "event", "unsubscribed", "stream", "configuration", "stop-early", "stdin");

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    @Test
    void echoPassesEveryCaseOfTheBlackBoxRunner(@TempDir Path directory) throws Exception {
        Optional<Path> go = findGo();
        // 缺 Go 默认失败，门禁不因环境里没有运行器而静默变绿；只有显式设为 0 才跳过
        boolean required = !"0".equals(System.getenv("XIHAN_CONFORMANCE_REQUIRED"));
        if (go.isEmpty() && !required) {
            System.out.println("[EchoConformanceTest] skipped: Go is not on PATH and XIHAN_CONFORMANCE_REQUIRED=0");
        }
        Assumptions.assumeTrue(go.isPresent() || required,
                "Go is not on PATH and XIHAN_CONFORMANCE_REQUIRED=0; the black-box runner of framework-spec is a Go program.");
        assertTrue(go.isPresent(), "Go is not on PATH; the black-box runner of framework-spec is a Go program. "
                + "Install Go, or set XIHAN_CONFORMANCE_REQUIRED=0 to skip this test.");
        Path root = Vectors.root();
        Path conformance = root.resolve("spec").resolve("conformance");

        String entry = WINDOWS ? "echo.cmd" : "echo";
        Manifest manifest = ManifestJson.readFile(root.resolve("examples/echo/xihan.manifest.json")).toBuilder().entry(entry).build();
        Files.writeString(directory.resolve(Manifest.FILE_NAME), ManifestJson.write(manifest), StandardCharsets.UTF_8);
        writeLauncher(directory, entry);

        List<String> command = new ArrayList<>(List.of(go.get().toString(), "run", "./cmd/xihan-conformance", "plugin",
                "--dir", directory.toString(), "--json", "--quiet",
                "--config", argument("{\"greeting\":\"hello\"}"),
                "--sample", argument("echoes.create={\"text\":\"conformance\"}"),
                "--exclude", "echoes.crash"));
        Process process = new ProcessBuilder(command).directory(conformance.toFile()).start();
        process.getOutputStream().close();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        assertTrue(process.waitFor(10, TimeUnit.MINUTES), "the black-box runner did not finish within 10 minutes");
        String output = stdout.get();
        String diagnostics = stderr.get();
        System.out.println("[EchoConformanceTest] " + output.strip());

        JsonNode results = new ObjectMapper().readTree(output.isBlank() ? "[]" : output);
        assertTrue(results.isArray(), "the runner must print a JSON array; stderr:\n" + diagnostics);
        List<String> seen = new ArrayList<>();
        for (JsonNode result : results) {
            String name = result.path("case").asText();
            seen.add(name);
            assertTrue(result.path("passed").asBoolean(), "case '" + name + "' failed: " + result.path("message").asText());
            assertTrue(!result.path("skipped").asBoolean(), "case '" + name + "' was skipped: " + result.path("message").asText());
        }
        assertEquals(CASES, seen, "the runner must report every case; stderr:\n" + diagnostics);
        assertEquals(0, process.exitValue(), "the runner exits with 0 when every case passes; stderr:\n" + diagnostics);
    }

    /**
     * 写启动脚本：{@code java} 的绝对路径与参数文件（类路径与主类）都是绝对的，不依赖 {@code JAVA_HOME} 与工作目录之外的任何东西。
     */
    private static void writeLauncher(Path directory, String entry) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        Path arguments = directory.resolve("echo.args");
        // 参数文件里的引号内反斜杠是转义符，Windows 路径一律换成正斜杠，JVM 照样认
        String classpath = String.join(File.pathSeparator, classpath()).replace('\\', '/');
        // JDK 23 起 Netty 用到的 sun.misc.Unsafe 内存访问会在 stderr 打告警（JEP 471、498），宿主会把它们记成日志；这里显式允许，JDK 21 没有这个选项
        String options = Runtime.version().feature() >= 23 ? "--sun-misc-unsafe-memory-access=allow\n" : "";
        Files.writeString(arguments, options + "-cp \"" + classpath + "\"\n" + EchoPlugin.class.getName() + "\n", StandardCharsets.UTF_8);
        Path launcher = directory.resolve(entry);
        if (WINDOWS) {
            Files.writeString(launcher, "@echo off\r\n\"" + java + "\" \"@" + arguments + "\" %*\r\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(launcher, "#!/bin/sh\nexec " + shellQuote(java.toString()) + " " + shellQuote("@" + arguments) + " \"$@\"\n",
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * 测试 JVM 的类路径（含 echo 与全部依赖），写成绝对路径：Surefire 把 {@code java.class.path} 设为真实的测试类路径。
     */
    private static List<String> classpath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry).toAbsolutePath().toString());
            }
        }
        return entries;
    }

    /** 在 PATH 里找 go。 */
    private static Optional<Path> findGo() {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory.replace("\"", ""), WINDOWS ? "go.exe" : "go");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Windows 上 {@link ProcessBuilder} 不转义参数里的双引号，Go 按 CommandLineToArgvW 的规则解析命令行：先写成 {@code \"}。
     */
    private static String argument(String value) {
        return WINDOWS ? value.replace("\"", "\\\"") : value;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static CompletableFuture<String> read(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failed) {
                return "";
            }
        });
    }
}
