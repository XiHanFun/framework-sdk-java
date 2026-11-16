package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/primitives/json-text.json}（RFC-0001「JSON 文本」，ADR-0236）：
 * 合法的通过；语法错误只写 {@code $}，优先于重复成员；重复成员写第一处重复的路径。
 */
class JsonTextVectorsTest {
    @Test
    void casesFollowTheVectors() throws IOException {
        for (JsonNode vector : Vectors.read("primitives/json-text.json").get("cases")) {
            String name = vector.get("name").textValue();
            byte[] bytes = vector.has("hex") ? HexFormat.of().parseHex(vector.get("hex").textValue())
                    : vector.get("text").textValue().getBytes(StandardCharsets.UTF_8);
            Optional<JsonText.Problem> expected = switch (vector.get("expect").textValue()) {
                case "valid" -> Optional.empty();
                case "syntax" -> Optional.of(new JsonText.Problem(true, "$"));
                case "duplicate" -> Optional.of(new JsonText.Problem(false, vector.get("path").textValue()));
                default -> throw new IllegalStateException("Unknown expectation in " + name);
            };
            assertEquals(expected, JsonText.check(bytes), name);
            if (vector.has("text")) {
                assertEquals(expected, JsonText.check(vector.get("text").textValue()), name + " (string)");
            }
        }
    }

    @Test
    void stringsWithLoneSurrogatesAreNotJson() {
        // Java 的字符串可以带孤立的代理项，编不成 UTF-8：按语法错误处理，而不是像 getBytes 那样换成 ?
        assertEquals(Optional.of(new JsonText.Problem(true, "$")), JsonText.check("{\"a\":\"\uD800\"}"));
        assertEquals(Optional.empty(), JsonText.encode("\uDC00"));
    }

    @Test
    void stringsAreQuotedWithTheMinimalEscapes() {
        assertEquals("\"q\\\"b\\\\s/\\n\\t\\b\\f\\r\\u0001\\u001f\u007f\u0085  é 中 😀\"",
                JsonText.quote("q\"b\\s/\n\t\b\f\r\u0001\u001f\u007f\u0085  é 中 😀"));
        assertEquals("\"\\ud800x\\udc00\"", JsonText.quote("\uD800x\uDC00"), "lone surrogates are escaped in lower case");
    }

    @Test
    void pathsFollowRfc0011() {
        assertEquals("$.a", JsonText.memberPath("$", "a"));
        assertEquals("$['a b']", JsonText.memberPath("$", "a b"));
        assertEquals("$.", JsonText.memberPath("$", ""));
        assertEquals("$.lines[1]", JsonText.indexPath("$.lines", 1));
    }
}
