package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * 逐个词法单元读出 JSON 树，同一层出现重复的成员名即拒绝（RFC-0001「Result」，ADR-0235）。
 *
 * <p>Jackson 的树读取对重复成员缺省取最后一个，严格与否取决于使用者的 {@code ObjectMapper} 配置；编码规则不能随它变，所以 Result 与 Error
 * 的成员由这里读，不经那份配置。
 */
final class StrictTrees {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private StrictTrees() {
    }

    /**
     * 读出解析器当前所在的值；对象的每一层都核对重复的成员名。
     */
    static JsonNode read(JsonParser parser, DeserializationContext context, Class<?> target) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == null) {
            token = parser.nextToken();
        }
        if (token == null) {
            return context.reportInputMismatch(target, "%s", "The JSON input ended before a value.");
        }
        switch (token) {
            case START_OBJECT -> {
                ObjectNode object = NODES.objectNode();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    parser.nextToken();
                    if (object.has(name)) {
                        return context.reportInputMismatch(target, "The member '%s' appears more than once in the same object.", name);
                    }
                    object.set(name, read(parser, context, target));
                }
                return object;
            }
            case START_ARRAY -> {
                ArrayNode array = NODES.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(read(parser, context, target));
                }
                return array;
            }
            case VALUE_STRING -> {
                return NODES.textNode(parser.getText());
            }
            case VALUE_NUMBER_INT -> {
                return switch (parser.getNumberType()) {
                    case INT -> NODES.numberNode(parser.getIntValue());
                    case LONG -> NODES.numberNode(parser.getLongValue());
                    default -> NODES.numberNode(parser.getBigIntegerValue());
                };
            }
            case VALUE_NUMBER_FLOAT -> {
                return NODES.numberNode(parser.getDoubleValue());
            }
            case VALUE_TRUE -> {
                return NODES.booleanNode(true);
            }
            case VALUE_FALSE -> {
                return NODES.booleanNode(false);
            }
            case VALUE_NULL -> {
                return NODES.nullNode();
            }
            default -> {
                return context.reportInputMismatch(target, "Unexpected JSON token %s.", token);
            }
        }
    }
}
