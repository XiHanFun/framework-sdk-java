package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.primitives.MessageTemplate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 插件运行时的公开类型与内置目录：消息目录的 10 种区域、日志行的格式、端点与订阅的文法、元数据的截止时间。
 */
class PluginTypesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theBuiltInCatalogHasTenLocalesWithTheSameKeysAndPlaceholders() throws Exception {
        PluginCatalog catalog = PluginMessages.catalog();
        assertEquals(new TreeSet<>(PluginCatalog.LOCALES), new TreeSet<>(catalog.locales().keySet()));
        Map<String, String> english = PluginMessages.templates();
        for (Field field : PluginMessages.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class && !field.getName().equals("CATALOG_NAME")) {
                assertTrue(english.containsKey((String) field.get(null)), "en-US has a template for " + field.getName());
            }
        }
        for (Map.Entry<String, Map<String, String>> locale : catalog.locales().entrySet()) {
            assertEquals(english.keySet(), locale.getValue().keySet(), "the keys of " + locale.getKey() + " match en-US");
            for (Map.Entry<String, String> template : locale.getValue().entrySet()) {
                assertEquals(new TreeSet<>(MessageTemplate.placeholdersOf(english.get(template.getKey()))),
                        new TreeSet<>(MessageTemplate.placeholdersOf(template.getValue())), locale.getKey() + " " + template.getKey());
                assertTrue(template.getValue().chars().noneMatch(c -> c == 0x00A0 || c == 0x202F || c == 0x2028 || c == 0x2029 || c == 0xFEFF),
                        "no invisible characters in " + locale.getKey() + " " + template.getKey());
            }
        }
    }

    @Test
    void catalogsNeedEnglishAndValidFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PluginCatalog.of("samples", Map.of("zh-CN", Map.of("samples.a", "a"))));
        assertThrows(IllegalArgumentException.class, () -> PluginCatalog.of("samples", Map.of("en-US", Map.of("Bad Key", "a"))));
        assertThrows(IOException.class, () -> PluginCatalog.load("samples", locale -> stream("{\"samples.a\":\"a\",\"samples.a\":\"b\"}")));
        assertThrows(IOException.class, () -> PluginCatalog.load("samples", locale -> stream("{\"samples.a\":1}")));
        PluginCatalog loaded = PluginCatalog.load("samples", locale -> locale.equals("en-US") ? stream("{\"samples.a\":\"A {x}\"}") : null);
        assertEquals(Map.of("en-US", Map.of("samples.a", "A {x}")), loaded.locales());
        assertEquals(Optional.of("A 1"), loaded.messages().resolve("samples.a", "fr-FR", Map.of("x", 1)), "other locales fall back to en-US");
    }

    @Test
    void theLoggerWritesOneJsonObjectPerLine() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        PluginLogger logger = PluginLogger.of(target, PluginLogger.Level.INFORMATION).with("plugin", "xihan.tests.sample");
        logger.log(PluginLogger.Level.DEBUG, "hidden", Map.of());
        logger.information("listening", Map.of("endpoint", "tcp 127.0.0.1:1", "count", 2, "level", "ignored"));
        logger.error("failed", new IllegalStateException("boom"), Map.of());
        String[] lines = target.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length, "debug is below the minimum level and the stack trace stays on one line");
        JsonNode first = JSON.readTree(lines[0]);
        assertEquals("information", first.get("level").asText(), "the scope cannot replace the protocol members");
        assertEquals("listening", first.get("message").asText());
        assertEquals("xihan.tests.sample", first.get("plugin").asText());
        assertEquals(2, first.get("count").asInt());
        JsonNode second = JSON.readTree(lines[1]);
        assertEquals("error", second.get("level").asText());
        assertTrue(second.get("exception").asText().contains("IllegalStateException: boom"));
        List<String> names = List.of("trace", "debug", "information", "warning", "error", "critical");
        for (PluginLogger.Level level : PluginLogger.Level.values()) {
            assertEquals(names.get(level.ordinal()), level.wireName());
        }
    }

    @Test
    void endpointsSubscriptionsAndDeadlinesFollowTheGrammars() {
        assertEquals(Optional.of(new Endpoint(Network.UNIX, "/run/xihan/host 1.sock")), Endpoint.parse("unix /run/xihan/host 1.sock"));
        assertEquals(Optional.of(new Endpoint(Network.NPIPE, "\\\\.\\pipe\\xihan")), Endpoint.parse("npipe \\\\.\\pipe\\xihan"));
        assertEquals(Optional.empty(), Endpoint.parse("ipc /x"));
        assertEquals(Optional.empty(), Endpoint.parse("tcp"));
        assertEquals(Optional.empty(), Endpoint.parse("tcp "));
        assertEquals("tcp 127.0.0.1:5000", new Endpoint(Network.TCP, "127.0.0.1:5000").toString());

        assertTrue(Subscription.isTopic("orders.created-v2_1"));
        assertFalse(Subscription.isTopic("Orders"));
        assertFalse(Subscription.isTopic("orders\n"), "whole-string match");
        assertFalse(Subscription.isTopic("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> Subscription.of("orders").withMessageType(""));
        assertEquals(Optional.of(SubscriptionMode.COMPETING), SubscriptionMode.parse(""));
        assertEquals(Optional.of(SubscriptionMode.BROADCAST), SubscriptionMode.parse("broadcast"));
        assertEquals(Optional.empty(), SubscriptionMode.parse("Broadcast"));

        assertEquals(Optional.of(Instant.parse("2026-11-17T08:00:00.5Z")), Wire.parseDeadline("2026-11-17t16:00:00.5+08:00"));
        assertEquals(Optional.empty(), Wire.parseDeadline("2026-11-17 08:00:00"));
        assertEquals(Optional.empty(), Wire.parseDeadline(""));
    }

    @Test
    void protocolVersionsComeFromTheEnvironment() {
        assertEquals(List.of(1, 2), PluginRuntime.parseVersions("1, 2"));
        for (String invalid : new String[] {"", "1,", "x", "-1", "99999999999"}) {
            assertThrows(com.xihanfun.framework.primitives.FrameworkException.class, () -> PluginRuntime.parseVersions(invalid), invalid);
        }
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
