package com.xihanfun.framework.operation;

import com.xihanfun.framework.primitives.JsonText;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * XGI 请求：操作 ID 与请求类型的 UTF-8 JSON 载荷；空载荷等价于 {@code {}}。
 *
 * <p>不可变：载荷在创建时复制一次，读取时再复制，调用方之后改动自己的数组不影响请求。
 */
public final class OperationRequest {
    private static final byte[] EMPTY = new byte[0];

    private final String id;
    private final byte[] payload;

    /** 以 JSON 文本创建、文本里有孤立的代理项（编不成 UTF-8）。 */
    private final boolean unencodableText;

    private OperationRequest(String id, byte[] payload, boolean unencodableText) {
        this.id = id;
        this.payload = payload;
        this.unencodableText = unencodableText;
    }

    /**
     * 创建没有载荷的请求（等价于 {@code {}}）。
     *
     * @param id 操作 ID
     * @return 请求
     */
    public static OperationRequest of(String id) {
        return new OperationRequest(Objects.requireNonNull(id, "id"), EMPTY, false);
    }

    /**
     * 创建请求。
     *
     * @param id 操作 ID
     * @param payload UTF-8 JSON 载荷；空数组等价于 {@code {}}
     * @return 请求
     */
    public static OperationRequest of(String id, byte[] payload) {
        return new OperationRequest(Objects.requireNonNull(id, "id"), Objects.requireNonNull(payload, "payload").clone(), false);
    }

    /**
     * 以 JSON 文本创建请求：文本严格编码成 UTF-8（RFC-0001「JSON 文本」的「写出与转交」，ADR-0312）。
     *
     * <p>文本里有孤立的代理项时编不成 UTF-8，不换成别的字符：请求照样建得出，{@link #hasUnencodableText()} 为真，载荷是文本的广义 UTF-8
     * （WTF-8）编码，孤立的代理项各写成三个字节，不是合法的 UTF-8。本地调用器读它与宿主读到不是 JSON 的载荷一样，为
     * {@code KERNEL_VALIDATION_001} + {@value OperationMessages#INVALID_PAYLOAD}（{@code reason} 为 {@code $}）；插件的宿主回调
     * {@code Host.invoke} 不发出这样的请求，在本地以同一个错误失败。上一版用 {@link String#getBytes}，孤立的代理项换成 {@code ?}，
     * 读的一方收到的是另一份合法的 JSON。
     *
     * @param id 操作 ID
     * @param json JSON 文本
     * @return 请求
     */
    public static OperationRequest of(String id, String json) {
        Objects.requireNonNull(id, "id");
        Optional<byte[]> utf8 = JsonText.encode(Objects.requireNonNull(json, "json"));
        if (utf8.isPresent()) {
            return new OperationRequest(id, utf8.get(), false);
        }
        return new OperationRequest(id, generalizedUtf8(json), true);
    }

    /**
     * 广义 UTF-8（WTF-8）：成对的代理项合成一个码位，孤立的代理项与其余码位一样按 UTF-8 的位模式写成三个字节（{@code ED A0 80} 等）。
     */
    private static byte[] generalizedUtf8(String text) {
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

    /**
     * 返回操作 ID。
     *
     * @return 操作 ID
     */
    public String id() {
        return id;
    }

    /**
     * 返回载荷的副本。
     *
     * @return UTF-8 JSON；没有载荷时为空数组；{@link #hasUnencodableText()} 为真时是文本的广义 UTF-8 编码
     */
    public byte[] payload() {
        return payload.clone();
    }

    /**
     * 是否以编不成 UTF-8 的 JSON 文本创建（ADR-0312）：{@link #of(String, String)} 的文本里有孤立的代理项时为真。
     *
     * <p>为真时插件的宿主回调 {@code Host.invoke} 不发出这个请求，在本地以 {@code KERNEL_VALIDATION_001} +
     * {@value OperationMessages#INVALID_PAYLOAD}（{@code reason} 为 {@code $}）失败（{@code invocation.md} §5）。以字节创建的请求总是假：
     * 字节原样，由读它的一方核对。
     *
     * @return 文本编不成 UTF-8 时为真
     */
    public boolean hasUnencodableText() {
        return unencodableText;
    }

    /**
     * 返回载荷本身，不复制；只供本包在解码时读取。
     */
    byte[] payloadView() {
        return payload;
    }

    /**
     * 返回操作 ID 与载荷长度。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return id + " (" + payload.length + " bytes)";
    }
}
