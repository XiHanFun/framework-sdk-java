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

    private InvocationErrors() {
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
     * 创建「开始前已超时」的错误：建上下文时截止时间就已经过了。
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
