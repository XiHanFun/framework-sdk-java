package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            assertEquals(vector.get("lenient").textValue(), MessageTemplate.format(template, arguments), name + " (lenient)");
            JsonNode strict = vector.get("strict");
            assertEquals(strict.isNull() ? Optional.empty() : Optional.of(strict.textValue()), MessageTemplate.tryFormat(template, arguments),
                    name + " (strict)");
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
     * 参数的 JSON 值按原类型交给格式化：整数为 Integer、浮点为 Double、布尔、字符串与 null；{@code null} 的参数表表示没有参数。
     */
    private static Map<String, Object> arguments(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, new TypeReference<>() { });
    }
}
