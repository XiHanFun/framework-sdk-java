package com.xihanfun.framework.operation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * XGI 请求：操作 ID 与请求类型的 UTF-8 JSON 载荷；空载荷等价于 {@code {}}。
 *
 * <p>不可变：载荷在创建时复制一次，读取时再复制，调用方之后改动自己的数组不影响请求。
 */
public final class OperationRequest {
    private static final byte[] EMPTY = new byte[0];

    private final String id;
    private final byte[] payload;

    private OperationRequest(String id, byte[] payload) {
        this.id = id;
        this.payload = payload;
    }

    /**
     * 创建没有载荷的请求（等价于 {@code {}}）。
     *
     * @param id 操作 ID
     * @return 请求
     */
    public static OperationRequest of(String id) {
        return new OperationRequest(Objects.requireNonNull(id, "id"), EMPTY);
    }

    /**
     * 创建请求。
     *
     * @param id 操作 ID
     * @param payload UTF-8 JSON 载荷；空数组等价于 {@code {}}
     * @return 请求
     */
    public static OperationRequest of(String id, byte[] payload) {
        return new OperationRequest(Objects.requireNonNull(id, "id"), Objects.requireNonNull(payload, "payload").clone());
    }

    /**
     * 以 JSON 文本创建请求。
     *
     * @param id 操作 ID
     * @param json JSON 文本，按 UTF-8 编码
     * @return 请求
     */
    public static OperationRequest of(String id, String json) {
        return new OperationRequest(Objects.requireNonNull(id, "id"), Objects.requireNonNull(json, "json").getBytes(StandardCharsets.UTF_8));
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
     * @return UTF-8 JSON；没有载荷时为空数组
     */
    public byte[] payload() {
        return payload.clone();
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
