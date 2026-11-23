package com.xihanfun.framework.operation;

import com.xihanfun.framework.primitives.KernelMessages;

/**
 * 操作错误的消息键（RFC-0010）；文案在宿主的语言文件里（.NET 内核的 {@code Localization/en-US.json}），宿主在 egress 阶段按调用方语言再翻译。
 *
 * <p>键的文本只在 {@link KernelMessages} 写一遍，这里的常量引用它，取值相同。
 */
public final class OperationMessages {
    /** 操作未登记；参数 {@code id}。 */
    public static final String UNKNOWN = KernelMessages.UNKNOWN_OPERATION;

    /** 操作载荷不合法；参数 {@code id} 与 {@code reason}，{@code reason} 只写 JSON 路径，如 {@code $.quantity}。 */
    public static final String INVALID_PAYLOAD = KernelMessages.INVALID_OPERATION_PAYLOAD;

    /** 类型化调用的类型与登记不符；参数只有 {@code id}（ADR-0236）。 */
    public static final String TYPE_MISMATCH = KernelMessages.OPERATION_TYPE_MISMATCH;

    /** 管道成功结束却没有执行到操作、没有响应；参数 {@code id}。 */
    public static final String NO_RESPONSE = KernelMessages.OPERATION_NO_RESPONSE;

    /** 操作 ID 重复登记（配置期）；参数 {@code id} 与已登记者的 {@code owner}。 */
    public static final String DUPLICATE = KernelMessages.DUPLICATE_OPERATION;

    /** 操作登记表已满（配置期）；参数 {@code capacity}。 */
    public static final String REGISTRY_FULL = KernelMessages.OPERATION_REGISTRY_FULL;

    private OperationMessages() {
    }
}
