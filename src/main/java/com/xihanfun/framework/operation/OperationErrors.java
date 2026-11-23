package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.primitives.Error;
import java.util.Map;
import java.util.Objects;

/**
 * 内核登记的操作错误码，以及不止一处要创建的操作错误。
 *
 * <p>调用期的失败以 {@link com.xihanfun.framework.primitives.Result} 返回；登记期的配置错误以
 * {@link com.xihanfun.framework.primitives.FrameworkException} 抛出。调用器、载荷解码与插件运行时的宿主回调都经这里的工厂方法创建
 * 「操作未登记」与「载荷不合法」，码、消息键与参数只在这里拼一次。
 */
public final class OperationErrors {
    /** 操作未登记（调用期）。 */
    public static final String NOT_FOUND = "KERNEL_NOTFOUND_001";

    /** 操作载荷不合法：非 JSON、{@code null} 或类型不匹配（调用期）。 */
    public static final String INVALID_PAYLOAD = "KERNEL_VALIDATION_001";

    /** 类型化调用的请求或响应类型与登记不符（调用期）。 */
    public static final String TYPE_MISMATCH = "KERNEL_VALIDATION_002";

    /** 操作 ID 重复登记（配置期）。 */
    public static final String DUPLICATE = "KERNEL_OPERATION_001";

    /** 操作登记表已满（配置期）。 */
    public static final String REGISTRY_FULL = "KERNEL_OPERATION_002";

    /**
     * 调用期间的未预期失败，如响应编码失败。
     *
     * @deprecated 用 {@link InvocationErrors#UNHANDLED_EXCEPTION}：{@code KERNEL_UNEXPECTED_001} 只在那里定义一次，这里是它的别名。
     */
    @Deprecated
    public static final String UNEXPECTED = InvocationErrors.UNHANDLED_EXCEPTION;

    private OperationErrors() {
    }

    /**
     * 创建「操作未登记」的错误（RFC-0011「错误码」）。
     *
     * @param id 操作 ID，原样写进参数 {@code id}
     * @return {@code KERNEL_NOTFOUND_001}，消息键 {@value OperationMessages#UNKNOWN}，参数 {@code id}
     */
    public static Error notFound(String id) {
        return Error.notFound(NOT_FOUND, OperationMessages.UNKNOWN, Map.of("id", Objects.requireNonNull(id, "id")));
    }

    /**
     * 创建「操作载荷不合法」的错误（RFC-0011 规则 2）。
     *
     * @param id 操作 ID
     * @param reason 出错位置的 JSON 路径，如 {@code $.quantity}；宿主回调的 {@code InvalidArgument} 是状态的消息
     * @return {@code KERNEL_VALIDATION_001}，消息键 {@value OperationMessages#INVALID_PAYLOAD}，参数 {@code id} 与 {@code reason}
     */
    public static Error invalidPayload(String id, String reason) {
        return Error.validation(INVALID_PAYLOAD, OperationMessages.INVALID_PAYLOAD,
                Map.of("id", Objects.requireNonNull(id, "id"), "reason", Objects.requireNonNull(reason, "reason")));
    }
}
