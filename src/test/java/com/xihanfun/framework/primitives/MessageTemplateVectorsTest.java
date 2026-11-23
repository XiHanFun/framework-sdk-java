package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.Vectors;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/primitives/message-templates.json}：消息键文法、宽松与严格格式化、{@code Describe}。
 */
class MessageTemplateVectorsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void keysFollowTheVectors() throws IOException {
        JsonNode keys = Vectors.read("primitives/message-templates.json").get("keys");
        for (String key : Vectors.strings(keys.get("valid"))) {
            assertTrue(MessageTemplate.isValidKey(key), key + " must be valid");
        }
        for (String key : Vectors.strings(keys.get("invalid"))) {
            assertFalse(MessageTemplate.isValidKey(key), key + " must be invalid");
        }
    }

    @Test
    void formattingFollowsTheVectors() throws IOException {
        for (JsonNode vector : Vectors.read("primitives/message-templates.json").get("format")) {
            String name = vector.get("name").textValue();
            String template = vector.get("template").textValue();
            Map<String, Object> arguments = arguments(vector.get("arguments"));
            JsonNode strict = vector.get("strict");
            Optional<String> expected = strict.isNull() ? Optional.empty() : Optional.of(strict.textValue());
            if (vector.has("locale")) {
                // 区域只改变小数点与分组符（ADR-0236）
                String locale = vector.get("locale").textValue();
                assertEquals(vector.get("lenient").textValue(), MessageTemplate.format(template, arguments, locale), name + " (lenient)");
                assertEquals(expected, MessageTemplate.tryFormat(template, arguments, locale), name + " (strict)");
                assertEquals(expected, MessageCatalog.of(Map.of("en-US", Map.of("a.b", template))).resolve("a.b", locale, arguments),
                        name + " (catalog)");
            } else {
                assertEquals(vector.get("lenient").textValue(), MessageTemplate.format(template, arguments), name + " (lenient)");
                assertEquals(expected, MessageTemplate.tryFormat(template, arguments), name + " (strict)");
            }
        }
    }

    @Test
    void describeFollowsTheVectors() throws IOException {
        for (JsonNode vector : Vectors.read("primitives/message-templates.json").get("describe")) {
            String key = vector.get("key").textValue();
            String expected = vector.get("text").textValue();
            JsonNode arguments = vector.get("arguments");
            String actual = arguments.isNull() ? MessageTemplate.describe(key) : MessageTemplate.describe(key, arguments(arguments));
            assertEquals(expected, actual, key + " " + arguments);
        }
    }

    /**
     * 参数的 JSON 值按 Jackson 的缺省类型交给格式化：整数为 Integer / Long / BigInteger，浮点为 Double，数组为 List，对象为 Map；
     * 格式化自己把它们归到值模型（超出 64 位的整数是浮点）。{@code null} 的参数表表示没有参数。
     */
    private static Map<String, Object> arguments(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, new TypeReference<>() { });
    }
}
