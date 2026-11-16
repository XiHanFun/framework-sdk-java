package com.xihanfun.framework.primitives;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * XiHan 读取 JSON 文本时的共同检查（RFC-0001「JSON 文本」，ADR-0236）：Result 与 Error 的线上编码、操作载荷与清单都先过这一遍。
 *
 * <ul>
 *   <li>语法按 RFC 8259：不允许注释、尾逗号、单引号、{@code NaN} / {@code Infinity}、前导零、正号与多个根值；空文本与只有空白也不是 JSON。
 *       文本按 UTF-8 编码，开头的 BOM 是语法错误。</li>
 *   <li>嵌套不超过 {@value #MAX_DEPTH} 层，最外层的对象或数组是第 1 层。</li>
 *   <li>字符串与成员名必须是合法的 Unicode：没有非法的 UTF-8（含过长编码与编码成 UTF-8 的代理项）、没有原始的控制字符、只用 RFC 8259 的转义，
 *       {@code \uD800}–{@code \uDBFF} 后必须紧跟 {@code \uDC00}–{@code \uDFFF}，单独的低代理项也不合法。</li>
 *   <li>任何一层都不能有重复的成员名，名字按转义还原后逐个 UTF-16 码元比较（{@code "a"} 与 {@code "a"} 重复）。</li>
 *   <li>语法错误不论在哪里都优先于重复成员，位置只写 {@code $}；重复成员写第一处重复（第二次出现的成员）的路径。</li>
 * </ul>
 *
 * <p>Jackson 的解析器会跳过 BOM、按字节猜 UTF-16、放过孤立的代理项与过长编码，重复成员取最后一个，严格与否还随使用者的配置变；
 * 所以这里不经 Jackson，逐字节按规范读一遍。检查通过的文本再交给 Jackson 读出值。
 *
 * <p>另带两种规范共用的写法：成员与数组项的路径（RFC-0011 规则 2）与 JSON 字符串（RFC-0010「模板」，清单写出也用它）。
 */
public final class JsonText {
    /** 共同检查允许的最大嵌套层数。 */
    public static final int MAX_DEPTH = 64;

    /** 根的路径。 */
    public static final String ROOT = "$";

    private JsonText() {
    }

    /**
     * 没有通过共同检查的原因。
     *
     * @param syntax 为真表示不是 JSON（RFC 8259 之外的写法、BOM、嵌套过深、非法 UTF-8、孤立的代理项）；否则是重复成员
     * @param path 语法错误时为 {@code $}，重复成员时为第一处重复（第二次出现的成员）的路径
     */
    public record Problem(boolean syntax, String path) {
        /**
         * 校验路径。
         *
         * @param syntax 是否语法错误
         * @param path 路径
         */
        public Problem {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * 检查 UTF-8 文本。
     *
     * @param utf8 原样的字节
     * @return 通过时为空
     */
    public static Optional<Problem> check(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        Reader reader = new Reader(utf8);
        reader.whitespace();
        if (!reader.value(0, ROOT)) {
            return Optional.of(new Problem(true, ROOT));
        }
        reader.whitespace();
        if (reader.at != utf8.length) {
            return Optional.of(new Problem(true, ROOT));
        }
        return reader.duplicate == null ? Optional.empty() : Optional.of(new Problem(false, reader.duplicate));
    }

    /**
     * 检查文本：按 UTF-8 编码后检查；文本里有孤立的代理项（编不成 UTF-8）时是语法错误。
     *
     * @param text 文本
     * @return 通过时为空
     */
    public static Optional<Problem> check(String text) {
        Objects.requireNonNull(text, "text");
        return encode(text).map(JsonText::check).orElseGet(() -> Optional.of(new Problem(true, ROOT)));
    }

    /**
     * 把文本编成 UTF-8；有孤立的代理项时为空（{@link String#getBytes} 会把它换成 {@code ?}，把不合法的文本变成合法的）。
     *
     * @param text 文本
     * @return UTF-8 字节；编不出时为空
     */
    public static Optional<byte[]> encode(String text) {
        Objects.requireNonNull(text, "text");
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return Optional.of(bytes);
        } catch (CharacterCodingException malformed) {
            return Optional.empty();
        }
    }

    /**
     * 写成员的路径（RFC-0011 规则 2，照 System.Text.Json 的实测）：名字含 {@code .}、空格、{@code '}、{@code /}、{@code "}、{@code [}、
     * {@code ]}、{@code (}、{@code )}、{@code \}、制表符、换行、回车、换页、退格、U+0085、U+2028、U+2029 之一时写 {@code ['名字']}，
     * 名字原样、不转义；其余写 {@code .名字}，空名字写成 {@code $.}。
     *
     * @param parent 外层的路径
     * @param name 成员名
     * @return 成员的路径
     */
    public static String memberPath(String parent, String name) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(name, "name");
        for (int index = 0; index < name.length(); index++) {
            if (isBracketed(name.charAt(index))) {
                return parent + "['" + name + "']";
            }
        }
        return parent + "." + name;
    }

    /**
     * 写数组项的路径。
     *
     * @param parent 外层的路径
     * @param index 下标
     * @return 数组项的路径
     */
    public static String indexPath(String parent, int index) {
        Objects.requireNonNull(parent, "parent");
        return parent + "[" + index + "]";
    }

    /**
     * 写 JSON 字符串（RFC-0010「模板」的写法，清单写出也用它）：{@code "} 与 {@code \} 加反斜杠；退格、换页、换行、回车、制表符写
     * {@code \b}、{@code \f}、{@code \n}、{@code \r}、{@code \t}；其余 U+0000–U+001F 与孤立的代理项写成小写十六进制的 {@code \u001f}、
     * {@code \ud800}；别的字符（含 {@code /}、U+007F–U+009F、U+2028 与非 ASCII）原样。
     *
     * @param value 文本
     * @return 带引号的 JSON 字符串
     */
    public static String quote(String value) {
        StringBuilder builder = new StringBuilder(Objects.requireNonNull(value, "value").length() + 2);
        appendQuoted(builder, value);
        return builder.toString();
    }

    /**
     * 把 JSON 字符串写进 {@code builder}，写法见 {@link #quote(String)}。
     *
     * @param builder 目标
     * @param value 文本
     */
    public static void appendQuoted(StringBuilder builder, String value) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(value, "value");
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        unicodeEscape(builder, current);
                    } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        builder.append(current).append(value.charAt(++index));
                    } else if (Character.isSurrogate(current)) {
                        unicodeEscape(builder, current);
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static void unicodeEscape(StringBuilder builder, char unit) {
        String hex = Integer.toHexString(unit);
        builder.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
    }

    private static boolean isBracketed(char current) {
        return switch (current) {
            case '.', ' ', '\'', '/', '"', '[', ']', '(', ')', '\\', '\t', '\n', '\r', '\f', '\b', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }

    /** 按 RFC 8259 逐字节读取的检查器；重复的成员名只记第一处，语法错误要读到末尾才能排除，所以遇到重复也接着读。 */
    private static final class Reader {
        private final byte[] data;
        private int at;
        private String duplicate;

        Reader(byte[] data) {
            this.data = data;
        }

        void whitespace() {
            while (at < data.length) {
                byte current = data[at];
                if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                    at++;
                } else {
                    return;
                }
            }
        }

        /** 读一个值；{@code depth} 是外层容器的层数。 */
        boolean value(int depth, String path) {
            if (at >= data.length) {
                return false;
            }
            byte current = data[at];
            if (current == '{') {
                return object(depth + 1, path);
            }
            if (current == '[') {
                return array(depth + 1, path);
            }
            if (current == '"') {
                return text(null);
            }
            if (current == '-' || (current >= '0' && current <= '9')) {
                return number();
            }
            return literal("true") || literal("false") || literal("null");
        }

        boolean object(int depth, String path) {
            if (depth > MAX_DEPTH) {
                return false;
            }
            at++;
            whitespace();
            if (at < data.length && data[at] == '}') {
                at++;
                return true;
            }
            Set<String> names = new HashSet<>();
            while (true) {
                if (at >= data.length || data[at] != '"') {
                    return false;
                }
                StringBuilder name = new StringBuilder();
                if (!text(name)) {
                    return false;
                }
                String member = memberPath(path, name.toString());
                if (!names.add(name.toString()) && duplicate == null) {
                    duplicate = member;
                }
                whitespace();
                if (at >= data.length || data[at] != ':') {
                    return false;
                }
                at++;
                whitespace();
                if (!value(depth, member)) {
                    return false;
                }
                whitespace();
                if (at >= data.length) {
                    return false;
                }
                if (data[at] == ',') {
                    at++;
                    whitespace();
                } else if (data[at] == '}') {
                    at++;
                    return true;
                } else {
                    return false;
                }
            }
        }

        boolean array(int depth, String path) {
            if (depth > MAX_DEPTH) {
                return false;
            }
            at++;
            whitespace();
            if (at < data.length && data[at] == ']') {
                at++;
                return true;
            }
            for (int index = 0; ; index++) {
                if (!value(depth, indexPath(path, index))) {
                    return false;
                }
                whitespace();
                if (at >= data.length) {
                    return false;
                }
                if (data[at] == ',') {
                    at++;
                    whitespace();
                } else if (data[at] == ']') {
                    at++;
                    return true;
                } else {
                    return false;
                }
            }
        }

        /** 读一个字符串并还原转义（{@code into} 为空时只核对）；非法 UTF-8、原始控制字符、非法转义与孤立的代理项都不合法。 */
        boolean text(StringBuilder into) {
            at++;
            while (at < data.length) {
                int current = data[at] & 0xFF;
                if (current == '"') {
                    at++;
                    return true;
                }
                if (current < 0x20) {
                    return false;
                }
                if (current == '\\') {
                    if (!escape(into)) {
                        return false;
                    }
                } else if (current < 0x80) {
                    if (into != null) {
                        into.append((char) current);
                    }
                    at++;
                } else if (!multibyte(current, into)) {
                    return false;
                }
            }
            return false;
        }

        /** 读一个多字节的 UTF-8 字符：拒绝过长编码、编码成 UTF-8 的代理项、超出 U+10FFFF 与截断的序列。 */
        private boolean multibyte(int lead, StringBuilder into) {
            int length;
            int low = 0x80;
            int high = 0xBF;
            if (lead >= 0xC2 && lead <= 0xDF) {
                length = 2;
            } else if (lead >= 0xE0 && lead <= 0xEF) {
                length = 3;
                if (lead == 0xE0) {
                    low = 0xA0;
                } else if (lead == 0xED) {
                    high = 0x9F;
                }
            } else if (lead >= 0xF0 && lead <= 0xF4) {
                length = 4;
                if (lead == 0xF0) {
                    low = 0x90;
                } else if (lead == 0xF4) {
                    high = 0x8F;
                }
            } else {
                return false;
            }
            if (at + length > data.length) {
                return false;
            }
            int codePoint = lead & (0xFF >> (length + 1));
            for (int offset = 1; offset < length; offset++) {
                int next = data[at + offset] & 0xFF;
                int min = offset == 1 ? low : 0x80;
                int max = offset == 1 ? high : 0xBF;
                if (next < min || next > max) {
                    return false;
                }
                codePoint = (codePoint << 6) | (next & 0x3F);
            }
            if (into != null) {
                into.appendCodePoint(codePoint);
            }
            at += length;
            return true;
        }

        /** 读一个转义序列；高代理项后必须紧跟低代理项的转义。 */
        private boolean escape(StringBuilder into) {
            if (at + 1 >= data.length) {
                return false;
            }
            char decoded;
            switch (data[at + 1]) {
                case '"' -> decoded = '"';
                case '\\' -> decoded = '\\';
                case '/' -> decoded = '/';
                case 'b' -> decoded = '\b';
                case 'f' -> decoded = '\f';
                case 'n' -> decoded = '\n';
                case 'r' -> decoded = '\r';
                case 't' -> decoded = '\t';
                case 'u' -> {
                    int unit = hex4(at + 2);
                    if (unit < 0) {
                        return false;
                    }
                    if (Character.isHighSurrogate((char) unit)) {
                        int lowUnit = hex4(at + 8);
                        if (lowUnit < 0 || data[at + 6] != '\\' || data[at + 7] != 'u' || !Character.isLowSurrogate((char) lowUnit)) {
                            return false;
                        }
                        if (into != null) {
                            into.append((char) unit).append((char) lowUnit);
                        }
                        at += 12;
                        return true;
                    }
                    if (Character.isLowSurrogate((char) unit)) {
                        return false;
                    }
                    if (into != null) {
                        into.append((char) unit);
                    }
                    at += 6;
                    return true;
                }
                default -> {
                    return false;
                }
            }
            if (into != null) {
                into.append(decoded);
            }
            at += 2;
            return true;
        }

        /** 读 {@code from} 起的四位十六进制数；不是时为 -1。 */
        private int hex4(int from) {
            if (from + 4 > data.length) {
                return -1;
            }
            int value = 0;
            for (int index = from; index < from + 4; index++) {
                int digit = hexDigit(data[index]);
                if (digit < 0) {
                    return -1;
                }
                value = (value << 4) | digit;
            }
            return value;
        }

        private static int hexDigit(byte current) {
            if (current >= '0' && current <= '9') {
                return current - '0';
            }
            if (current >= 'a' && current <= 'f') {
                return current - 'a' + 10;
            }
            if (current >= 'A' && current <= 'F') {
                return current - 'A' + 10;
            }
            return -1;
        }

        /** 读一个数：{@code -? (0 | [1-9][0-9]*) (.[0-9]+)? ([eE][+-]?[0-9]+)?}。 */
        boolean number() {
            if (data[at] == '-') {
                at++;
            }
            if (at >= data.length) {
                return false;
            }
            if (data[at] == '0') {
                at++;
            } else if (!digits()) {
                return false;
            }
            if (at < data.length && data[at] == '.') {
                at++;
                if (!digits()) {
                    return false;
                }
            }
            if (at < data.length && (data[at] == 'e' || data[at] == 'E')) {
                at++;
                if (at < data.length && (data[at] == '+' || data[at] == '-')) {
                    at++;
                }
                return digits();
            }
            return true;
        }

        private boolean digits() {
            int start = at;
            while (at < data.length && data[at] >= '0' && data[at] <= '9') {
                at++;
            }
            return at > start;
        }

        private boolean literal(String word) {
            if (at + word.length() > data.length) {
                return false;
            }
            for (int index = 0; index < word.length(); index++) {
                if (data[at + index] != word.charAt(index)) {
                    return false;
                }
            }
            at += word.length();
            return true;
        }
    }
}
