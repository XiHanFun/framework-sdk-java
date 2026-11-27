package com.xihanfun.framework.internal;

/**
 * 孤立的代理项的判定与替换（RFC-0001「孤立的代理项」，ADR-0321、ADR-0339）：不在一对之中的 U+D800–U+DFFF 码元。
 *
 * <p>单独的高代理项、单独的低代理项、低代理项在前的颠倒的一对、末尾没有低代理项跟着的高代理项都是；高代理项紧跟着低代理项是成对的代理项，
 * 即平面以外的一个字符，不算。Java 的字符串以 UTF-16 码元存放，逐个码元判定，与 .NET 的 {@code UnicodeText.HasLoneSurrogate} 相同。
 */
public final class UnicodeText {
    /** 替换字符 U+FFFD。 */
    private static final char REPLACEMENT = (char) 0xFFFD;

    private UnicodeText() {
    }

    /**
     * 把每个孤立的代理项换成一个 U+FFFD，成对的代理项照抄，替换之后长度不变。
     *
     * <p>按 Unicode 的「最大子部分」写出编不成 UTF-8 的自由文本（{@code invocation.md} §3「写出 Error 的文案」，ADR-0339）：以 UTF-16 存放的字符串里
     * 每个孤立的代理项是一段，与 .NET 的 {@code Encoding.UTF8} 和 JavaScript 的 {@code String.prototype.toWellFormed} 相同；
     * {@code String.getBytes(UTF_8)} 与 protobuf-java 换成的是 {@code ?}，所以写出之前先经这里。
     *
     * @param text 文本
     * @return 没有孤立的代理项时是 {@code text} 本身，否则是替换之后的新字符串
     */
    public static String replaceLoneSurrogates(String text) {
        if (!hasLoneSurrogate(text)) {
            return text;
        }
        char[] characters = text.toCharArray();
        for (int index = 0; index < characters.length; index++) {
            char current = characters[index];
            if (Character.isHighSurrogate(current) && index + 1 < characters.length && Character.isLowSurrogate(characters[index + 1])) {
                // 成对的代理项照抄：跳过低代理项
                index++;
            } else if (Character.isSurrogate(current)) {
                characters[index] = REPLACEMENT;
            }
        }
        return new String(characters);
    }

    /**
     * 判断文本里有没有孤立的代理项。
     *
     * @param text 文本
     * @return 有不在一对之中的代理项码元时为真
     */
    public static boolean hasLoneSurrogate(CharSequence text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current) && index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                // 成对的代理项：跳过低代理项
                index++;
            } else if (Character.isSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
