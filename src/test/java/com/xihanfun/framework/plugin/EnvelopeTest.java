package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.plugin.v1.PluginProto;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 信封的 {@code time} 与 {@code dataschema}（{@code invocation.md} §4，RFC-0009「信封」第 3 条，ADR-0305）：写出的文本、读入、往返与没有时间；
 * 回放 {@code plugin} 档的 {@code envelope-times.json}（宿主读插件写来的 {@code time} 与 SDK 读宿主写来的是同一条文法）与
 * {@code sdk-envelope-times.json}（SDK 一侧按时间类型精度的写出与读入，ADR-0310）。
 */
class EnvelopeTest {
    /** {@link Instant} 的精度在 {@code sdk-envelope-times.json} 里的名字：纳秒，写出时最多九位小数。 */
    private static final String PRECISION = "nanoseconds";

    /** 作者给的时刻与 SDK 写出的 {@code time}。 */
    record Written(Instant time, String text) {
    }

    /** 六个 SDK 共用的写出用例：换算成 UTC、以 {@code Z} 结尾、小数去掉末尾的 0；最后几条是纳秒精度才有的。 */
    private static final List<Written> WRITTEN = List.of(
            written("2026-11-25T03:25:00.123Z", "2026-11-25T03:25:00.123Z"),
            written("2026-11-25T03:25:00.500Z", "2026-11-25T03:25:00.5Z"),
            written("2026-11-25T03:25:00.120Z", "2026-11-25T03:25:00.12Z"),
            written("2026-11-25T03:25:00Z", "2026-11-25T03:25:00Z"),
            written("2026-11-25T11:25:00.25+08:00", "2026-11-25T03:25:00.25Z"),
            written("2026-11-24T22:25:00-05:00", "2026-11-25T03:25:00Z"),
            written("2026-01-01T00:30:00+01:00", "2025-12-31T23:30:00Z"),
            written("0001-01-01T00:00:00Z", "0001-01-01T00:00:00Z"),
            written("2026-11-25T03:25:00.000001Z", "2026-11-25T03:25:00.000001Z"),
            written("2026-11-25T03:25:00.123456Z", "2026-11-25T03:25:00.123456Z"),
            written("2026-11-25T03:25:00.000000001Z", "2026-11-25T03:25:00.000000001Z"),
            written("2026-11-25T03:25:00.123456789Z", "2026-11-25T03:25:00.123456789Z"),
            written("9999-12-31T23:59:59.999999999Z", "9999-12-31T23:59:59.999999999Z"));

    /** 六个 SDK 共用的读入用例：宿主写来的 {@code time} 与作者收到的时刻；{@link Instant} 精确到纳秒，九位小数不截断。 */
    private static final Map<String, Optional<Instant>> READ = Map.of(
            "2026-11-25T11:25:00+08:00", Optional.of(Instant.parse("2026-11-25T03:25:00Z")),
            "2026-11-24T22:25:00.25-05:00", Optional.of(Instant.parse("2026-11-25T03:25:00.25Z")),
            "2026-11-25t03:25:00z", Optional.of(Instant.parse("2026-11-25T03:25:00Z")),
            "2026-11-25T03:25:00.1234567+00:00", Optional.of(Instant.parse("2026-11-25T03:25:00.1234567Z")),
            "2026-11-25T03:25:00.123456789Z", Optional.of(Instant.parse("2026-11-25T03:25:00.123456789Z")),
            "9999-12-31T23:59:59.999999999Z", Optional.of(Instant.parse("9999-12-31T23:59:59.999999999Z")),
            "", Optional.empty());

    private static Written written(String instant, String text) {
        return new Written(OffsetDateTime.parse(instant).toInstant(), text);
    }

    private static Envelope.Builder envelope() {
        return Envelope.builder("orders", "e1", "urn:test", "orders.created");
    }

    /** 宿主写来的信封：只设 {@code time} 与 {@code dataschema}。 */
    private static Optional<Envelope> delivered(String time, String dataSchema) {
        return Wire.envelope(PluginProto.Envelope.newBuilder().setTopic("orders").setId("e1").setSource("urn:test")
                .setType("orders.created").setTime(time).setDataSchema(dataSchema).build());
    }

    private static Optional<Instant> deliveredTime(String text) {
        return delivered(text, "").orElseThrow(() -> new AssertionError("'" + text + "' should be read")).time();
    }

    @Test
    void timesAreWrittenInUtcWithTheTrailingZerosOfTheFractionDropped() {
        for (Written written : WRITTEN) {
            assertEquals(written.text(), Wire.formatTime(written.time()), written.toString());
            PluginProto.Envelope encoded = Wire.envelope(envelope().time(written.time()).build());
            assertEquals(written.text(), encoded.getTime(), written.toString());
            // 往返：写出的文本读回同一个时刻
            assertEquals(Optional.of(written.time()), deliveredTime(encoded.getTime()), written.toString());
        }
        assertEquals("", Wire.envelope(envelope().build()).getTime(), "no time is written as an empty text");
        assertEquals(Optional.empty(), envelope().build().time());
    }

    @Test
    void timesFromTheHostAreReadInUtcToTheNanosecond() {
        READ.forEach((text, time) -> assertEquals(time, deliveredTime(text), text));
        // 闰秒、没有偏移、空格代替 T、十位小数、只有空白
        for (String invalid : List.of("2026-11-25T03:25:60Z", "2026-11-25T03:25:00", "2026-11-25 03:25:00Z",
                "2026-11-25T03:25:00.1234567890Z", " ")) {
            assertEquals(Optional.empty(), delivered(invalid, ""), invalid);
        }
    }

    @Test
    void replaysTheEnvelopeTimesVector() throws Exception {
        JsonNode vectors = Vectors.read("plugin/envelope-times.json");
        for (JsonNode valid : vectors.get("valid")) {
            JsonNode utc = valid.get("utc");
            Optional<Instant> expected = utc.isNull() ? Optional.empty() : Optional.of(Instant.parse(utc.asText()));
            assertEquals(expected, deliveredTime(valid.get("text").asText()), valid.toString());
        }
        for (String invalid : Vectors.strings(vectors.get("invalid"))) {
            assertEquals(Optional.empty(), delivered(invalid, ""), invalid);
        }
        // written 是宿主写给插件的：SDK 读出 instant 那个时刻
        for (JsonNode written : vectors.get("written")) {
            JsonNode instant = written.get("instant");
            Optional<Instant> expected = instant.isNull() ? Optional.empty()
                    : Optional.of(OffsetDateTime.parse(instant.asText()).toInstant());
            assertEquals(expected, deliveredTime(written.get("text").asText()), written.toString());
        }
    }

    @Test
    void replaysTheSdkEnvelopeTimesVectorAtNanosecondPrecision() throws Exception {
        JsonNode vectors = Vectors.read("plugin/sdk-envelope-times.json");
        assertEquals(9, vectors.get("precisions").get(PRECISION).asInt(), "Instant writes up to nine fraction digits");
        // writing：按投递时读宿主文本的读法构造时刻（Instant 不带偏移，原文的偏移在这一步换算成 UTC），经发布时的写出路径写成文本
        for (JsonNode writing : vectors.get("writing")) {
            JsonNode instant = writing.get("instant");
            Envelope.Builder builder = envelope();
            if (!instant.isNull()) {
                builder.time(Wire.parseTimestamp(instant.asText())
                        .orElseThrow(() -> new AssertionError("'" + instant.asText() + "' should be read")));
            }
            String written = Wire.envelope(builder.build()).getTime();
            assertEquals(writing.get("text").get(PRECISION).asText(), written, writing.get("name").asText());
        }
        // reading：经投递的读入路径读宿主写来的文本，与九位小数的 UTC 时刻比较
        for (JsonNode reading : vectors.get("reading")) {
            Instant expected = Instant.parse(reading.get("utc").get(PRECISION).asText());
            assertEquals(Optional.of(expected), deliveredTime(reading.get("text").asText()), reading.get("name").asText());
        }
        // unreadable：一条也读不出，投递以 InvalidArgument 拒绝（PluginCallsTest 经 gRPC 核对同一份文本）
        for (JsonNode unreadable : vectors.get("unreadable")) {
            assertEquals(Optional.empty(), delivered(unreadable.get("text").asText(), ""), unreadable.get("name").asText());
        }
    }

    @Test
    void timesOutsideTheFourDigitYearsAreWrittenAsIsForTheHostToReject() {
        // 不在本地核对（ADR-0306）：换算成 UTC 之后落到 0001–9999 年之外的照写，宿主以 messaging.envelope.invalid_time 拒绝那一项
        List<Written> outside = List.of(
                written("0001-01-01T00:30:00+01:00", "0000-12-31T23:30:00Z"),
                written("9999-12-31T23:30:00-01:00", "10000-01-01T00:30:00Z"),
                new Written(Instant.parse("0000-12-31T23:59:59.999999999Z"), "0000-12-31T23:59:59.999999999Z"),
                new Written(Instant.parse("-0001-06-01T00:00:00Z"), "-0001-06-01T00:00:00Z"),
                new Written(Instant.MIN, "-1000000000-01-01T00:00:00Z"),
                new Written(Instant.MAX, "1000000000-12-31T23:59:59.999999999Z"));
        for (Written written : outside) {
            assertEquals(written.text(), Wire.envelope(envelope().time(written.time()).build()).getTime(), written.toString());
            assertEquals(Optional.empty(), Wire.parseTimestamp(written.text()), "the host cannot read " + written.text());
        }
        assertThrows(NullPointerException.class, () -> envelope().time(null));
    }

    @Test
    void theDataSchemaIsCarriedAsIs() {
        String schema = "https://example.com/schemas/order.json";
        assertEquals(schema, Wire.envelope(envelope().dataSchema(schema).build()).getDataSchema());
        assertEquals("", Wire.envelope(envelope().build()).getDataSchema(), "no dataschema is written as an empty text");
        // 不裁剪、不核对：只有空白的值由宿主拒绝那一项
        assertEquals(" urn:x ", Wire.envelope(envelope().dataSchema(" urn:x ").build()).getDataSchema());
        assertEquals(Optional.of(schema), delivered("", schema).orElseThrow().dataSchema());
        assertEquals(Optional.empty(), delivered("", "").orElseThrow().dataSchema(), "an empty text is no dataschema");
        assertEquals(Optional.of(" "), delivered("", " ").orElseThrow().dataSchema());
        // 相等比较、散列码与调试输出都算上它
        assertEquals(envelope().dataSchema("urn:a").build(), envelope().dataSchema("urn:a").build());
        assertEquals(envelope().dataSchema("urn:a").build().hashCode(), envelope().dataSchema("urn:a").build().hashCode());
        assertNotEquals(envelope().dataSchema("urn:a").build(), envelope().dataSchema("urn:b").build());
        assertNotEquals(envelope().dataSchema("urn:a").build(), envelope().build());
        assertTrue(envelope().dataSchema("urn:a").build().toString().contains("dataSchema=urn:a"));
        assertEquals("Envelope[topic=orders, id=e1, type=orders.created]", envelope().build().toString());
        Instant time = Instant.parse("2026-11-25T03:25:00Z");
        assertEquals(envelope().time(time).build(), envelope().time(time).build());
        assertNotEquals(envelope().time(time).build(), envelope().time(time.plusNanos(1)).build());
    }

    @Test
    void textDataIsEncodedStrictlyAndALoneSurrogateIsKeptForThePublishToRefuse() {
        // RFC-0001「写出与转交」（ADR-0315）：data(String) 严格编码成 UTF-8；孤立的代理项编不成，不换成 ?，信封照样建得出、留给 Host.publish 拒绝
        String pair = "{\"title\":\"" + new String(Character.toChars(0x1F600)) + "\"}";
        Envelope paired = envelope().data(pair).build();
        assertFalse(paired.hasUnencodableData());
        assertArrayEquals(pair.getBytes(StandardCharsets.UTF_8), paired.data(), "a surrogate pair is one character");
        assertEquals(pair, paired.dataText());
        for (String text : List.of(String.valueOf((char) 0xD800), String.valueOf((char) 0xDC00), "" + (char) 0xDC00 + (char) 0xD800,
                "x" + (char) 0xDBFF)) {
            Envelope unencodable = envelope().data(text).build();
            assertTrue(unencodable.hasUnencodableData(), text);
            assertNotEquals(envelope().data(text.getBytes(StandardCharsets.UTF_8)).build(), unencodable, "not the bytes with '?' in place");
        }
        // 广义 UTF-8：孤立的代理项照位模式写成三个字节，不是合法的 UTF-8
        assertArrayEquals(new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80}, envelope().data("a" + (char) 0xD800).build().data());
        // 以字节设置的 data 原样、总是可发：同样的字节也与以文本设置的不相等
        Envelope bytes = envelope().data(new byte[] {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80}).build();
        assertFalse(bytes.hasUnencodableData());
        assertNotEquals(bytes, envelope().data("a" + (char) 0xD800).build());
        assertFalse(envelope().data(String.valueOf((char) 0xD800)).data(new byte[] {'{', '}'}).build().hasUnencodableData(),
                "setting the data again replaces the text");
        assertTrue(envelope().data(new byte[] {'{', '}'}).data(String.valueOf((char) 0xD800)).build().hasUnencodableData());
        assertEquals(envelope().data("x" + (char) 0xD800).build(), envelope().data("x" + (char) 0xD800).build());
        assertEquals(envelope().data("x" + (char) 0xD800).build().hashCode(), envelope().data("x" + (char) 0xD800).build().hashCode());
    }
}
