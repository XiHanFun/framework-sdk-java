package com.xihanfun.framework.manifest;

import java.util.List;
import java.util.Map;

/**
 * 规范化的 JSON 文本：两空格缩进、字段按给定顺序、没有末尾换行，字符串转义与 Go 的 {@code encoding/json} 相同。
 *
 * <p>与 Go SDK 的 {@code json.MarshalIndent(manifest, "", "  ")} 逐字节一致，所以各语言 SDK 写出的同一份清单完全相同。
 */
final class CanonicalJson {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CanonicalJson() {
    }

    /**
     * 写出一个值：{@link String}、{@link Boolean}、元素是这些类型的 {@link List}，或按插入顺序的 {@link Map}。
     */
    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        write(builder, value, 0);
        return builder.toString();
    }

    private static void write(StringBuilder builder, Object value, int depth) {
        if (value instanceof String text) {
            quote(builder, text);
        } else if (value instanceof Boolean bool) {
            builder.append(bool.booleanValue());
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                builder.append("[]");
                return;
            }
            builder.append('[');
            for (int index = 0; index < list.size(); index++) {
                newline(builder, depth + 1);
                write(builder, list.get(index), depth + 1);
                if (index < list.size() - 1) {
                    builder.append(',');
                }
            }
            newline(builder, depth);
            builder.append(']');
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                builder.append("{}");
                return;
            }
            builder.append('{');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                newline(builder, depth + 1);
                quote(builder, (String) entry.getKey());
                builder.append(": ");
                write(builder, entry.getValue(), depth + 1);
                if (++index < map.size()) {
                    builder.append(',');
                }
            }
            newline(builder, depth);
            builder.append('}');
        } else {
            throw new IllegalStateException("The canonical writer cannot write a value of type '" + value.getClass().getName() + "'.");
        }
    }

    private static void newline(StringBuilder builder, int depth) {
        builder.append('\n');
        for (int level = 0; level < depth; level++) {
            builder.append("  ");
        }
    }

    /**
     * 按 Go 的规则转义：引号、反斜杠与控制字符；{@code <}、{@code >}、{@code &} 与 U+2028、U+2029 写成六个字符的 Unicode 转义；
     * 孤立的代理项写成 U+FFFD。
     */
    private static void quote(StringBuilder builder, String text) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '<', '>', '&', ' ', ' ' -> unicode(builder, current);
                default -> {
                    if (current < 0x20) {
                        unicode(builder, current);
                    } else if (Character.isHighSurrogate(current) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                        builder.append(current).append(text.charAt(++index));
                    } else if (Character.isSurrogate(current)) {
                        builder.append("\\ufffd");
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static void unicode(StringBuilder builder, char value) {
        builder.append("\\u").append(HEX[(value >> 12) & 0xF]).append(HEX[(value >> 8) & 0xF]).append(HEX[(value >> 4) & 0xF])
                .append(HEX[value & 0xF]);
    }
}
