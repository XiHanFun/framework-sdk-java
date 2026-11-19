package com.xihanfun.framework;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 向量全集：{@code spec/conformance/vectors} 下实际存在的每个向量文件，要么有本 SDK 的测试回放，要么写明理由豁免。
 *
 * <p>规范新增向量文件时本测试失败：补上回放并写进 {@link #REPLAYED}，或写进 {@link #EXEMPTED} 并给出理由；
 * 规范删掉或改名了列出的文件时也失败。路径相对 {@code vectors/}，以 {@code /} 分隔。
 */
class VectorCoverageTest {
    /** 本 SDK 的测试回放的向量文件，注释是读取它的测试。 */
    private static final Set<String> REPLAYED = Set.of(
            // ManifestVectorsTest
            "manifest/manifests.json",
            // PayloadReasonsVectorsTest
            "operations/payload-reasons.json",
            // OrderingVectorsTest
            "pipeline/ordering.json",
            // HostCallbackTest
            "plugin/callback-status.json",
            // PluginTypesTest
            "plugin/deadlines.json",
            // HostCallbackTest
            "plugin/error-reading.json",
            // PluginTypesTest
            "plugin/handshake-lines.json",
            // PluginTypesTest
            "plugin/host-addresses.json",
            // PluginTypesTest
            "plugin/plugin-messages.json",
            // PluginRuntimeTest
            "plugin/protocol-versions.json",
            // PrimitivesTest、ContextTest
            "primitives/baggage-keys.json",
            // ManifestTest
            "primitives/capability-requirements.json",
            // PrimitivesTest
            "primitives/error-codes.json",
            // JsonTextVectorsTest
            "primitives/json-text.json",
            // KernelMessagesVectorsTest、KernelMessagesTest、ContextTest、ManifestVectorsTest
            "primitives/kernel-messages.json",
            // PrimitivesTest
            "primitives/lifecycle-transitions.json",
            // MessageTemplateVectorsTest
            "primitives/message-templates.json",
            // ContextTest
            "primitives/trace-context.json",
            // WireEncodingVectorsTest
            "primitives/wire-encoding.json");

    /** 不回放的向量文件与理由。 */
    private static final Map<String, String> EXEMPTED = Map.of(
            "plugin/wasm-exports.json", "Wasm ABI exports are checked by hosts; this SDK has no Wasm plugin runtime.",
            "provider/caching-distributed.json", "This SDK does not provide the caching.distributed@1 remote provider.",
            "security/webauthn.json", "WebAuthn verification is host-side (RFC-0008); this SDK does not verify credentials.");

    @Test
    void replayedAndExemptedFilesDoNotOverlap() {
        Set<String> both = new TreeSet<>(REPLAYED);
        both.retainAll(EXEMPTED.keySet());
        assertTrue(both.isEmpty(), "vector files both replayed and exempted: " + String.join(", ", both));
    }

    @Test
    void everyListedVectorFileExists() throws IOException {
        Set<String> missing = new TreeSet<>(REPLAYED);
        missing.addAll(EXEMPTED.keySet());
        missing.removeAll(files());
        assertTrue(missing.isEmpty(), "listed vector files that the spec does not have: " + String.join(", ", missing));
    }

    @Test
    void everyVectorFileIsReplayedOrExempted() throws IOException {
        Set<String> unlisted = files();
        unlisted.removeAll(REPLAYED);
        unlisted.removeAll(EXEMPTED.keySet());
        assertTrue(unlisted.isEmpty(), "spec vector files neither replayed nor exempted: " + String.join(", ", unlisted));
    }

    /**
     * 递归列出 {@code spec/conformance/vectors} 下的全部 {@code .json} 文件，路径相对 {@code vectors/}、以 {@code /} 分隔。
     */
    private static Set<String> files() throws IOException {
        Path vectors = root().resolve("spec/conformance/vectors");
        if (!Files.isDirectory(vectors)) {
            throw new IllegalStateException("The conformance vectors '" + vectors + "' are missing; run 'git submodule update --init'.");
        }
        try (Stream<Path> paths = Files.walk(vectors)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> vectors.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * 仓库根：Maven 经系统属性传入；在 IDE 里直接运行时从工作目录向上找含 spec/ 的目录。
     */
    private static Path root() {
        String configured = System.getProperty("xihan.repository.root");
        if (configured != null) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("spec/conformance"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("The repository root with 'spec/' could not be found.");
        }
        return current;
    }
}
