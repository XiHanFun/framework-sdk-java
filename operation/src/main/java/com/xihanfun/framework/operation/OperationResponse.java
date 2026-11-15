package com.xihanfun.framework.operation;

import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * XGI 响应：结果，以及成功时响应类型的 UTF-8 JSON 载荷；失败时没有载荷。
 *
 * <p>不可变：载荷的读取返回副本。
 */
public final class OperationResponse {
    private final Result<Unit> outcome;

    /** 成功时的载荷；失败时为 {@code null}。 */
    private final byte[] payload;

    private OperationResponse(Result<Unit> outcome, byte[] payload) {
        this.outcome = outcome;
        this.payload = payload;
    }

    /**
     * 创建成功的响应。
     *
     * @param payload 响应类型的 UTF-8 JSON
     * @return 响应
     */
    public static OperationResponse success(byte[] payload) {
        return new OperationResponse(Result.success(), Objects.requireNonNull(payload, "payload").clone());
    }

    /**
     * 创建失败的响应。
     *
     * @param error 失败原因
     * @return 响应
     */
    public static OperationResponse failed(Error error) {
        return new OperationResponse(Result.fail(error), null);
    }

    /**
     * 以本包刚编码出的数组创建成功的响应，不复制。
     */
    static OperationResponse owning(byte[] payload) {
        return new OperationResponse(Result.success(), payload);
    }

    /**
     * 返回结果。
     *
     * @return 结果
     */
    public Result<Unit> outcome() {
        return outcome;
    }

    /**
     * 判断是否成功。
     *
     * @return 成功时为真
     */
    public boolean isOk() {
        return outcome.isOk();
    }

    /**
     * 返回载荷的副本。
     *
     * @return UTF-8 JSON；失败时为空
     */
    public Optional<byte[]> payload() {
        return payload == null ? Optional.empty() : Optional.of(payload.clone());
    }

    /**
     * 返回载荷的文本。
     *
     * @return JSON 文本；失败时为空
     */
    public Optional<String> payloadText() {
        return payload == null ? Optional.empty() : Optional.of(new String(payload, StandardCharsets.UTF_8));
    }

    /**
     * 返回载荷本身，不复制；只供本包使用。
     */
    byte[] payloadView() {
        return payload;
    }

    /**
     * 返回结果与载荷的文本。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return payload == null ? outcome.toString() : outcome + " " + new String(payload, StandardCharsets.UTF_8);
    }
}
