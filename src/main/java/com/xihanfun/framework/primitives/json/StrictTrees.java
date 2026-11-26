package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xihanfun.framework.internal.UnicodeText;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xihanfun.framework.primitives.JsonText;
import java.io.IOException;
import java.math.BigInteger;

/**
 * 逐个词法单元读出 JSON 树，按 RFC-0001「JSON 文本」核对解析器看得见的部分（ADR-0235、ADR-0236）：
 * 任何一层的重复成员名、超过 {@value JsonText#MAX_DEPTH} 层的嵌套、字符串与成员名里孤立的代理项、{@code NaN} 这类非 JSON 的数。
 *
 * <p>Jackson 的树读取对重复成员缺省取最后一个、不查代理项，严格与否取决于使用者的 {@code ObjectMapper} 配置；编码规则不能随它变，
 * 所以 Result 与 Error（含 {@code value}、{@code details} 与 {@code inner} 的内部）由这里读，不经那份配置。BOM、非法 UTF-8 与注释这类
 * 文本层面的问题在词法单元里看不见，由 {@link PrimitivesJson} 读文本时先过 {@link JsonText#check(byte[])}。
 *
 * <p>数字按值模型读（RFC-0010「模板」）：64 位范围内的整数写法是整数，原样往返；其余（超出 64 位的整数、带小数点或指数的数）是双精度浮点。
 */
final class StrictTrees {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private StrictTrees() {
    }

    /**
     * 读出解析器当前所在的值。
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
                requireDepth(parser, context, target);
                ObjectNode object = NODES.objectNode();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = text(parser.currentName(), context, target);
                    parser.nextToken();
                    if (object.has(name)) {
                        return context.reportInputMismatch(target, "The member '%s' appears more than once in the same object.", name);
                    }
                    object.set(name, read(parser, context, target));
                }
                return object;
            }
            case START_ARRAY -> {
                requireDepth(parser, context, target);
                ArrayNode array = NODES.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(read(parser, context, target));
                }
                return array;
            }
            case VALUE_STRING -> {
                return NODES.textNode(text(parser.getText(), context, target));
            }
            case VALUE_NUMBER_INT -> {
                return switch (parser.getNumberType()) {
                    case INT -> NODES.numberNode(parser.getIntValue());
                    case LONG -> NODES.numberNode(parser.getLongValue());
                    default -> integer(parser.getBigIntegerValue());
                };
            }
            case VALUE_NUMBER_FLOAT -> {
                if (parser.isNaN()) {
                    return context.reportInputMismatch(target, "%s", "NaN and Infinity are not JSON numbers.");
                }
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

    /**
     * 核对当前对象或数组所在的层数：解析器的嵌套深度从最外层的 1 算起，与规范的层数相同。
     */
    static void requireDepth(JsonParser parser, DeserializationContext context, Class<?> target) throws IOException {
        if (parser.getParsingContext().getNestingDepth() > JsonText.MAX_DEPTH) {
            context.reportInputMismatch(target, "The JSON nests deeper than %d levels.", JsonText.MAX_DEPTH);
        }
    }

    /**
     * 超出 64 位的整数按值模型是双精度浮点。
     */
    private static JsonNode integer(BigInteger value) {
        return value.bitLength() < Long.SIZE ? NODES.numberNode(value.longValue()) : NODES.numberNode(value.doubleValue());
    }

    /**
     * 字符串与成员名必须是合法的 Unicode：Jackson 把 {@code \ud800} 原样还原成孤立的代理项，这里拒绝。
     */
    private static String text(String value, DeserializationContext context, Class<?> target) throws IOException {
        if (UnicodeText.hasLoneSurrogate(value)) {
            return context.reportInputMismatch(target, "%s", "A JSON string contains a lone surrogate.");
        }
        return value;
    }
}
