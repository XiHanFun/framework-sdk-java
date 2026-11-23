package com.xihanfun.framework.invocation;

import com.xihanfun.framework.primitives.Error;

/**
 * 内核登记的、与调用上下文有关的错误码与错误。
 */
public final class InvocationErrors {
    /** 调用超过截止时间；截止令牌已触发时调用方同时取消也报它（RFC-0003 规则 1）。 */
    public static final String DEADLINE_EXCEEDED = "KERNEL_TIMEOUT_001";

    /** 调用在截止时间到达之前被调用方取消。 */
    public static final String CANCELLED = "KERNEL_CANCELLED_001";

    /**
     * 调用期间的未预期失败（.NET 的 {@code KernelErrors.Unexpected}）：未处理的异常、响应编码失败，以及管道成功结束却没有响应
     * （{@code kernel.operation.no_response}）。{@code KERNEL_UNEXPECTED_001} 只在这里定义一次。
     */
    public static final String UNHANDLED_EXCEPTION = "KERNEL_UNEXPECTED_001";

    private InvocationErrors() {
    }

    /**
     * 创建未处理异常的错误（RFC-0011 规则 5、10）：没有参数，异常的文本与类型不上线。
     *
     * @return {@code KERNEL_UNEXPECTED_001}，消息键 {@value InvocationMessages#UNHANDLED_EXCEPTION}
     */
    public static Error unhandledException() {
        return Error.unexpected(UNHANDLED_EXCEPTION, InvocationMessages.UNHANDLED_EXCEPTION);
    }

    /**
     * 创建未处理异常的错误，异常是框架异常（带错误码的配置或编程错误）时它的错误作为内部错误。
     *
     * @param inner 框架异常的错误
     * @return {@code KERNEL_UNEXPECTED_001}，消息键 {@value InvocationMessages#UNHANDLED_EXCEPTION}，内部错误 {@code inner}
     */
    public static Error unhandledException(Error inner) {
        return Error.unexpected(UNHANDLED_EXCEPTION, InvocationMessages.UNHANDLED_EXCEPTION, java.util.Map.of(), inner);
    }

    /**
     * 创建超时错误。
     *
     * @return {@code KERNEL_TIMEOUT_001}，消息键 {@value InvocationMessages#DEADLINE_EXCEEDED}
     */
    public static Error deadlineExceeded() {
        return Error.timeout(DEADLINE_EXCEEDED, InvocationMessages.DEADLINE_EXCEEDED);
    }

    /**
     * 创建「开始前已超时」的错误：调用器在 Resilience 阶段的内置步骤里核对截止时间，已到即以它结束，后面的中间件与处理器不执行（RFC-0011 规则 10，ADR-0236）。
     *
     * @return {@code KERNEL_TIMEOUT_001}，消息键 {@value InvocationMessages#DEADLINE_BEFORE_START}
     */
    public static Error deadlineBeforeStart() {
        return Error.timeout(DEADLINE_EXCEEDED, InvocationMessages.DEADLINE_BEFORE_START);
    }

    /**
     * 创建取消错误。
     *
     * @return {@code KERNEL_CANCELLED_001}，消息键 {@value InvocationMessages#CANCELLED}
     */
    public static Error cancelled() {
        return Error.cancelled(CANCELLED, InvocationMessages.CANCELLED);
    }
}
