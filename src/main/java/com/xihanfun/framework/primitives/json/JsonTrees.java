package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON 树到 Java 值的转换：细节表只含 JSON 值（RFC-0001「Result」，ADR-0236）；按值模型写出见内部的 {@code JsonValueWriter}。
 */
final class JsonTrees {
    private JsonTrees() {
    }

    /**
     * 把 JSON 对象节点转成键按字典序排列的表；数字已由 {@link StrictTrees} 按值模型读成整数或双精度浮点。
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
}
