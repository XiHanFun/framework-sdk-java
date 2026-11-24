package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 绑定连同属性交给作者（{@code lifecycle.md} §2、§7.2，ADR-0285）：回放 {@code binding-properties.json} 的 {@code reading}，
 * 并核对绑定类型的文本与运行时自己的输出不带主值与属性值。
 *
 * <p>同一文件的 {@code json}（Wasm 载体的 proto3 JSON 文本）、{@code names}、{@code limits} 与 {@code delivery} 是宿主一侧的，SDK 不回放。
 */
class BindingPropertiesVectorsTest {
    /** 测试用的主值与属性值：够特别，在输出里搜得出来。 */
    private static final String VALUE = "postgres://main-value-0f3a/app";

    private static final String PASSWORD = "property-value-7c1e";

    private static final String TOKEN = "property-value-9b2d";

    @TempDir
    Path directory;

    /** 记下配置回调收到的配置，再给出预定的结局。 */
    private static PluginHooks recording(AtomicReference<PluginConfiguration> configured, Supplier<Result<Unit>> outcome) {
        return new PluginHooks() {
            @Override
            public Result<Unit> configure(Context context, PluginConfiguration configuration) {
                configured.set(configuration);
                return outcome.get();
            }
        };
    }

    @Test
    void theAuthorReceivesTheBindingsTheHostWrote() throws Exception {
        // reading：hex 是宿主写出的 ConfigureRequest 字节，用生成的协议代码解码后经 Configure 交给配置回调，
        // 作者拿到的绑定逐项等于期望（名字、类型、主值、属性，含先后；没有属性时是空表）
        JsonNode reading = Vectors.read("plugin/binding-properties.json").get("reading");
        assertFalse(reading.isEmpty(), "the vectors must have reading cases");
        int index = 0;
        for (JsonNode vector : reading) {
            String name = vector.get("name").asText();
            byte[] written = HexFormat.of().parseHex(vector.get("hex").asText());
            PluginProto.ConfigureRequest request = PluginProto.ConfigureRequest.parseFrom(written);
            AtomicReference<PluginConfiguration> configured = new AtomicReference<>();
            Path home = Files.createDirectory(directory.resolve("reading-" + index++));
            try (RunningPlugin plugin = RunningPlugin.start(home, PluginOptions.builder().hooks(recording(configured, Result::success)))) {
                plugin.handshake();
                assertTrue(plugin.stub().configure(request).getStatus().getOk(), name);
            }
            List<ResolvedBinding> bindings = configured.get().bindings();
            JsonNode expected = vector.get("bindings");
            assertEquals(expected.size(), bindings.size(), name + ": the number of bindings");
            for (int position = 0; position < expected.size(); position++) {
                JsonNode want = expected.get(position);
                ResolvedBinding binding = bindings.get(position);
                String which = name + ": binding " + position;
                assertEquals(want.get("name").asText(), binding.name(), which);
                assertEquals(want.get("type").asText(), binding.type(), which);
                assertEquals(want.get("value").asText(), binding.value(), which);
                assertEquals(properties(want.get("properties")), binding.properties(), which);
            }
        }
    }

    /** 期望的属性表：reading 里的属性值都是字符串。 */
    private static Map<String, String> properties(JsonNode object) {
        Map<String, String> properties = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            assertTrue(entry.getValue().isTextual(), "a property value of a reading case must be a string: " + entry.getKey());
            properties.put(entry.getKey(), entry.getValue().textValue());
        }
        return properties;
    }

    @Test
    void propertiesAreCopiedReadOnlyAndKeptAsTheyCame() {
        // 属性名不折叠大小写、不改写，像数字的、带点的名字照旧；值为空的属性在表里
        Map<String, String> source = new HashMap<>(Map.of("accessKey", "a", "AccessKey", "b", "0", "zero", "8080", "port", "tls.crt", ""));
        ResolvedBinding binding = new ResolvedBinding("db", "connection:relational", "", source);
        assertEquals(Map.of("accessKey", "a", "AccessKey", "b", "0", "zero", "8080", "port", "tls.crt", ""), binding.properties());
        assertEquals("", binding.value(), "an empty value is not an error");

        // 拷贝且只读：之后改传入的表不影响绑定，作者也改不动绑定的表
        source.put("late", "x");
        source.remove("0");
        assertEquals(5, binding.properties().size());
        assertEquals("zero", binding.properties().get("0"));
        assertThrows(UnsupportedOperationException.class, () -> binding.properties().put("added", "x"));
        assertThrows(UnsupportedOperationException.class, () -> binding.properties().clear());

        // 原来的三个参数的构造照旧可用：没有属性，是空表
        ResolvedBinding plain = new ResolvedBinding("cache", "cache:distributed", "redis.internal:6379");
        assertEquals(Map.of(), plain.properties());
        assertEquals(new ResolvedBinding("cache", "cache:distributed", "redis.internal:6379", Map.of()), plain);

        assertThrows(NullPointerException.class, () -> new ResolvedBinding("db", "queue", "", null));
        Map<String, String> withNull = new HashMap<>();
        withNull.put("password", null);
        assertThrows(NullPointerException.class, () -> new ResolvedBinding("db", "queue", "", withNull));
    }

    @Test
    void theTextCarriesNamesButNoValues() {
        ResolvedBinding binding = new ResolvedBinding("db", "connection:relational", VALUE,
                Map.of("password", PASSWORD, "Password", TOKEN, "empty", ""));
        // 只带绑定名、类型与按升序排列的属性名（lifecycle.md §7.2）
        assertEquals("ResolvedBinding[name=db, type=connection:relational, properties=[Password, empty, password]]", binding.toString());
        assertEquals("ResolvedBinding[name=cache, type=cache:distributed, properties=[]]",
                new ResolvedBinding("cache", "cache:distributed", VALUE).toString());

        PluginConfiguration configuration = new PluginConfiguration("{}", List.of(), List.of(binding), Optional.empty());
        for (String text : List.of(binding.toString(), String.valueOf(binding), "" + binding, String.format("%s", binding),
                List.of(binding).toString(), Map.of("db", binding).toString(), configuration.toString())) {
            assertNoSecrets(text, "the text of a binding");
            assertTrue(text.contains("name=db") && text.contains("[Password, empty, password]"), text);
        }
    }

    @Test
    void theRuntimeKeepsBindingValuesOutOfItsLogAndTheStatus() throws Exception {
        // 配置回调成功、返回失败、抛出三种情况：运行时写的日志与回给宿主的 Status 都不带主值与属性值，作者照常拿到它们
        Map<String, Supplier<Result<Unit>>> outcomes = Map.of(
                "succeeds", Result::success,
                "fails", () -> Result.fail(Error.validation("SAMPLES_VALIDATION_001", "samples.connection.failed")),
                "throws", () -> {
                    throw new IllegalStateException("The connection could not be opened.");
                });
        List<String> names = new ArrayList<>(outcomes.keySet());
        for (String name : names) {
            AtomicReference<PluginConfiguration> configured = new AtomicReference<>();
            Path home = Files.createDirectory(directory.resolve("outcome-" + name));
            PluginOptions.Builder options = PluginOptions.builder().hooks(recording(configured, outcomes.get(name)));
            try (RunningPlugin plugin = RunningPlugin.start(home, options)) {
                plugin.handshake();
                PluginProto.ConfigureResponse response = plugin.stub().configure(PluginProto.ConfigureRequest.newBuilder()
                        .setConfiguration("{}")
                        .addAllGrantedPermissions(RunningPlugin.PERMISSIONS)
                        .addBindings(PluginProto.Binding.newBuilder().setName("db").setType("connection:relational").setValue(VALUE)
                                .putProperties("password", PASSWORD).putProperties("Password", TOKEN))
                        .build());
                assertEquals(name.equals("succeeds"), response.getStatus().getOk(), name);
                ResolvedBinding binding = configured.get().findBinding("db").orElseThrow();
                assertEquals(VALUE, binding.value(), name);
                assertEquals(Map.of("password", PASSWORD, "Password", TOKEN), binding.properties(), name);
                assertNoSecrets(plugin.logs(), "the runtime log when the configure hook " + name);
                assertNoSecrets(response.toString(), "the status when the configure hook " + name);
            }
        }
    }

    private static void assertNoSecrets(String text, String what) {
        for (String secret : List.of(VALUE, PASSWORD, TOKEN)) {
            assertFalse(text.contains(secret), what + " must not carry the value or a property value of a binding: " + text);
        }
    }
}
