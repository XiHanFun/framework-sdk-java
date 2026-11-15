package com.xihanfun.framework.primitives;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 消息键文法与模板格式化（RFC-0010）。
 *
 * <p>消息键是 {@code <领域>.<段>[.<段>…]}，每段 {@code [a-z][a-z0-9_]*}。模板里 {@code {name}} 按名字、{@code {0}} 按位置取参数，
 * 可带格式说明 {@code {name:%05d}}（以 {@code %} 开头时按 {@link java.util.Formatter} 解释，其他写法输出参数本身）；
 * <code>{{</code> 与 <code>}}</code> 转义单个花括号，花括号里不是合法名字的按字面量输出。
 */
public final class MessageTemplate {
    /** 消息键文法：首段与后段同形（2026-10-28 修订，首段放开下划线）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    /** 占位符名：字母开头的标识或纯数字的位置序号。 */
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*|[0-9]+)$");

    private MessageTemplate() {
    }

    /**
     * 判断消息键是否合法。
     *
     * @param key 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * 校验消息键并原样返回。
     *
     * @param key 消息键
     * @return 原样返回 {@code key}
     * @throws IllegalArgumentException 文法不合法
     */
    public static String validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("The message key '" + key + "' must match <domain>.<segment>[.<segment>].");
        }
        return key;
    }

    /**
     * 取消息键的领域段（第一个点之前）。
     *
     * @param key 消息键
     * @return 领域段
     * @throws IllegalArgumentException 文法不合法
     */
    public static String domainOf(String key) {
        validateKey(key);
        return key.substring(0, key.indexOf('.'));
    }

    /**
     * 列出模板里的占位符名，按出现顺序、去重。
     *
     * @param template 模板
     * @return 占位符名
     */
    public static List<String> placeholdersOf(String template) {
        Objects.requireNonNull(template, "template");
        List<String> names = new ArrayList<>();
        Cursor cursor = new Cursor(template);
        while (cursor.hasNext()) {
            Segment segment = cursor.next();
            if (segment.name() != null && !names.contains(segment.name())) {
                names.add(segment.name());
            }
        }
        return List.copyOf(names);
    }

    /**
     * 用参数填充模板，按与区域无关的规则格式化；缺参数的占位符原样保留，让遗漏可见。
     *
     * @param template 模板
     * @param arguments 参数；位置参数以 {@code "0"}、{@code "1"}… 为键，见 {@link MessageArguments#positional(Object...)}
     * @return 填充后的文本
     */
    public static String format(String template, Map<String, ?> arguments) {
        return format(template, arguments, Locale.ROOT);
    }

    /**
     * 用参数填充模板，按给定区域格式化；缺参数的占位符原样保留。
     *
     * @param template 模板
     * @param arguments 参数
     * @param locale 格式说明使用的区域
     * @return 填充后的文本
     */
    public static String format(String template, Map<String, ?> arguments, Locale locale) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(locale, "locale");
        return render(template, arguments, locale, false).orElseThrow();
    }

    /**
     * 严格地填充模板：有占位符缺参数即失败。消息解析器用它判断模板能否完整渲染（RFC-0010「解析」）。
     *
     * @param template 模板
     * @param arguments 参数
     * @param locale 格式说明使用的区域
     * @return 填充后的文本；有占位符缺参数时为空
     */
    public static Optional<String> tryFormat(String template, Map<String, ?> arguments, Locale locale) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(locale, "locale");
        return render(template, arguments, locale, true);
    }

    /**
     * 在没有文案时描述一个消息：只有键。
     *
     * @param key 消息键
     * @return 键本身
     */
    public static String describe(String key) {
        return Objects.requireNonNull(key, "key");
    }

    /**
     * 在没有文案时描述一个消息：{@code 键 (k=v, …)}，参数按名字排序。
     *
     * @param key 消息键
     * @param arguments 参数；为空时只返回键
     * @return 描述
     */
    public static String describe(String key, Map<String, ?> arguments) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.isEmpty()) {
            return key;
        }
        // 参数按名字排序，描述与线上的细节编码一样稳定，不随传入表的迭代顺序变化
        StringBuilder builder = new StringBuilder(key).append(" (");
        boolean first = true;
        for (Map.Entry<String, ?> entry : new TreeMap<String, Object>(arguments).entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(text(entry.getValue()));
        }
        return builder.append(')').toString();
    }

    /**
     * 把参数值转成文本：{@code null} 为空串，整数值的浮点数不带小数部分（与 Go 和 .NET 的默认格式一致）。
     *
     * @param value 参数值
     * @return 文本
     */
    static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number) && number == Math.rint(number) && Math.abs(number) < 1e15) {
                return Long.toString((long) number);
            }
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    private static Optional<String> render(String template, Map<String, ?> arguments, Locale locale, boolean strict) {
        StringBuilder builder = new StringBuilder(template.length() + 16);
        Cursor cursor = new Cursor(template);
        while (cursor.hasNext()) {
            Segment segment = cursor.next();
            if (segment.name() == null) {
                builder.append(segment.literal());
                continue;
            }
            if (!arguments.containsKey(segment.name())) {
                // 缺值：严格模式整条拒绝；宽松模式把占位符原样留在文本里，让遗漏可见而不是悄悄吞掉
                if (strict) {
                    return Optional.empty();
                }
                builder.append(segment.literal());
                continue;
            }
            Object value = arguments.get(segment.name());
            String format = segment.format();
            if (format != null && format.startsWith("%")) {
                try {
                    builder.append(String.format(locale, format, value));
                } catch (IllegalFormatException invalid) {
                    // 格式说明与参数类型不符：原样保留占位符，与缺参数同样可见
                    builder.append(segment.literal());
                }
            } else {
                builder.append(text(value));
            }
        }
        return Optional.of(builder.toString());
    }

    /** 模板的一段：字面量，或占位符（名字、可选的格式说明与原文）。 */
    private record Segment(String literal, String name, String format) {
    }

    /** 逐段读取模板。 */
    private static final class Cursor {
        private final String text;
        private int index;

        Cursor(String text) {
            this.text = text;
        }

        boolean hasNext() {
            return index < text.length();
        }

        Segment next() {
            char current = text.charAt(index);
            // "{{" 与 "}}" 是转义的单个花括号
            if ((current == '{' || current == '}') && index + 1 < text.length() && text.charAt(index + 1) == current) {
                index += 2;
                return new Segment(String.valueOf(current), null, null);
            }
            if (current != '{') {
                int end = index + 1;
                while (end < text.length() && text.charAt(end) != '{' && text.charAt(end) != '}') {
                    end++;
                }
                String literal = text.substring(index, end);
                index = end;
                return new Segment(literal, null, null);
            }
            int end = text.indexOf('}', index + 1);
            if (end < 0) {
                // 没有闭合的左花括号按字面量输出
                String literal = text.substring(index);
                index = text.length();
                return new Segment(literal, null, null);
            }
            String body = text.substring(index + 1, end);
            String original = text.substring(index, end + 1);
            index = end + 1;
            int separator = body.indexOf(':');
            String candidate = separator < 0 ? body : body.substring(0, separator);
            if (!PLACEHOLDER_NAME.matcher(candidate).matches()) {
                // 花括号里不是合法名字（如 JSON 片段）按字面量输出
                return new Segment(original, null, null);
            }
            return new Segment(original, candidate, separator < 0 ? null : body.substring(separator + 1));
        }
    }
}
