package com.xihanfun.framework.primitives;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 消息键文法与模板格式化（RFC-0010「消息键」「模板」，ADR-0233）。
 *
 * <p>消息键是 {@code <领域>.<段>[.<段>…]}，每段 {@code [a-z][a-z0-9_]*}，整个键最长 {@value #MAX_KEY_LENGTH} 个字符。
 *
 * <p>模板里 {@code {name}} 按名字、{@code {0}} 按位置取参数，第一个 {@code :} 之后是格式说明符。可移植的格式说明符按不变文化解释：
 * <ul>
 *   <li>{@code D} / {@code Dn}：整数，至少 n 位，不足补零，负号在前</li>
 *   <li>{@code X} / {@code Xn} 与 {@code x} / {@code xn}：非负整数的十六进制，大写或小写，至少 n 位</li>
 *   <li>{@code F} / {@code Fn}：定点小数，n 位小数，缺省 2</li>
 *   <li>{@code N} / {@code Nn}：带千分位（{@code ,}）的定点小数，n 位小数，缺省 2</li>
 * </ul>
 * {@code F} 与 {@code N} 按值的精确十进制展开舍入，正好一半时取偶（与 .NET 一致，不同于 {@link String#format} 的一半进位）。
 * 其余说明符（日期时间、自定义数字模式等）是 .NET 的格式串，由宿主解释，这里按无格式输出、不报错。
 *
 * <p><code>{{</code> 与 <code>}}</code> 转义单个花括号；花括号里不是合法名字的、没有闭合的 <code>{</code> 及其后的文本原样输出；参数值里的花括号不再解释。
 */
public final class MessageTemplate {
    /** 消息键的最大长度（与 .NET 的 {@code MessageTemplate.MaxKeyLength} 相同）。 */
    public static final int MAX_KEY_LENGTH = 128;

    /** 消息键文法：首段与后段同形（2026-10-28 修订，首段放开下划线）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");

    /** 占位符名：字母开头的标识或纯数字的位置序号。 */
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*|[0-9]+)$");

    /** 格式说明符里位数的上限：输出长度随位数增长，超出即当作不支持的说明符按无格式输出。 */
    private static final int MAX_PRECISION = 99;

    private MessageTemplate() {
    }

    /**
     * 判断消息键是否合法：合文法且不超过 {@value #MAX_KEY_LENGTH} 个字符。
     *
     * @param key 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValidKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * 校验消息键并原样返回。
     *
     * @param key 消息键
     * @return 原样返回 {@code key}
     * @throws IllegalArgumentException 文法不合法或超长
     */
    public static String validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("The message key '" + key + "' must match <domain>.<segment>[.<segment>] with at most "
                    + MAX_KEY_LENGTH + " characters.");
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
     * 宽松地填充模板：缺值的占位符连同格式说明符原样保留，让遗漏可见。
     *
     * @param template 模板
     * @param arguments 参数；位置参数以 {@code "0"}、{@code "1"}… 为键，见 {@link MessageArguments#positional(Object...)}；
     *     参数存在而值为 {@code null} 不算缺值，写空串
     * @return 填充后的文本
     */
    public static String format(String template, Map<String, ?> arguments) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(arguments, "arguments");
        return render(template, arguments, false).orElseThrow();
    }

    /**
     * 严格地填充模板：任一占位符缺值即失败。消息解析器用它判断模板能否完整渲染，失败时调用方退回 {@link #describe(String, Map)}。
     *
     * @param template 模板
     * @param arguments 参数
     * @return 填充后的文本；有占位符缺值时为空
     */
    public static Optional<String> tryFormat(String template, Map<String, ?> arguments) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(arguments, "arguments");
        return render(template, arguments, true);
    }

    /**
     * 在没有文案时描述一个消息：没有参数时就是键本身。
     *
     * @param key 消息键
     * @return 键本身
     */
    public static String describe(String key) {
        return Objects.requireNonNull(key, "key");
    }

    /**
     * 在没有文案时描述一个消息：{@code 键 (name=value, …)}，值按 {@link #text(Object)} 的文本形式，参数按名字排序。
     *
     * <p>它只给人看，不参与程序判断；参数的先后顺序规范不作规定，这里按名字排序，结果稳定。
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
     * 把参数值转成文本（RFC-0010「值的文本形式」）：字符串原样，整数十进制，布尔写 {@code true} / {@code false}，{@code null} 写空串；
     * 浮点在 1e-4 ≤ |x| &lt; 1e15 或 x = 0 时写能往返的最短十进制、不用指数，整数值的浮点不带小数点。
     *
     * <p>范围之外的浮点、数组与对象规范不作规定，这里用 Java 的缺省文本。
     *
     * @param value 参数值
     * @return 文本
     */
    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double number) {
            return floating(number, Double.toString(number));
        }
        if (value instanceof Float number) {
            // Float.toString 是 float 精度下最短的往返文本，不经 double 放大出多余的位
            return floating(number.doubleValue(), Float.toString(number));
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value.toString();
    }

    private static String floating(double number, String shortest) {
        double magnitude = Math.abs(number);
        if (!Double.isFinite(number) || (magnitude != 0 && (magnitude < 1e-4 || magnitude >= 1e15))) {
            return shortest;
        }
        if (number == Math.rint(number)) {
            return Long.toString((long) number);
        }
        // Java 的 Double.toString 自 JDK 19 起是最短往返形式，只是可能带指数（如 1.0E-4）；转成十进制再去掉末尾的零
        return new BigDecimal(shortest).stripTrailingZeros().toPlainString();
    }

    private static Optional<String> render(String template, Map<String, ?> arguments, boolean strict) {
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
            builder.append(format == null ? text(value) : formatted(value, format).orElseGet(() -> text(value)));
        }
        return Optional.of(builder.toString());
    }

    /**
     * 按可移植的格式说明符格式化；说明符不支持或与值的类型不符时为空，调用方按无格式输出。
     */
    private static Optional<String> formatted(Object value, String format) {
        if (format.isEmpty()) {
            return Optional.empty();
        }
        char kind = format.charAt(0);
        String digits = format.substring(1);
        Integer precision = null;
        if (!digits.isEmpty()) {
            if (digits.length() > 2 || !digits.chars().allMatch(c -> c >= '0' && c <= '9') || Integer.parseInt(digits) > MAX_PRECISION) {
                return Optional.empty();
            }
            precision = Integer.parseInt(digits);
        }
        int width = precision == null ? 0 : precision;
        int scale = precision == null ? 2 : precision;
        // D、F、N 与 .NET 一样不区分大小写；X 与 x 决定十六进制字母的大小写
        return switch (kind) {
            case 'D', 'd' -> integer(value).map(number -> (number.signum() < 0 ? "-" : "") + pad(number.abs().toString(), width));
            case 'X' -> integer(value).filter(number -> number.signum() >= 0).map(number -> pad(number.toString(16).toUpperCase(Locale.ROOT), width));
            case 'x' -> integer(value).filter(number -> number.signum() >= 0).map(number -> pad(number.toString(16), width));
            case 'F', 'f' -> decimal(value).map(number -> number.setScale(scale, RoundingMode.HALF_EVEN).toPlainString());
            case 'N', 'n' -> decimal(value).map(number -> grouped(number.setScale(scale, RoundingMode.HALF_EVEN).toPlainString()));
            default -> Optional.empty();
        };
    }

    private static Optional<BigInteger> integer(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return Optional.of(BigInteger.valueOf(((Number) value).longValue()));
        }
        if (value instanceof BigInteger number) {
            return Optional.of(number);
        }
        return Optional.empty();
    }

    /**
     * 取值的精确十进制：浮点按二进制值精确展开（{@code new BigDecimal(double)}），2.675 因此是 2.67499…，不是正好一半。
     */
    private static Optional<BigDecimal> decimal(Object value) {
        if (value instanceof BigDecimal number) {
            return Optional.of(number);
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? Optional.of(new BigDecimal(number)) : Optional.empty();
        }
        return integer(value).map(BigDecimal::new);
    }

    private static String pad(String digits, int width) {
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }

    /**
     * 给定点小数的整数部分加千分位：不变文化下分组符是 {@code ,}、小数点是 {@code .}，负号在前。
     */
    private static String grouped(String plain) {
        boolean negative = plain.startsWith("-");
        String unsigned = negative ? plain.substring(1) : plain;
        int point = unsigned.indexOf('.');
        String whole = point < 0 ? unsigned : unsigned.substring(0, point);
        String fraction = point < 0 ? "" : unsigned.substring(point);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < whole.length(); index++) {
            if (index > 0 && (whole.length() - index) % 3 == 0) {
                builder.append(',');
            }
            builder.append(whole.charAt(index));
        }
        return (negative ? "-" : "") + builder + fraction;
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
                // 没有闭合的左花括号连同其后的文本按字面量输出
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
