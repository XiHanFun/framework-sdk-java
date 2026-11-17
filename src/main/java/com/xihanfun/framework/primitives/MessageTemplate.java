package com.xihanfun.framework.primitives;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 消息键文法与模板格式化（RFC-0010「消息键」「模板」，ADR-0233、ADR-0235、ADR-0236）。
 *
 * <p>消息键是 {@code <领域>.<段>[.<段>…]}，每段 {@code [a-z][a-z0-9_]*}，整个键最长 {@value #MAX_KEY_LENGTH} 个字符。
 *
 * <p>模板里 {@code {name}} 按名字、{@code {0}} 按位置取参数，第一个 {@code :} 之后是格式说明符。
 *
 * <p><b>值模型</b>：格式化之前先把参数值归到可移植的值模型，只有空、布尔、字符串、64 位整数、双精度浮点、数组与对象七种：
 * <ul>
 *   <li>{@link Byte}、{@link Short}、{@link Integer}、{@link Long}、{@link AtomicInteger}、{@link AtomicLong} 是整数；
 *       {@link BigInteger} 在 64 位有符号范围内是整数，超出时是双精度浮点</li>
 *   <li>{@link Float} 与 {@link BigDecimal} 先换成最接近的双精度浮点：单精度的 0.1 写 {@code 0.10000000149011612}，{@code 2.50} 写 {@code 2.5}</li>
 *   <li>{@link Character} 与其他 {@link CharSequence} 是字符串；键都是字符串的 {@link Map} 是对象；{@link Collection} 与 Java 数组是数组</li>
 *   <li>模型之外的值（日期时间、枚举、UUID 等）写成 {@code toString()} 的文本，不跨语言；插件要跨语言格式化时先转成字符串</li>
 * </ul>
 *
 * <p><b>可移植的格式说明符</b>：说明符符合 {@code ^[DdFfNnXx][0-9]{0,2}$} 才认，其余（{@code E2}、{@code G}、{@code 0.00}、
 * {@code yyyy}、空说明符、带空格、{@code D100}）一律按无格式写出：
 * <ul>
 *   <li>{@code D} / {@code Dn}：整数，至少 n 位，不足补零，负号在前</li>
 *   <li>{@code X} / {@code Xn} 与 {@code x} / {@code xn}：非负整数的十六进制，大写或小写，至少 n 位</li>
 *   <li>{@code F} / {@code Fn}：整数与浮点的定点小数，n 位小数，缺省 2</li>
 *   <li>{@code N} / {@code Nn}：带分组符的定点小数，n 位小数，缺省 2</li>
 * </ul>
 * {@code D}、{@code F}、{@code N} 不区分大小写；不适用的值（浮点的 {@code D}、负数的 {@code X}、字符串、布尔、{@code null}、数组与对象）按无格式写出。
 * {@code F} 与 {@code N} 按值的精确十进制展开舍入，正好一半时取偶（与 .NET 一致，不同于 {@link String#format} 的一半进位）。
 *
 * <p><b>区域</b>：只改变小数点与分组符（{@link #format(String, Map, String)}），取自规范的固定表，区域名不区分大小写：
 * de-DE、es-ES、pt-BR 是 {@code ,} 与 {@code .}；fr-FR 是 {@code ,} 与 U+202F；ru-RU 是 {@code ,} 与 U+00A0；en-US、zh-CN、zh-TW、ja-JP、ko-KR、
 * 表外的区域与不给区域都是 {@code .} 与 {@code ,}。小数点用在无格式的浮点（含指数写法的尾数）与 {@code F}、{@code N} 上，分组符只用在
 * {@code N} 上；整数无格式时不分组，{@code D}、{@code X} 与数组、对象里的数字不受区域影响；负号、{@code NaN}、{@code Infinity} 不随区域变化。
 *
 * <p><code>{{</code> 与 <code>}}</code> 转义单个花括号；花括号里不是合法名字的、没有闭合的 <code>{</code> 及其后的文本原样输出；参数值里的花括号不再解释。
 */
public final class MessageTemplate {
    /** 消息键的最大长度（与 .NET 的 {@code MessageTemplate.MaxKeyLength} 相同）。 */
    public static final int MAX_KEY_LENGTH = 128;

    /** 数组与对象写成 JSON 文本时的最大层数，更深的容器写成 {@code null}。 */
    public static final int MAX_DEPTH = 64;

    /** 消息键文法：首段与后段同形（2026-10-28 修订，首段放开下划线）；整串匹配，结尾的换行不例外。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

    /** 占位符名：字母开头的标识或纯数字的位置序号。 */
    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("([A-Za-z][A-Za-z0-9_]*|[0-9]+)");

    /** 可移植的格式说明符：一个字母加至多两位精度。 */
    private static final Pattern SPECIFIER = Pattern.compile("[DdFfNnXx][0-9]{0,2}");

    /** 不变文化的数字符号。 */
    private static final Symbols INVARIANT = new Symbols('.', ',');

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

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
     * 按不变文化宽松地填充模板：缺值的占位符连同格式说明符原样保留，让遗漏可见。
     *
     * @param template 模板
     * @param arguments 参数；位置参数以 {@code "0"}、{@code "1"}… 为键，见 {@link MessageArguments#positional(Object...)}；
     *     参数存在而值为 {@code null} 不算缺值，写空串
     * @return 填充后的文本
     */
    public static String format(String template, Map<String, ?> arguments) {
        return render(template, arguments, INVARIANT, false).orElseThrow();
    }

    /**
     * 按区域宽松地填充模板：区域只改变小数点与分组符，取自规范的固定表；表外的区域按不变文化。
     *
     * @param template 模板
     * @param arguments 参数
     * @param locale 区域名，不区分大小写
     * @return 填充后的文本
     */
    public static String format(String template, Map<String, ?> arguments, String locale) {
        return render(template, arguments, symbols(locale), false).orElseThrow();
    }

    /**
     * 按不变文化严格地填充模板：任一占位符缺值即失败。消息解析器用它判断模板能否完整渲染，失败时调用方退回 {@link #describe(String, Map)}。
     *
     * @param template 模板
     * @param arguments 参数
     * @return 填充后的文本；有占位符缺值时为空
     */
    public static Optional<String> tryFormat(String template, Map<String, ?> arguments) {
        return render(template, arguments, INVARIANT, true);
    }

    /**
     * 按区域严格地填充模板，区域的作用同 {@link #format(String, Map, String)}。
     *
     * @param template 模板
     * @param arguments 参数
     * @param locale 区域名，不区分大小写
     * @return 填充后的文本；有占位符缺值时为空
     */
    public static Optional<String> tryFormat(String template, Map<String, ?> arguments, String locale) {
        return render(template, arguments, symbols(locale), true);
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
     * 在没有文案时描述一个消息：{@code 键 (name=value, …)}，值按 {@link #text(Object)} 的文本形式（不变文化），
     * 参数按名字的序数顺序（逐个 UTF-16 码元比较，ADR-0235）排列。
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
     * 把参数值按不变文化转成文本（RFC-0010「值的文本形式」）：字符串原样，整数十进制，布尔写 {@code true} / {@code false}，
     * {@code null} 写空串。浮点写能往返的最短十进制：
     * <ul>
     *   <li>1e-4 ≤ |x| &lt; 1e17 或 x = 0 时不用指数，整数值不带小数点（{@code 2}、{@code 10000000000000000}）</li>
     *   <li>范围之外写有效数字加指数：{@code E}、总写的符号、至少两位的指数，如 {@code 1E+17}、{@code 9.9E-05}、{@code 5E-324}</li>
     *   <li>负零写 {@code -0}；非数与无穷写 {@code NaN}、{@code Infinity}、{@code -Infinity}</li>
     * </ul>
     * 数组与对象写成紧凑的 JSON 文本：对象成员按名字的序数顺序排列，嵌套的 {@code null} 写 {@code null}，数字写法同上，
     * 超过 {@value #MAX_DEPTH} 层的容器写成 {@code null}。
     *
     * @param value 参数值
     * @return 文本
     */
    public static String text(Object value) {
        return text(value, INVARIANT);
    }

    /**
     * 按区域把参数值转成文本：只有浮点的小数点随区域变化，见 {@link #format(String, Map, String)}。
     *
     * @param value 参数值
     * @param locale 区域名，不区分大小写
     * @return 文本
     */
    public static String text(Object value, String locale) {
        return text(value, symbols(locale));
    }

    private static String text(Object value, Symbols symbols) {
        Object model = model(value);
        if (model == null) {
            return "";
        }
        if (model instanceof Double number) {
            return floating(number, symbols.point());
        }
        if (model instanceof Long || model instanceof Boolean || model instanceof String) {
            return model.toString();
        }
        StringBuilder builder = new StringBuilder();
        json(builder, model, 1);
        return builder.toString();
    }

    /**
     * 把值归到值模型：返回 {@code null}、{@link Boolean}、{@link String}、{@link Long}、{@link Double}、{@link Collection} / 数组（数组）或
     * 键都是字符串的 {@link Map}（对象）；模型之外的值换成它的 {@code toString()} 文本。
     */
    private static Object model(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte || value instanceof AtomicInteger
                || value instanceof AtomicLong) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger number) {
            return number.compareTo(LONG_MIN) >= 0 && number.compareTo(LONG_MAX) <= 0 ? number.longValue() : number.doubleValue();
        }
        if (value instanceof Float || value instanceof BigDecimal) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Character || value instanceof CharSequence) {
            return value.toString();
        }
        if (value instanceof Collection<?> || value.getClass().isArray()) {
            return value;
        }
        if (value instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance)) {
            return value;
        }
        return value.toString();
    }

    /**
     * 把模型里的值写成紧凑的 JSON 文本；{@code depth} 是这个值作为容器时所在的层数，最外层是 1。
     */
    private static void json(StringBuilder builder, Object value, int depth) {
        Object model = model(value);
        if (model == null) {
            builder.append("null");
        } else if (model instanceof String text) {
            JsonText.appendQuoted(builder, text);
        } else if (model instanceof Double number) {
            builder.append(floating(number, '.'));
        } else if (model instanceof Long || model instanceof Boolean) {
            builder.append(model);
        } else if (depth > MAX_DEPTH) {
            builder.append("null");
        } else if (model instanceof Map<?, ?> map) {
            // 成员按名字的序数顺序（String.compareTo 即逐个 UTF-16 码元比较）；排序是稳定的
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((left, right) -> ((String) left.getKey()).compareTo((String) right.getKey()));
            builder.append('{');
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                JsonText.appendQuoted(builder, (String) entries.get(index).getKey());
                builder.append(':');
                json(builder, entries.get(index).getValue(), depth + 1);
            }
            builder.append('}');
        } else {
            List<Object> items = elements(model);
            builder.append('[');
            for (int index = 0; index < items.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                json(builder, items.get(index), depth + 1);
            }
            builder.append(']');
        }
    }

    private static List<Object> elements(Object sequence) {
        if (sequence instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        int length = Array.getLength(sequence);
        List<Object> items = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            items.add(Array.get(sequence, index));
        }
        return items;
    }

    private static String floating(double number, char point) {
        if (Double.isNaN(number)) {
            return "NaN";
        }
        if (Double.isInfinite(number)) {
            return number > 0 ? "Infinity" : "-Infinity";
        }
        if (number == 0) {
            return isNegative(number) ? "-0" : "0";
        }
        BigDecimal shortest = shortest(number);
        double magnitude = Math.abs(number);
        if (magnitude >= 1e-4 && magnitude < 1e17) {
            return shortest.toPlainString().replace('.', point);
        }
        // 有效数字 d.ddd 加指数，与 .NET 的往返格式相同：E、总写的符号、至少两位
        String digits = shortest.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - shortest.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + String.valueOf(point) + digits.substring(1);
        String exponentDigits = Integer.toString(Math.abs(exponent));
        return (shortest.signum() < 0 ? "-" : "") + mantissa + "E" + (exponent < 0 ? "-" : "+")
                + (exponentDigits.length() < 2 ? "0" + exponentDigits : exponentDigits);
    }

    /**
     * 求能往返的最短十进制。Java 的 Double.toString（JDK 19 起）是最短往返的，只是至少写两位有效数字（{@code 4.9E-324}），
     * 所以两位以内的结果再试一位：按精确值舍到一位有效数字，读回仍是同一个值就用它（{@code 5E-324}）。
     */
    private static BigDecimal shortest(double number) {
        BigDecimal candidate = new BigDecimal(Double.toString(number)).stripTrailingZeros();
        if (candidate.precision() <= 2) {
            BigDecimal one = new BigDecimal(number).round(new MathContext(1, RoundingMode.HALF_EVEN)).stripTrailingZeros();
            if (one.doubleValue() == number) {
                return one;
            }
        }
        return candidate;
    }

    /**
     * 判断浮点是否为负，含负零：负零与零比较相等，要看符号位。
     */
    private static boolean isNegative(double number) {
        return Double.doubleToRawLongBits(number) < 0;
    }

    private static Optional<String> render(String template, Map<String, ?> arguments, Symbols symbols, boolean strict) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(arguments, "arguments");
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
            builder.append(format == null ? text(value, symbols) : formatted(value, format, symbols).orElseGet(() -> text(value, symbols)));
        }
        return Optional.of(builder.toString());
    }

    /**
     * 按可移植的格式说明符格式化；说明符不认或不适用于这个值时为空，调用方按无格式写出。
     */
    private static Optional<String> formatted(Object value, String format, Symbols symbols) {
        if (!SPECIFIER.matcher(format).matches()) {
            return Optional.empty();
        }
        Object model = model(value);
        boolean integer = model instanceof Long;
        boolean floating = model instanceof Double number && Double.isFinite(number);
        String digits = format.substring(1);
        int width = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        int scale = digits.isEmpty() ? 2 : width;
        // D、F、N 与 .NET 一样不区分大小写；X 与 x 决定十六进制字母的大小写
        return switch (format.charAt(0)) {
            case 'D', 'd' -> integer ? Optional.of(decimalDigits((Long) model, width)) : Optional.empty();
            case 'X' -> integer && (Long) model >= 0 ? Optional.of(pad(Long.toHexString((Long) model).toUpperCase(Locale.ROOT), width)) : Optional.empty();
            case 'x' -> integer && (Long) model >= 0 ? Optional.of(pad(Long.toHexString((Long) model), width)) : Optional.empty();
            case 'F', 'f' -> integer || floating ? Optional.of(fixed(model, scale, symbols, false)) : Optional.empty();
            default -> integer || floating ? Optional.of(fixed(model, scale, symbols, true)) : Optional.empty();
        };
    }

    private static String decimalDigits(long number, int width) {
        String digits = BigInteger.valueOf(number).abs().toString();
        return (number < 0 ? "-" : "") + pad(digits, width);
    }

    /**
     * 定点小数：按精确的十进制展开舍入（浮点按二进制值，2.675 因此是 2.67499…），正好一半时取偶；负数舍入成零时保留负号，负零同样。
     */
    private static String fixed(Object model, int scale, Symbols symbols, boolean grouped) {
        boolean negative;
        BigDecimal exact;
        if (model instanceof Long number) {
            negative = number < 0;
            exact = BigDecimal.valueOf(number);
        } else {
            double number = (Double) model;
            negative = isNegative(number);
            exact = new BigDecimal(number);
        }
        String plain = exact.abs().setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
        int point = plain.indexOf('.');
        String whole = point < 0 ? plain : plain.substring(0, point);
        String fraction = point < 0 ? "" : plain.substring(point + 1);
        StringBuilder builder = new StringBuilder(plain.length() + 8);
        if (negative) {
            builder.append('-');
        }
        for (int index = 0; index < whole.length(); index++) {
            if (grouped && index > 0 && (whole.length() - index) % 3 == 0) {
                builder.append(symbols.group());
            }
            builder.append(whole.charAt(index));
        }
        if (!fraction.isEmpty()) {
            builder.append(symbols.point()).append(fraction);
        }
        return builder.toString();
    }

    private static String pad(String digits, int width) {
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }

    /**
     * 按区域名取数字符号（RFC-0010「模板」的固定表，ADR-0236）；区域名不区分大小写，表外的区域按不变文化。
     */
    private static Symbols symbols(String locale) {
        Objects.requireNonNull(locale, "locale");
        return switch (locale.toLowerCase(Locale.ROOT)) {
            case "de-de", "es-es", "pt-br" -> new Symbols(',', '.');
            case "fr-fr" -> new Symbols(',', ' ');
            case "ru-ru" -> new Symbols(',', ' ');
            default -> INVARIANT;
        };
    }

    /** 区域的小数点与分组符。 */
    private record Symbols(char point, char group) {
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
