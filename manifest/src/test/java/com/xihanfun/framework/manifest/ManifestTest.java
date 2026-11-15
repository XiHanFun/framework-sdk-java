package com.xihanfun.framework.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Stability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 清单的读取、规范化输出与校验：逐项移植自 Go SDK 的 manifest_test.go，规范化输出与 Go SDK 写出的逐字节比较。
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

    /** Go SDK 的 manifest.Write 对 ECHO_MANIFEST 的输出。 */
    private static final String ECHO_WRITTEN_BY_GO = "{\n  \"$schema\": \"https://spec.xihanfun.com/manifest/v1.json\",\n  \"id\": \"xihan.samples.echo\",\n  \"package\": \"EchoPlugin\",\n  \"version\": \"5.0.0-preview.1\",\n  \"primitives\": \"1\",\n  \"stability\": \"experimental\",\n  \"kind\": \"plugin\",\n  \"isolation\": \"out-of-process\",\n  \"requires\": [\n    {\n      \"id\": \"kernel.plugins\",\n      \"version\": \"1\"\n    }\n  ],\n  \"contributes\": {\n    \"operations\": [\n      \"echoes.create\",\n      \"echoes.relay\",\n      \"echoes.crash\",\n      \"events.list\"\n    ],\n    \"config\": {\n      \"section\": \"XiHan:Echo\"\n    },\n    \"health\": [\n      \"echo\"\n    ]\n  },\n  \"activation\": [\n    \"onStartup\"\n  ],\n  \"permissions\": [\n    \"capability:kernel.pipeline\"\n  ],\n  \"entry\": \"EchoPlugin\"\n}";

    /** 覆盖全部字段与字符串转义的清单。 */
    private static final String FULL_MANIFEST = "{\"id\":\"xihan.caching.redis\",\"package\":\"A<b>&c \\\"q\\\" \\\\ \\u2028 tab\\t\",\"version\":\"1.2.3+build.5\",\"primitives\":\"1\",\"stability\":\"preview\",\"kind\":\"provider\",\"role\":\"default\",\"provides\":[{\"id\":\"caching.distributed\",\"version\":\"2\"},{\"id\":\"caching.local\",\"version\":\"1\"}],\"requires\":[{\"id\":\"kernel.hosting\",\"version\":\"^1\"},{\"id\":\"multitenancy.context\",\"version\":\"1\",\"optional\":true}],\"bindings\":[{\"name\":\"cache\",\"type\":\"cache:distributed\"},{\"name\":\"db\",\"type\":\"connection:relational\",\"required\":false}],\"contributes\":{\"pipeline\":[{\"middleware\":\"audit\",\"stage\":\"observe\",\"before\":[\"x\"],\"after\":[\"y\",\"z\"]}],\"config\":{\"section\":\"XiHan:Caching:Redis\",\"schema\":\"{}\"},\"permissions\":[\"caching.read\"],\"health\":[\"caching.redis.connection\"]},\"activation\":[\"onOperation:cache-entries.*\"],\"entry\":\"X\"}";

    /** Go SDK 的 manifest.Write 对 FULL_MANIFEST 的输出。 */
    private static final String FULL_WRITTEN_BY_GO = "{\n  \"id\": \"xihan.caching.redis\",\n  \"package\": \"A\\u003cb\\u003e\\u0026c \\\"q\\\" \\\\ \\u2028 tab\\t\",\n  \"version\": \"1.2.3+build.5\",\n  \"primitives\": \"1\",\n  \"stability\": \"preview\",\n  \"kind\": \"provider\",\n  \"role\": \"default\",\n  \"provides\": [\n    {\n      \"id\": \"caching.distributed\",\n      \"version\": \"2\"\n    },\n    {\n      \"id\": \"caching.local\",\n      \"version\": \"1\"\n    }\n  ],\n  \"requires\": [\n    {\n      \"id\": \"kernel.hosting\",\n      \"version\": \"1\"\n    },\n    {\n      \"id\": \"multitenancy.context\",\n      \"version\": \"1\",\n      \"optional\": true\n    }\n  ],\n  \"bindings\": [\n    {\n      \"name\": \"cache\",\n      \"type\": \"cache:distributed\"\n    },\n    {\n      \"name\": \"db\",\n      \"type\": \"connection:relational\",\n      \"required\": false\n    }\n  ],\n  \"contributes\": {\n    \"pipeline\": [\n      {\n        \"middleware\": \"audit\",\n        \"stage\": \"observe\",\n        \"before\": [\n          \"x\"\n        ],\n        \"after\": [\n          \"y\",\n          \"z\"\n        ]\n      }\n    ],\n    \"config\": {\n      \"section\": \"XiHan:Caching:Redis\",\n      \"schema\": \"{}\"\n    },\n    \"permissions\": [\n      \"caching.read\"\n    ],\n    \"health\": [\n      \"caching.redis.connection\"\n    ]\n  },\n  \"activation\": [\n    \"onOperation:cache-entries.*\"\n  ],\n  \"entry\": \"X\"\n}";

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
        assertEquals(ECHO_WRITTEN_BY_GO, written, "the canonical output is byte-identical to the Go SDK");
        Manifest again = ManifestJson.read(written);
        assertEquals(manifest, again, "round trip");
        assertTrue(written.contains("\"primitives\": \"1\""));
        assertTrue(written.contains("\"stability\": \"experimental\""));
    }

    @Test
    void canonicalOutputMatchesGoForEveryField() {
        Manifest manifest = ManifestJson.read(FULL_MANIFEST);
        assertEquals(FULL_WRITTEN_BY_GO, ManifestJson.write(manifest));
        assertEquals(manifest, ManifestJson.read(ManifestJson.write(manifest)));
        assertEquals(Optional.of(false), manifest.bindings().get(1).required());
        assertFalse(manifest.bindings().get(1).isRequired());
        assertTrue(manifest.bindings().get(0).isRequired());
        assertEquals(List.of("onOperation:cache-entries.*"), manifest.effectiveActivation());
        assertFalse(manifest.activatesOnStartup());
        String empty = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"capability\",\"contributes\":{}}";
        assertEquals("{\n  \"id\": \"xihan.a.b\",\n  \"version\": \"1.0.0\",\n  \"primitives\": \"1\",\n  \"stability\": \"stable\",\n  \"kind\": \"capability\",\n  \"contributes\": {}\n}",
                ManifestJson.write(ManifestJson.read(empty)), "present but empty contributions are written as {}");
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
    void onlyWasmPluginsMayContributeMiddleware() {
        String template = "{\"id\":\"xihan.a.b\",\"version\":\"1.0.0\",\"primitives\":\"1\",\"stability\":\"stable\",\"kind\":\"%s\"%s,\"contributes\":{\"pipeline\":%s}}";
        String audit = "[{\"middleware\":\"audit\",\"stage\":\"observe\"},{\"middleware\":\"tenant-guard\",\"stage\":\"tenant\"}]";
        for (PluginIsolation isolation : List.of(PluginIsolation.OUT_OF_PROCESS, PluginIsolation.IN_PROCESS)) {
            String json = template.formatted("plugin", ",\"isolation\":\"" + isolation.wireName() + "\"", audit);
            FrameworkException rejected = assertThrows(FrameworkException.class, () -> ManifestJson.read(json));
            assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
            for (String fragment : List.of("'xihan.a.b'", "'contributes.pipeline'", "audit, tenant-guard", isolation.wireName())) {
                assertTrue(rejected.getMessage().contains(fragment), "the message must mention " + fragment + ": " + rejected.getMessage());
            }
        }
        Map<String, String> accepted = Map.of(
                "wasm plugin", template.formatted("plugin", ",\"isolation\":\"wasm\"", audit),
                "capability", template.formatted("capability", "", audit),
                "provider", template.formatted("provider", "", audit),
                "out-of-process, empty list", template.formatted("plugin", ",\"isolation\":\"out-of-process\"", "[]"),
                "in-process, empty list", template.formatted("plugin", ",\"isolation\":\"in-process\"", "[]"));
        accepted.forEach((name, json) -> assertDoesNotThrow(() -> ManifestJson.read(json), name));

        // 构建同样核对：不产生宿主会拒绝的清单
        Manifest.Builder builder = Manifest.builder().id("xihan.a.b").version("1.0.0").primitives(1).stability(Stability.STABLE)
                .kind(ManifestKind.PLUGIN).isolation(PluginIsolation.OUT_OF_PROCESS)
                .contributes(Contributions.builder().pipeline(List.of(PipelineContribution.of("audit", PipelineStage.OBSERVE))).build());
        FrameworkException rejected = assertThrows(FrameworkException.class, builder::build);
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code());
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
        assertThrows(IOException.class, () -> ManifestJson.readFile(directory.resolve("missing.json")));
    }

    private static void assertRejected(String json, String name) {
        FrameworkException rejected = assertThrows(FrameworkException.class, () -> ManifestJson.read(json), name + " must be rejected");
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code(), name);
    }
}
