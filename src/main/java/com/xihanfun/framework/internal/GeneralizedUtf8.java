package com.xihanfun.framework.internal;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * 广义 UTF-8（WTF-8）：编不成 UTF-8 的文本（有孤立的代理项）照样写成字节，供以字符串创建、要在之后才报错的对象保留原文（ADR-0312、ADR-0315）。
 *
 * <p>内部实现，不属于公开 API：所在的包不导出（{@code module-info.java}），为了让 {@code operation} 的请求与 {@code plugin} 的信封共用才是
 * {@code public}。成对的代理项合成一个码位，孤立的代理项与其余码位一样按 UTF-8 的位模式写成三个字节（{@code ED A0 80} 等），
 * 写出的不是合法的 UTF-8；没有孤立的代理项时与严格的 UTF-8 编码相同。
 */
public final class GeneralizedUtf8 {
    private GeneralizedUtf8() {
    }

    /**
     * 把文本写成广义 UTF-8。
     *
     * @param text 文本
     * @return 字节；孤立的代理项各占三个字节
     */
    public static byte[] encode(String text) {
        Objects.requireNonNull(text, "text");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length() * 3);
        // String.codePoints 把成对的代理项合成一个码位，孤立的代理项原样作为一个码位给出
        text.codePoints().forEach(codePoint -> {
            if (codePoint < 0x80) {
                bytes.write(codePoint);
            } else if (codePoint < 0x800) {
                bytes.write(0xC0 | (codePoint >> 6));
                bytes.write(0x80 | (codePoint & 0x3F));
            } else if (codePoint < 0x10000) {
                bytes.write(0xE0 | (codePoint >> 12));
                bytes.write(0x80 | ((codePoint >> 6) & 0x3F));
                bytes.write(0x80 | (codePoint & 0x3F));
            } else {
                bytes.write(0xF0 | (codePoint >> 18));
                bytes.write(0x80 | ((codePoint >> 12) & 0x3F));
                bytes.write(0x80 | ((codePoint >> 6) & 0x3F));
                bytes.write(0x80 | (codePoint & 0x3F));
            }
        });
        return bytes.toByteArray();
    }
}
