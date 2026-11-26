package com.xihanfun.framework.internal;

/**
 * 孤立的代理项的判定（RFC-0001「孤立的代理项」，ADR-0321）：不在一对之中的 U+D800–U+DFFF 码元。
 *
 * <p>单独的高代理项、单独的低代理项、低代理项在前的颠倒的一对、末尾没有低代理项跟着的高代理项都是；高代理项紧跟着低代理项是成对的代理项，
 * 即平面以外的一个字符，不算。Java 的字符串以 UTF-16 码元存放，逐个码元判定，与 .NET 的 {@code UnicodeText.HasLoneSurrogate} 相同。
 */
public final class UnicodeText {
    private UnicodeText() {
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
