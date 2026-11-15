package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON 树与 Java 值之间的转换：细节表只含 JSON 值，按值本身写出，不受 {@code ObjectMapper} 的配置影响。
 */
final class JsonTrees {
    private JsonTrees() {
    }

    /**
     * 把 JSON 对象节点转成键按字典序排列的表。
     */
    static Map<String, Object> toMap(JsonNode node) {
        TreeMap<String, Object> map = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            map.put(field.getKey(), toValue(field.getValue()));
        }
        return map;
    }

    private static Object toValue(JsonNode node) {
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                list.add(toValue(element));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return null;
    }

    /**
     * 写出一个 JSON 值：细节表的成员已经由 {@code Error} 校验为 JSON 值。
     */
    static void write(JsonGenerator generator, Object value) throws IOException {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof String text) {
            generator.writeString(text);
        } else if (value instanceof Boolean bool) {
            generator.writeBoolean(bool);
        } else if (value instanceof BigInteger integer) {
            generator.writeNumber(integer);
        } else if (value instanceof BigDecimal decimal) {
            generator.writeNumber(decimal);
        } else if (value instanceof Double || value instanceof Float) {
            generator.writeNumber(((Number) value).doubleValue());
        } else if (value instanceof Number number) {
            generator.writeNumber(number.longValue());
        } else if (value instanceof List<?> list) {
            generator.writeStartArray();
            for (Object element : list) {
                write(generator, element);
            }
            generator.writeEndArray();
        } else if (value instanceof Map<?, ?> map) {
            generator.writeStartObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                generator.writeFieldName((String) entry.getKey());
                write(generator, entry.getValue());
            }
            generator.writeEndObject();
        } else {
            throw new IllegalStateException("The value of type '" + value.getClass().getName() + "' is not a JSON value.");
        }
    }
}
