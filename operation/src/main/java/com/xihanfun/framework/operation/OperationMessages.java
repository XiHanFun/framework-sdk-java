package com.xihanfun.framework.operation;

/**
 * 操作错误的消息键（RFC-0010）；文案在宿主的语言文件里（.NET 内核的 {@code Localization/en-US.json}），宿主在 egress 阶段按调用方语言再翻译。
 */
public final class OperationMessages {
    /** 操作未登记；参数 {@code id}。 */
    public static final String UNKNOWN = "kernel.operation.unknown";

    /** 操作载荷不合法；参数 {@code id} 与 {@code reason}，{@code reason} 只写 JSON 路径，如 {@code $.quantity}。 */
    public static final String INVALID_PAYLOAD = "kernel.operation.invalid_payload";

    /** 类型化调用的类型与登记不符；参数 {@code id}、{@code expected}、{@code actual}，类型写成 {@code 请求 -> 响应}。 */
    public static final String TYPE_MISMATCH = "kernel.operation.type_mismatch";

    /** 管道成功结束却没有执行到操作、没有响应；参数 {@code id}。 */
    public static final String NO_RESPONSE = "kernel.operation.no_response";

    private OperationMessages() {
    }
}
