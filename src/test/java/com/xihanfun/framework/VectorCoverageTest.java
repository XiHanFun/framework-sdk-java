package com.xihanfun.framework;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 按认领的一致性档核对向量（RFC-0024「一致性档」）：档表 {@code spec/conformance/profiles.json} 是唯一事实源，
 * 认领的档要求的每个向量要么有本 SDK 的测试回放，要么列为缺口并写明理由。
 *
 * <p>规范在认领的档里新增向量时本测试失败：补上回放并写进 {@link #REPLAYED}，或写进 {@link #GAPS} 并给出理由；
 * 规范删掉或改名了回放的文件时也失败。回放认领档以外的向量是允许的。路径相对 {@code vectors/}，以 {@code /} 分隔。
 */
class VectorCoverageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本 SDK 认领的档：{@code core} 是所有实现都认领的档，{@code plugin} 是用本 SDK 写进程外插件（RFC-0024「一致性档」第 2 条）。 */
    private static final Set<String> CLAIMED = Set.of("core", "plugin");

    /** 本 SDK 的测试回放的向量文件，注释是读取它的测试。 */
    private static final Set<String> REPLAYED = Set.of(
            // ManifestVectorsTest
            "manifest/manifests.json",
            // PayloadReasonsVectorsTest
            "operations/payload-reasons.json",
            // OrderingVectorsTest
            "pipeline/ordering.json",
            // BindingPropertiesVectorsTest（reading；其余各节是宿主一侧的）
            "plugin/binding-properties.json",
            // HostCallbackTest
            "plugin/callback-status.json",
            // PluginTypesTest
            "plugin/deadlines.json",
            // EnvelopeTest（valid 与 invalid：SDK 读宿主写来的 time 是同一条文法；written 是宿主的写法，SDK 读得出）
            "plugin/envelope-times.json",
            // HostCallbackTest
            "plugin/error-reading.json",
            // HostCallbackTest（utf16 一组，经 Wire.error）
            "plugin/error-text-writing.json",
            // PluginTypesTest
            "plugin/handshake-lines.json",
            // PluginTypesTest
            "plugin/host-addresses.json",
            // PluginTypesTest
            "plugin/plugin-messages.json",
            // PluginRuntimeTest
            "plugin/protocol-versions.json",
            // EnvelopeTest（取 nanoseconds 一项）、PluginCallsTest（unreadable 经 gRPC 投递）
            "plugin/sdk-envelope-times.json",
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
            // OperationIdVectorsTest
            "primitives/operation-ids.json",
            // ContextTest
            "primitives/trace-context.json",
            // WireEncodingVectorsTest
            "primitives/wire-encoding.json");

    /** 缺口：认领的档要求、本 SDK 没有回放的向量与理由（RFC-0024「实现钉规范」第 3 条）；目前没有。 */
    private static final Map<String, String> GAPS = Map.of();

    @Test
    void claimedProfilesAreDefinedByTheSpec() throws IOException {
        Set<String> undefined = new TreeSet<>(CLAIMED);
        undefined.removeAll(profiles().keySet());
        assertTrue(undefined.isEmpty(), "claimed profiles that the spec does not define: " + String.join(", ", undefined));
    }

    @Test
    void everyVectorOfTheClaimedProfilesIsReplayedOrAGap() throws IOException {
        Set<String> uncovered = required();
        uncovered.removeAll(REPLAYED);
        uncovered.removeAll(GAPS.keySet());
        assertTrue(uncovered.isEmpty(),
                "vector files of the claimed profiles that are neither replayed nor listed as gaps: " + String.join(", ", uncovered));
    }

    @Test
    void gapsBelongToTheClaimedProfiles() throws IOException {
        Set<String> outside = new TreeSet<>(GAPS.keySet());
        outside.removeAll(required());
        assertTrue(outside.isEmpty(), "gaps outside the claimed profiles: " + String.join(", ", outside));
    }

    @Test
    void gapsAreNotReplayed() {
        Set<String> replayed = new TreeSet<>(GAPS.keySet());
        replayed.retainAll(REPLAYED);
        assertTrue(replayed.isEmpty(), "gaps that are replayed; remove them from gaps: " + String.join(", ", replayed));
    }

    @Test
    void everyReplayedVectorFileExists() throws IOException {
        Set<String> missing = new TreeSet<>(REPLAYED);
        missing.removeAll(files());
        assertTrue(missing.isEmpty(), "replayed vector files that the spec does not have: " + String.join(", ", missing));
    }

    /**
     * 认领的档要求回放的向量：档表里认领的各档 {@code vectors} 的并集；档表里没有的档不贡献向量，由另一个测试报出。
     */
    private static Set<String> required() throws IOException {
        Map<String, Set<String>> profiles = profiles();
        Set<String> required = new TreeSet<>();
        for (String id : CLAIMED) {
            required.addAll(profiles.getOrDefault(id, Set.of()));
        }
        return required;
    }

    /**
     * 读档表 {@code spec/conformance/profiles.json}：档 ID 到该档必须回放的向量。
     */
    private static Map<String, Set<String>> profiles() throws IOException {
        Path file = Vectors.root().resolve("spec/conformance/profiles.json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("The conformance profiles '" + file + "' are missing; run 'git submodule update --init'.");
        }
        Map<String, Set<String>> profiles = new TreeMap<>();
        for (JsonNode profile : MAPPER.readTree(file.toFile()).path("profiles")) {
            Set<String> vectors = new TreeSet<>();
            profile.path("vectors").forEach(vector -> vectors.add(vector.asText()));
            profiles.put(profile.path("id").asText(), vectors);
        }
        return profiles;
    }

    /**
     * 递归列出 {@code spec/conformance/vectors} 下的全部 {@code .json} 文件，路径相对 {@code vectors/}、以 {@code /} 分隔。
     */
    private static Set<String> files() throws IOException {
        Path vectors = Vectors.root().resolve("spec/conformance/vectors");
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
}
