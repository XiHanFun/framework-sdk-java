package com.xihanfun.framework.internal;

/**
 * 规范所说的空白（RFC-0001「空白字符」，ADR-0245）：U+0009–U+000D、U+0020、U+0085、U+00A0、U+1680、U+2000–U+200A、U+2028、U+2029、
 * U+202F、U+205F、U+3000，共 25 个码位，等于 Unicode 的 White_Space 属性。
 *
 * <p>{@link com.xihanfun.framework.primitives.Error} 的文案、插件握手行与回调端点（{@code plugin/handshake.md} §2）、
 * 读入插件错误的 {@code text}（{@code plugin/invocation.md} §3）都按它判断。{@link Character#isWhitespace(char)}、{@link String#isBlank()}
 * 与 {@link String#strip()} 不含 U+0085、U+00A0、U+2007、U+202F，却含 U+001C–U+001F，所以逐个列出；
 * 集合里的字符都在基本多文种平面，按 UTF-16 码元判断即可，代理项不会是空白。
 *
 * <p>内部实现，不属于公开 API：所在的包不导出（{@code module-info.java}），为了让模块内的 {@code primitives} 与 {@code plugin} 共用才是
 * {@code public}。
 */
public final class Whitespace {
    private Whitespace() {
    }

    /**
     * 判断字符是不是空白。
     *
     * @param c 字符
     * @return 是 25 个空白码位之一时为真
     */
    public static boolean isWhitespace(char c) {
        return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /**
     * 判断文本是不是空串或只有空白。
     *
     * @param text 文本
     * @return 没有一个空白之外的字符时为真
     */
    public static boolean isBlank(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (!isWhitespace(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断文本里有没有空白。
     *
     * @param text 文本
     * @return 至少含一个空白时为真
     */
    public static boolean containsWhitespace(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (isWhitespace(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
