package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.internal.Whitespace;
import com.xihanfun.framework.primitives.MessageTemplate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 插件运行时的公开类型与规则：消息目录（回放 {@code plugin-messages.json}）、日志行的格式、握手行与回调端点的文法（回放 {@code handshake-lines.json}、
 * {@code host-addresses.json}）、截止时间的读写（回放 {@code deadlines.json}）与订阅的文法。
 */
class PluginTypesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 规范的消息键对应的 SDK 常量。 */
    private static final Map<String, String> CODES = Map.of(
            PluginMessages.UNAVAILABLE, PluginErrors.UNAVAILABLE,
            PluginMessages.FAILED, PluginErrors.UNEXPECTED,
            PluginMessages.EVENT_UNSUBSCRIBED, "KERNEL_NOTFOUND_001",
            PluginMessages.HOST_UNAVAILABLE, PluginErrors.UNAVAILABLE,
            PluginMessages.CALLBACK_DENIED, PluginErrors.CALLBACK_DENIED,
            PluginMessages.CALLBACK_TENANT_DENIED, PluginErrors.CALLBACK_DENIED,
            PluginMessages.SESSION_INVALID, PluginErrors.CALLBACK_DENIED);

    @Test
    void theBuiltInCatalogIsExactlyTheSpecifiedMessages() throws Exception {
        // plugin-messages.json：键、参数（即 details 的成员）、错误码与 en-US 模板（invocation.md §8）；SDK 不自造键
        JsonNode vectors = Vectors.read("plugin/plugin-messages.json");
        Map<String, String> english = PluginMessages.templates();
        Set<String> keys = new TreeSet<>();
        for (JsonNode message : vectors.get("messages")) {
            String key = message.get("key").asText();
            keys.add(key);
            assertEquals(message.get("template").asText(), english.get(key), key);
            assertEquals(message.get("code").asText(), CODES.get(key), key);
            assertEquals(new TreeSet<>(Vectors.strings(message.get("arguments"))), new TreeSet<>(MessageTemplate.placeholdersOf(english.get(key))), key);
        }
        // 另加 Host.publish 在本地报出的 messaging.envelope.invalid（RFC-0009「信封」，invocation.md §5），模板与宿主的消息目录逐字相同
        keys.add(PluginMessages.ENVELOPE_INVALID);
        assertEquals("The envelope is invalid: {reason}", english.get(PluginMessages.ENVELOPE_INVALID));
        assertEquals(keys, new TreeSet<>(english.keySet()), "the catalog holds exactly the specified keys and the local envelope key");
        Set<String> constants = new TreeSet<>();
        for (Field field : PluginMessages.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class && !field.getName().equals("CATALOG_NAME")) {
                constants.add((String) field.get(null));
            }
        }
        assertEquals(keys, constants, "every constant is a key of the catalog");
        // 固定的 reason 文本与运行时写出的相同
        Set<String> reasons = new TreeSet<>();
        vectors.get("reasons").forEach(reason -> reasons.add(reason.get("reason").asText()));
        assertTrue(reasons.containsAll(List.of("the configuration is not a JSON object", "the host reported a failure without an error",
                Host.NO_ENDPOINT, "invalid error code '{code}'", "the host returned {results} results for {envelopes} envelopes")), reasons.toString());
    }

    @Test
    void theBuiltInCatalogHasTenLocalesWithTheSameKeysAndPlaceholders() {
        PluginCatalog catalog = PluginMessages.catalog();
        assertEquals(new TreeSet<>(PluginCatalog.LOCALES), new TreeSet<>(catalog.locales().keySet()));
        Map<String, String> english = PluginMessages.templates();
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
    void theLocalEnvelopeErrorRendersFromTheBuiltInCatalog() {
        // Host.publish 在本地拒绝编不成 UTF-8 的 data 时的错误（ADR-0315），插件不经宿主也能按区域渲染
        Map<String, Object> details = Map.of("reason", Host.UNENCODABLE_DATA);
        assertEquals(Optional.of("The envelope is invalid: the data has a lone surrogate and cannot be encoded as UTF-8"),
                PluginMessages.catalog().messages().resolve(PluginMessages.ENVELOPE_INVALID, "en-US", details));
        assertEquals(Optional.of("信封不合法：x"), PluginMessages.catalog().messages().resolve(PluginMessages.ENVELOPE_INVALID, "zh-CN",
                Map.of("reason", "x")));
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
    void handshakeLinesFollowTheGrammar() throws Exception {
        // handshake-lines.json：地址是一个或多个空白之外的字符，tcp 只认回环地址加端口（handshake.md §2）
        JsonNode vectors = Vectors.read("plugin/handshake-lines.json");
        for (JsonNode valid : vectors.get("valid")) {
            Optional<Endpoint> parsed = Endpoint.parseHandshakeLine(valid.get("line").asText());
            assertEquals(Optional.of(new Endpoint(Network.parse(valid.get("network").asText()).orElseThrow(), valid.get("address").asText())), parsed,
                    valid.toString());
        }
        for (String invalid : Vectors.strings(vectors.get("invalid"))) {
            assertEquals(Optional.empty(), Endpoint.parseHandshakeLine(invalid), invalid);
        }
        // 空白集合（RFC-0001「空白字符」，handshake.md §2）与 Character.isWhitespace 不同
        assertTrue(Whitespace.isWhitespace(' ') && Whitespace.isWhitespace(' ') && Whitespace.isWhitespace(' ') && Whitespace.isWhitespace('\u0085'));
        assertFalse(Whitespace.isWhitespace('\u001c') || Whitespace.isWhitespace('​') || Whitespace.isWhitespace('﻿'));
        assertEquals("tcp 127.0.0.1:5000", new Endpoint(Network.TCP, "127.0.0.1:5000").toString());
    }

    @Test
    void hostAddressesAreParsedAndTheFirstSupportedEntryIsSelected() throws Exception {
        // host-addresses.json：以 ; 分隔，任何一项不合法整个值就不合法；按顺序取第一个能连的（handshake.md §4）
        JsonNode vectors = Vectors.read("plugin/host-addresses.json");
        for (JsonNode valid : vectors.get("valid")) {
            List<Endpoint> expected = new ArrayList<>();
            valid.get("entries").forEach(entry -> expected.add(endpoint(entry)));
            assertEquals(Optional.of(expected), Endpoint.parseList(valid.get("value").asText()), valid.toString());
        }
        for (String invalid : Vectors.strings(vectors.get("invalid"))) {
            assertEquals(Optional.empty(), Endpoint.parseList(invalid), invalid);
        }
        assertEquals(Optional.empty(), Endpoint.parseList(null), "a missing variable is not a list");
        for (JsonNode selection : vectors.get("selection")) {
            Set<Network> supported = EnumSet.noneOf(Network.class);
            selection.get("supported").forEach(name -> supported.add(Network.parse(name.asText()).orElseThrow()));
            String value = selection.get("value").isNull() ? null : selection.get("value").asText();
            Optional<Endpoint> expected = selection.get("selected").isNull() ? Optional.empty() : Optional.of(endpoint(selection.get("selected")));
            assertEquals(expected, Endpoint.select(value, supported), selection.toString());
        }
        assertTrue(Transport.dialable().contains(Network.TCP), "loopback TCP can always be dialed");
        assertFalse(Transport.dialable().contains(Network.NPIPE), "grpc-java cannot dial named pipes");
    }

    private static Endpoint endpoint(JsonNode node) {
        return new Endpoint(Network.parse(node.get("network").asText()).orElseThrow(), node.get("address").asText());
    }

    @Test
    void deadlinesAreReadStrictlyAndWrittenWithSevenDigits() throws Exception {
        // deadlines.json：Instant 精确到纳秒，比较时不截断（invocation.md §2）
        JsonNode vectors = Vectors.read("plugin/deadlines.json");
        for (JsonNode valid : vectors.get("valid")) {
            assertEquals(Optional.of(Instant.parse(valid.get("utc").asText())), Wire.parseTimestamp(valid.get("text").asText()), valid.toString());
        }
        for (String invalid : Vectors.strings(vectors.get("invalid"))) {
            assertEquals(Optional.empty(), Wire.parseTimestamp(invalid), invalid);
        }
        assertEquals(Optional.empty(), Wire.parseTimestamp(""));
        for (JsonNode effective : vectors.get("effective")) {
            Optional<Instant> grpc = effective.get("grpc").isNull() ? Optional.empty() : Optional.of(Instant.parse(effective.get("grpc").asText()));
            Optional<Instant> expected = effective.get("expected").isNull() ? Optional.empty() : Optional.of(Instant.parse(effective.get("expected").asText()));
            assertEquals(expected, Wire.effectiveDeadline(effective.get("metadata").asText(), grpc), effective.toString());
        }
        for (JsonNode written : vectors.get("written")) {
            assertEquals(written.get("text").asText(), Wire.formatDeadline(Instant.parse(written.get("utc").asText())), written.toString());
        }
        assertEquals("2026-11-18T03:25:00.1234567+00:00", Wire.formatDeadline(Instant.parse("2026-11-18T03:25:00.123456789Z")),
                "finer digits are truncated, not rounded");
        assertEquals("9999-12-31T23:59:59.9999999+00:00", Wire.formatDeadline(Instant.MAX), "far deadlines stay readable");
        assertTrue(Wire.parseTimestamp(Wire.formatDeadline(Instant.MAX)).isPresent());
    }

    @Test
    void subscriptionsFollowTheTopicGrammar() {
        assertTrue(Subscription.isTopic("orders.created-v2_1"));
        assertFalse(Subscription.isTopic("Orders"));
        assertFalse(Subscription.isTopic("orders\n"), "whole-string match");
        assertFalse(Subscription.isTopic("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> Subscription.of("orders").withMessageType(""));
        assertEquals(Optional.of(SubscriptionMode.COMPETING), SubscriptionMode.parse(""));
        assertEquals(Optional.of(SubscriptionMode.BROADCAST), SubscriptionMode.parse("broadcast"));
        assertEquals(Optional.empty(), SubscriptionMode.parse("Broadcast"));
    }

    @Test
    void enumsParseExactlyTheirWireNames() {
        // 各枚举的 parse 共用一个查找：整串相等、区分大小写，未知的名字为空；订阅方式的空值与 null 同竞争消费（invocation.md §4）
        for (Network network : Network.values()) {
            assertEquals(Optional.of(network), Network.parse(network.wireName()));
        }
        for (SubscriptionMode mode : SubscriptionMode.values()) {
            assertEquals(Optional.of(mode), SubscriptionMode.parse(mode.wireName()));
        }
        for (String unknown : java.util.Arrays.asList(null, "", "TCP", "tcp ", "pipe")) {
            assertEquals(Optional.empty(), Network.parse(unknown), "'" + unknown + "' is not a network");
        }
        assertEquals(Optional.of(SubscriptionMode.COMPETING), SubscriptionMode.parse(null));
        assertEquals(Optional.empty(), SubscriptionMode.parse("competing "));
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
