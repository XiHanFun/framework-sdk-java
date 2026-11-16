package com.xihanfun.framework.primitives;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * JSON 值的校验与不可变复制：{@link Error#details()} 只允许 JSON 可表示的值（RFC-0001）。
 *
 * <p>允许的值：{@code null}、{@link String}、{@link Boolean}、{@link Byte} / {@link Short} / {@link Integer} /
 * {@link Long} / {@link BigInteger}、{@link Float} / {@link Double}、{@link BigDecimal}、
 * 元素都合法的 {@link List}、键是字符串且值都合法的 {@link Map}。
 *
 * <p>数字按值模型写到线上（RFC-0001「Result」，ADR-0236）：64 位范围内的整数原样，超出 64 位的整数、单精度与十进制小数按双精度浮点；
 * 整数值的浮点写成带小数点或指数的写法，非有限的浮点写成字符串 {@code NaN}、{@code Infinity}、{@code -Infinity}。
 */
final class JsonValues {
    private JsonValues() {
    }

    /**
     * 校验并复制一份细节表：键按字典序排列（与 Go 的 encoding/json 一致，线上编码因此稳定），嵌套的表与列表同样复制成只读。
     *
     * @param details 细节表
     * @return 只读、按键排序的副本
     * @throws IllegalArgumentException 有不是 JSON 值的成员
     */
    static SortedMap<String, Object> copyObject(Map<String, ?> details) {
        Objects.requireNonNull(details, "details");
        if (details.isEmpty()) {
            return Collections.emptySortedMap();
        }
        TreeMap<String, Object> copy = new TreeMap<>();
        for (Map.Entry<String, ?> entry : details.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "A detail name must not be null.");
            copy.put(name, copyValue(name, entry.getValue()));
        }
        return Collections.unmodifiableSortedMap(copy);
    }

    private static Object copyValue(String path, Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Double || value instanceof Float) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                copy.add(copyValue(path + "[" + index + "]", list.get(index)));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> copy = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new IllegalArgumentException("The detail '" + path + "' must be an object with string keys.");
                }
                copy.put(name, copyValue(path + "." + name, entry.getValue()));
            }
            return Collections.unmodifiableSortedMap(copy);
        }
        throw new IllegalArgumentException("The detail '" + path + "' of type '" + value.getClass().getName()
                + "' is not a JSON value; use strings, numbers, booleans, lists, maps or null.");
    }
}
