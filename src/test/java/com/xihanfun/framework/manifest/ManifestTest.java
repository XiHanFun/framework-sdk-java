package com.xihanfun.framework.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.KernelMessages;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 清单的读取、写出与校验：读取的错误与写出的排版按 RFC-0002（ADR-0236），与 .NET 的 ManifestReader 相同；逐字的期望另见 ManifestVectorsTest。
 */
class ManifestTest {
    private static final String ECHO_MANIFEST = """
            {
              "$schema": "https://spec.xihanfun.com/manifest/v1.json",
              "id": "xihan.samples.echo",
              "package": "EchoPlugin",
              "version": "5.0.0-preview.1",
              "primitives": "1",
              "stability": "experimental",
              "kind": "plugin",
              "isolation": "out-of-process",
              "requires": [ { "id": "kernel.plugins", "version": "1" } ],
              "contributes": {
                "operations": [ "echoes.create", "echoes.relay", "echoes.crash", "events.list" ],
                "config": { "section": "XiHan:Echo" },
                "health": [ "echo" ]
              },
              "permissions": [ "capability:kernel.pipeline" ],
              "activation": [ "onStartup" ],
              "entry": "EchoPlugin"
            }""";

    /** 覆盖全部字段与字符串转义的清单。 */
    private static final String FULL_MANIFEST = "{\"id\":\"xihan.caching.redis\",\"package\":\"A<b>&c \\\"q\\\" \\\\ \u2028 tab\\t\",\"version\":\"1.2.3+build.5\",\"primitives\":\"1\",\"stability\":\"preview\",\"kind\":\"provider\",\"role\":\"default\",\"provides\":[{\"id\":\"caching.distributed\",\"version\":\"2\"},{\"id\":\"caching.local\",\"version\":\"1\"}],\"requires\":[{\"id\":\"kernel.hosting\",\"version\":\"^1\"},{\"id\":\"multitenancy.context\",\"version\":\"1\",\"optional\":true}],\"bindings\":[{\"name\":\"cache\",\"type\":\"cache:distributed\"},{\"name\":\"db\",\"type\":\"connection:relational\",\"required\":false}],\"contributes\":{\"pipeline\":[{\"middleware\":\"audit\",\"stage\":\"observe\",\"before\":[\"x\"],\"after\":[\"y\",\"z\"]}],\"config\":{\"section\":\"XiHan:Caching:Redis\",\"schema\":\"{}\"},\"permissions\":[\"caching.read\"],\"health\":[\"caching.redis.connection\"]},\"activation\":[\"onOperation:cache-entries.*\"],\"entry\":\"X\"}";

    /** FULL_MANIFEST 按 RFC-0002「写出」的排版：列表总写，缺省值写出，字符串只转义必须转义的字符（U+2028 与 {@code <>&} 原样；文本块里写不进 U+2028，用 {@code <LS>} 代替）。 */
    private static final String FULL_WRITTEN = """
            {
              "$schema": "https://spec.xihanfun.com/manifest/v1.json",
              "id": "xihan.caching.redis",
              "package": "A<b>&c \\"q\\" \\\\ <LS> tab\\t",
              "version": "1.2.3+build.5",
              "primitives": "1",
              "stability": "preview",
              "kind": "provider",
              "role": "default",
              "provides": [
                {
                  "id": "caching.distributed",
                  "version": "2"
                },
                {
                  "id": "caching.local",
                  "version": "1"
                }
              ],
              "requires": [
                {
                  "id": "kernel.hosting",
                  "version": "1",
                  "optional": false
                },
                {
                  "id": "multitenancy.context",
                  "version": "1",
                  "optional": true
                }
              ],
              "bindings": [
                {
                  "name": "cache",
                  "type": "cache:distributed",
                  "required": true
                },
                {
                  "name": "db",
                  "type": "connection:relational",
                  "required": false
                }
              ],
              "contributes": {
                "pipeline": [
                  {
                    "middleware": "audit",
                    "stage": "observe",
                    "before": [
                      "x"
                    ],
                    "after": [
                      "y",
                      "z"
                    ]
                  }
                ],
                "operations": [],
                "config": {
                  "section": "XiHan:Caching:Redis",
                  "schema": "{}"
                },
                "permissions": [
                  "caching.read"
                ],
                "health": [
                  "caching.redis.connection"
                ]
              },
              "activation": [
                "onOperation:cache-entries.*"
              ],
              "permissions": [],
              "entry": "X"
            }""".replace("<LS>", "\u2028");

    @Test
    void readParsesAPluginManifest() {
        Manifest manifest = ManifestJson.read(ECHO_MANIFEST);
        assertEquals("xihan.samples.echo", manifest.id());
        assertEquals(ManifestKind.PLUGIN, manifest.kind());
        assertEquals(Optional.of(PluginIsolation.OUT_OF_PROCESS), manifest.isolation());
        assertEquals(1, manifest.primitives());
        assertEquals(Stability.EXPERIMENTAL, manifest.stability());
        assertEquals(List.of(CapabilityRequirement.of("kernel.plugins", 1)), manifest.requires());
        assertEquals("kernel.plugins@1", manifest.requires().get(0).toString());
        assertEquals(4, manifest.operations().size());
        assertEquals(Optional.of("XiHan:Echo"), manifest.configSection());
        assertEquals(List.of("echo"), manifest.health());
        assertTrue(manifest.activatesOnStartup());
        assertEquals(ManifestRole.PROVIDER, manifest.effectiveRole());
        assertEquals("samples", ManifestId.domainOf(manifest.id()));
        assertEquals("caching", CapabilityId.domainOf("caching.distributed"));

        String written = ManifestJson.write(manifest);
        Manifest again = ManifestJson.read(written);
        assertEquals(manifest, again, "round trip");
        assertTrue(written.contains("\"primitives\": \"1\""));
        assertTrue(written.contains("\"stability\": \"experimental\""));
    }

    @Test
    void canonicalOutputMatchesGoForEveryField() {
        Manifest manifest = ManifestJson.read(FULL_MANIFEST);
        assertEquals(FULL_WRITTEN, ManifestJson.write(manifest));
        assertEquals(manifest, ManifestJson.read(ManifestJson.write(manifest)));
        assertEquals(Optional.of(false), manifest.bindings().get(1).required());
        assertFalse(manifest.bindings().get(1).isRequired());
        assertTrue(manifest.bindings().get(0).isRequired());
        assertEquals(List.of("onOperation:cache-entries.*"), manifest.effectiveActivation());
        assertFalse(manifest.activatesOnStartup());
        String minimal = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\"";
        assertEquals(ManifestJson.write(ManifestJson.read(minimal + "}")), ManifestJson.write(ManifestJson.read(minimal + ",\"contributes\":{}}")),
                "empty and missing contributions are written the same");
        assertEquals(ManifestJson.read(minimal + "}"), ManifestJson.read(minimal + ",\"role\":\"provider\",\"activation\":[\"onStartup\"]}"),
                "defaults compare equal to the written defaults");
    }

    @Test
    void configSectionMayHaveSubsections() {
        String template = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"contributes\":{\"config\":{\"section\":\"%s\"}}}";
        for (String section : List.of("XiHan:Caching", "XiHan:Caching:Redis", "XiHan:Protocols:CloudEvents", "XiHan:ObjectStorage:SignedUrls", "XiHan:A:B2:C")) {
            Manifest manifest = assertDoesNotThrow(() -> ManifestJson.read(template.formatted(section)), section);
            assertEquals(Optional.of(section), manifest.configSection());
        }
        for (String section : List.of("Orders", "XiHan", "XiHan:", "XiHan:caching", "XiHan:Caching:", "XiHan:Caching::Redis", "XiHan:Caching:redis",
                "XiHan:Caching:Re-dis", "xihan:Caching", "XiHan:Caching:Redis:")) {
            assertRejected(template.formatted(section), section);
        }
    }

    @Test
    void contributedPermissionCodesFollowTheHostGrammar() {
        String template = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"plugin\",\"isolation\":\"out-of-process\",\"contributes\":{\"permissions\":%s}}";
        for (String codes : List.of("[]", "[\"orders.read\"]", "[\"sales.orders.read\",\"sales.orders.write\"]", "[\"echo2.items-v2.read\"]")) {
            Manifest manifest = assertDoesNotThrow(() -> ManifestJson.read(template.formatted(codes)), codes);
            assertTrue(manifest.contributes().isPresent(), codes + " read without contributions");
        }
        for (String codes : List.of("[\"orders\"]", "[\"Orders.read\"]", "[\"orders.Read\"]", "[\"orders..read\"]", "[\"orders.read.\"]", "[\".orders.read\"]",
                "[\"1orders.read\"]", "[\"orders.1read\"]", "[\"orders_x.read\"]", "[\"orders:read\"]", "[\"orders.read\",\"orders.read\"]",
                "[\"orders.read\",\"orders.write\",\"orders.read\"]")) {
            assertRejected(template.formatted(codes), codes);
        }
    }

    @Test
    void readRejectsInvalidManifests() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("unknown field", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"extra\":1}"),
                Map.entry("bad id", "{\"id\":\"a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\"}"),
                Map.entry("bad version", "{\"id\":\"xihan.a.b\",\"version\":\"1.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\"}"),
                Map.entry("bad primitives", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"0\",\"stability\":\"stable\",\"kind\":\"capability\"}"),
                Map.entry("bad stability", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"solid\",\"kind\":\"capability\"}"),
                Map.entry("bad kind", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"thing\"}"),
                Map.entry("plugin needs isolation", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"plugin\"}"),
                Map.entry("non-plugin isolation", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"isolation\":\"wasm\"}"),
                Map.entry("non-plugin permissions", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"permissions\":[\"capability:x\"]}"),
                Map.entry("duplicate provides", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"provides\":[{\"id\":\"a.b\",\"version\":\"1\"},{\"id\":\"a.b\",\"version\":\"2\"}]}"),
                Map.entry("duplicate requires", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"requires\":[{\"id\":\"a.b\",\"version\":\"1\"},{\"id\":\"a.b\",\"version\":\"^1\"}]}"),
                Map.entry("bad activation", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"plugin\",\"isolation\":\"wasm\",\"activation\":[\"onBoot\"]}"),
                Map.entry("bad permission", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"plugin\",\"isolation\":\"wasm\",\"permissions\":[\"sudo\"]}"),
                Map.entry("bad operation", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"contributes\":{\"operations\":[\"Orders.Create\"]}}"),
                Map.entry("bad section", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"contributes\":{\"config\":{\"section\":\"Orders\"}}}"),
                Map.entry("bad stage", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"contributes\":{\"pipeline\":[{\"middleware\":\"x\",\"stage\":\"middle\"}]}}"),
                Map.entry("bad binding", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"bindings\":[{\"name\":\"Db\",\"type\":\"connection:relational\"}]}"),
                // Java 侧按 Schema 更严格的几处：数字类型、重复键、尾随内容、显式 null、提供的接口版本不许 ^
                Map.entry("number primitives", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":1,\"stability\":\"stable\",\"kind\":\"capability\"}"),
                Map.entry("duplicate key", "{\"id\":\"xihan.a.b\",\"id\":\"xihan.a.c\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\"}"),
                Map.entry("trailing content", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\"} {}"),
                Map.entry("null package", "{\"id\":\"xihan.a.b\",\"package\":null,\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\"}"),
                Map.entry("caret in provides", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"provides\":[{\"id\":\"a.b\",\"version\":\"^1\"}]}"),
                Map.entry("missing kind", "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\"}"),
                Map.entry("not an object", "[]"),
                Map.entry("empty", ""),
                Map.entry("not json", "{"));
        cases.forEach((name, json) -> assertRejected(json, name));
    }

    @Test
    void contributedOperationsFollowTheOperationIdGrammar() {
        // RFC-0002 解析规则 2（ADR-0233）：contributes.operations 的每一项符合操作 ID 文法，比 Schema 以前的写法严格
        String template = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"contributes\":{\"operations\":[\"%s\"]}}";
        for (String id : List.of("orders.create", "order-lines.list", "items-1.list", "a1.b2")) {
            assertDoesNotThrow(() -> ManifestJson.read(template.formatted(id)), id);
        }
        for (String id : List.of("orders-.create", "orders.create-", "order--lines.list", "orders.-create", "-orders.create", "orders", "1orders.create", "orders.Create")) {
            assertRejected(template.formatted(id), id);
        }
    }

    @Test
    void requirementsFollowTheVectors() throws IOException {
        JsonNode vectors = Vectors.read("primitives/capability-requirements.json");
        for (JsonNode valid : vectors.get("valid")) {
            CapabilityRequirement requirement = CapabilityRequirement.tryParse(valid.get("input").textValue())
                    .orElseThrow(() -> new AssertionError(valid + " must parse"));
            assertEquals(valid.get("id").textValue(), requirement.id());
            assertEquals(valid.get("version").intValue(), requirement.version());
            assertEquals(valid.get("optional").booleanValue(), requirement.optional());
        }
        for (String text : Vectors.strings(vectors.get("invalid"))) {
            assertEquals(Optional.empty(), CapabilityRequirement.tryParse(text), text + " must not parse");
            assertThrows(IllegalArgumentException.class, () -> CapabilityRequirement.parse(text));
        }
        CapabilityDeclaration declaration = CapabilityDeclaration.parse("caching.distributed@2");
        assertEquals("caching.distributed@2", declaration.toString());
        CapabilityRequirement requirement = CapabilityRequirement.parse("caching.distributed@^2?");
        assertTrue(requirement.isSatisfiedBy(declaration));
        assertEquals("caching.distributed@2?", requirement.toString());
        assertFalse(requirement.isSatisfiedBy(CapabilityDeclaration.parse("caching.distributed@1")), "versions must match");
        assertEquals(Optional.empty(), CapabilityDeclaration.tryParse("caching.distributed@^2"));
        assertEquals(Optional.empty(), CapabilityRequirement.tryParse("kernel.hosting@99999999999"), "out of range");
    }

    @Test
    void middlewareOfNonWasmPluginsIsLeftToTheHost() {
        // 只有 Wasm 插件能贡献中间件，但由宿主在发现插件时拒绝（PLUGINS_VALIDATION_001）；读取与构建清单不核对，与 .NET 一致（ADR-0236）
        String template = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"%s\"%s,\"contributes\":{\"pipeline\":%s}}";
        String audit = "[{\"middleware\":\"audit\",\"stage\":\"observe\"},{\"middleware\":\"tenant-guard\",\"stage\":\"tenant\"}]";
        Map<String, String> accepted = Map.of(
                "wasm plugin", template.formatted("plugin", ",\"isolation\":\"wasm\"", audit),
                "out-of-process plugin", template.formatted("plugin", ",\"isolation\":\"out-of-process\"", audit),
                "in-process plugin", template.formatted("plugin", ",\"isolation\":\"in-process\"", audit),
                "capability", template.formatted("capability", "", audit));
        accepted.forEach((name, json) -> assertDoesNotThrow(() -> ManifestJson.read(json), name));
    }

    @Test
    void crossFieldConstraintsCarryTheDotNetKeys() {
        // 构建时的跨字段约束：PRIMITIVES_VALIDATION_001 加 .NET 的消息键与参数 id；读取时换成 KERNEL_CAPABILITY_006
        Manifest.Builder plugin = Manifest.builder().id("xihan.a.b").version("1.0.0").primitives(1).stability(Stability.STABLE)
                .kind(ManifestKind.PLUGIN);
        FrameworkException rejected = assertThrows(FrameworkException.class, plugin::build);
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
        assertEquals(Optional.of("primitives.manifest.isolation_required"), rejected.toError().messageKey());
        assertEquals(Map.of("id", "xihan.a.b"), rejected.toError().details());
        assertEquals("Plugin manifest 'xihan.a.b' must declare 'isolation'.", rejected.getMessage());
    }

    @Test
    void builtManifestsAreValidAndWritable() {
        Manifest manifest = Manifest.builder().id("xihan.orders.service").version("0.1.0").primitives(1).stability(Stability.PREVIEW)
                .kind(ManifestKind.PROVIDER).role(ManifestRole.DEFAULT)
                .provides(List.of(CapabilityDeclaration.of("orders.service", 1)))
                .requires(List.of(CapabilityRequirement.of("kernel.hosting", 1), CapabilityRequirement.of("multitenancy.context", 1).withOptional(true)))
                .bindings(List.of(Binding.of("db", "connection:relational").withRequired(true)))
                .contributes(Contributions.builder().operations(List.of("orders.create")).config(ConfigContribution.of("XiHan:Orders")).build())
                .build();
        assertEquals(manifest, ManifestJson.read(ManifestJson.write(manifest)));
        assertEquals(manifest, manifest.toBuilder().build());
        assertThrows(FrameworkException.class, () -> Manifest.builder().version("1.0.0").primitives(1).build(), "an ID is required");
        assertThrows(IllegalArgumentException.class, () -> Binding.of("Db", "connection"));
        assertThrows(IllegalArgumentException.class, () -> ConfigContribution.of("Orders"));
        assertThrows(IllegalArgumentException.class, () -> CapabilityDeclaration.of("caching.distributed", 0));
        assertThrows(IllegalArgumentException.class, () -> PipelineContribution.of("", PipelineStage.OBSERVE));
    }

    @Test
    void readFileReadsFromDisk(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(Manifest.FILE_NAME);
        Files.writeString(file, ECHO_MANIFEST);
        assertEquals("xihan.samples.echo", ManifestJson.readFile(file).id());

        // ReadFile 跳过开头的 UTF-8 BOM；Read 不跳过（RFC-0002 解析规则 2 第 1 步）
        byte[] text = ECHO_MANIFEST.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[text.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(text, 0, withBom, 3, text.length);
        Files.write(file, withBom);
        assertEquals("xihan.samples.echo", ManifestJson.readFile(file).id());
        FrameworkException bom = assertThrows(FrameworkException.class, () -> ManifestJson.read(withBom));
        assertEquals(Optional.of(ManifestJson.INVALID_JSON), bom.toError().messageKey());
        assertEquals(Map.of("reason", "$"), bom.toError().details());

        Files.write(file, new byte[] {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xFF, '"', '}'});
        assertEquals(Optional.of(ManifestJson.INVALID_JSON), assertThrows(FrameworkException.class, () -> ManifestJson.readFile(file))
                .toError().messageKey(), "a file that is not UTF-8 is not JSON");

        for (Path missing : List.of(directory.resolve("missing.json"), directory)) {
            FrameworkException rejected = assertThrows(FrameworkException.class, () -> ManifestJson.readFile(missing));
            assertEquals(ManifestJson.INVALID_MANIFEST, rejected.code());
            assertEquals(Optional.of(ManifestJson.FILE_MISSING), rejected.toError().messageKey());
            assertEquals(Map.of("path", missing.toString()), rejected.toError().details());
        }
    }

    @Test
    void tryReadReturnsFailuresAsResults(@TempDir Path directory) throws IOException {
        // 读取的失败是可恢复的失败（.NET 的 ManifestReader.Read / ReadFile 返回 Result<Manifest>）；read / readFile 是抛异常的包装
        Result<Manifest> read = ManifestJson.tryRead(ECHO_MANIFEST);
        assertEquals(ManifestJson.read(ECHO_MANIFEST), read.value().orElseThrow());
        assertEquals(read, ManifestJson.tryRead(ECHO_MANIFEST.getBytes(StandardCharsets.UTF_8)));

        String minimal = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":";
        String notJson = "The manifest is not valid JSON: $";
        assertFailure(ManifestJson.tryRead("{"), ManifestJson.INVALID_JSON, Map.of("reason", "$"), notJson);
        // 孤立的代理项编不成 UTF-8，按不是 JSON 处理
        assertFailure(ManifestJson.tryRead("\"\uD800\""), ManifestJson.INVALID_JSON, Map.of("reason", "$"), notJson);
        assertFailure(ManifestJson.tryRead("null"), ManifestJson.EMPTY, Map.of(), "The manifest is empty.");
        assertFailure(ManifestJson.tryRead(minimal + "\"capability\",\"extra\":1}"), ManifestJson.INVALID, Map.of("reason", "$.extra"),
                "The manifest is not valid: $.extra");
        // 跨字段约束：保留 primitives.manifest.* 的键与参数，错误码换成清单读取失败
        assertFailure(ManifestJson.tryRead(minimal + "\"plugin\"}"), KernelMessages.MANIFEST_ISOLATION_REQUIRED, Map.of("id", "xihan.a.b"),
                "Plugin manifest 'xihan.a.b' must declare 'isolation'.");

        Path file = directory.resolve(Manifest.FILE_NAME);
        Files.writeString(file, ECHO_MANIFEST);
        assertEquals("xihan.samples.echo", ManifestJson.tryReadFile(file).value().orElseThrow().id());
        Path missing = directory.resolve("missing.json");
        assertFailure(ManifestJson.tryReadFile(missing), ManifestJson.FILE_MISSING, Map.of("path", missing.toString()),
                "Manifest file '" + missing + "' does not exist.");
    }

    @Test
    void validateReturnsCrossFieldProblemsAsResults() {
        // .NET 的 Manifest.Validate：字段之间的约束以结果返回，按固定的顺序报第一处；build() 把同一个错误抛出
        Map<String, Manifest.Builder> cases = new LinkedHashMap<>();
        cases.put(KernelMessages.MANIFEST_ISOLATION_REQUIRED, minimal().kind(ManifestKind.PLUGIN));
        cases.put(KernelMessages.MANIFEST_ISOLATION_NOT_ALLOWED, minimal().kind(ManifestKind.CAPABILITY).isolation(PluginIsolation.WASM));
        cases.put(KernelMessages.MANIFEST_DUPLICATE_PROVIDES, minimal().kind(ManifestKind.CAPABILITY)
                .provides(List.of(CapabilityDeclaration.of("a.b", 1), CapabilityDeclaration.of("a.b", 2))));
        cases.put(KernelMessages.MANIFEST_DUPLICATE_REQUIRES, minimal().kind(ManifestKind.CAPABILITY)
                .requires(List.of(CapabilityRequirement.of("a.b", 1), CapabilityRequirement.of("a.b", 1).withOptional(true))));
        cases.put(KernelMessages.MANIFEST_DUPLICATE_BINDINGS, minimal().kind(ManifestKind.CAPABILITY)
                .bindings(List.of(Binding.of("db", "connection:relational"), Binding.of("db", "cache:distributed"))));
        cases.forEach((key, builder) -> {
            Error error = builder.validate().error().orElseThrow(() -> new AssertionError(key + " must be reported"));
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.code(), key);
            assertEquals(Optional.of(key), error.messageKey());
            assertEquals(Map.of("id", "xihan.a.b"), error.details(), key);
            FrameworkException thrown = assertThrows(FrameworkException.class, builder::build, key);
            assertEquals(error, thrown.toError(), key + ": build() throws the same error");
        });
        // 同时违反几条时只报顺序在前的那一条
        Manifest.Builder several = minimal().kind(ManifestKind.PLUGIN)
                .provides(List.of(CapabilityDeclaration.of("a.b", 1), CapabilityDeclaration.of("a.b", 2)));
        assertEquals(Optional.of(KernelMessages.MANIFEST_ISOLATION_REQUIRED), several.validate().error().orElseThrow().messageKey());
        assertEquals(Result.success(), minimal().kind(ManifestKind.CAPABILITY).validate());

        // 单个字段的格式不是可恢复的失败：配置或编程错误，validate() 与 build() 一样抛出，没有消息键
        Manifest.Builder withoutId = Manifest.builder().version("1.0.0").primitives(1);
        FrameworkException missingId = assertThrows(FrameworkException.class, withoutId::validate);
        assertEquals(ErrorCode.INVALID_ARGUMENT, missingId.code());
        assertEquals(Optional.empty(), missingId.toError().messageKey());
    }

    @Test
    void messageKeysAreTheSharedKernelKeys() {
        // 键的文本只在 KernelMessages 写一遍，ManifestJson 的常量引用它
        assertEquals(KernelMessages.MANIFEST_INVALID_JSON, ManifestJson.INVALID_JSON);
        assertEquals(KernelMessages.MANIFEST_EMPTY, ManifestJson.EMPTY);
        assertEquals(KernelMessages.MANIFEST_INVALID, ManifestJson.INVALID);
        assertEquals(KernelMessages.MANIFEST_FILE_MISSING, ManifestJson.FILE_MISSING);
    }

    @Test
    void idGrammarsAreSharedByDeclarationsAndRequirements() {
        // 能力 ID 的文法只有一份：声明与依赖的文本各自多出来的部分之外，接受的 ID 与 CapabilityId 相同
        for (String id : List.of("caching.distributed", "a1.b-2", "kernel.hosting")) {
            assertTrue(CapabilityId.isValid(id), id);
            assertTrue(CapabilityDeclaration.tryParse(id + "@1").isPresent(), id);
            assertTrue(CapabilityRequirement.tryParse(id + "@^1?").isPresent(), id);
        }
        List<String> invalid = List.of("caching", "Caching.distributed", "caching.Distributed", "caching..x", "1a.b", "a-b.c", "a.b.c",
                "a.b\n");
        for (String id : invalid) {
            assertFalse(CapabilityId.isValid(id), id);
            assertEquals(Optional.empty(), CapabilityDeclaration.tryParse(id + "@1"), id);
            assertEquals(Optional.empty(), CapabilityRequirement.tryParse(id + "@1"), id);
        }
    }

    @Test
    void enumsParseExactlyTheirWireNames() {
        // 各枚举的 parse 共用一个查找：整串相等、区分大小写，null 与未知的名字为空
        for (ManifestKind kind : ManifestKind.values()) {
            assertEquals(Optional.of(kind), ManifestKind.parse(kind.wireName()));
        }
        for (ManifestRole role : ManifestRole.values()) {
            assertEquals(Optional.of(role), ManifestRole.parse(role.wireName()));
        }
        for (PluginIsolation isolation : PluginIsolation.values()) {
            assertEquals(Optional.of(isolation), PluginIsolation.parse(isolation.wireName()));
        }
        assertEquals("out-of-process", PluginIsolation.OUT_OF_PROCESS.wireName());
        for (String unknown : java.util.Arrays.asList(null, "", "Plugin", "plugin ", "PLUGIN")) {
            assertEquals(Optional.empty(), ManifestKind.parse(unknown), "'" + unknown + "' is not a kind");
            assertEquals(Optional.empty(), ManifestRole.parse(unknown));
            assertEquals(Optional.empty(), PluginIsolation.parse(unknown));
        }
    }

    /** 只差种类的最小清单。 */
    private static Manifest.Builder minimal() {
        return Manifest.builder().id("xihan.a.b").version("1.0.0").primitives(1).stability(Stability.STABLE);
    }

    private static void assertFailure(Result<Manifest> result, String key, Map<String, Object> arguments, String message) {
        Error error = result.error().orElseThrow(() -> new AssertionError(key + " must be reported"));
        assertEquals(ManifestJson.INVALID_MANIFEST, error.code(), key);
        assertEquals(Optional.of(key), error.messageKey());
        assertEquals(arguments, error.details(), key);
        assertEquals(message, error.message(), key);
    }

    private static void assertRejected(String json, String name) {
        FrameworkException rejected = assertThrows(FrameworkException.class, () -> ManifestJson.read(json), name + " must be rejected");
        assertEquals(ManifestJson.INVALID_MANIFEST, rejected.code(), name);
    }
}
