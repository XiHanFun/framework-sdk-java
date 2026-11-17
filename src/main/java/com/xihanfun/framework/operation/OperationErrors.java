package com.xihanfun.framework.operation;

/**
 * 内核登记的操作错误码。
 *
 * <p>调用期的失败以 {@link com.xihanfun.framework.primitives.Result} 返回；登记期的配置错误以
 * {@link com.xihanfun.framework.primitives.FrameworkException} 抛出。
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

    /** 调用期间的未预期失败，如响应编码失败。 */
    public static final String UNEXPECTED = "KERNEL_UNEXPECTED_001";

    private OperationErrors() {
    }
}
