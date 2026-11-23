package com.xihanfun.framework.invocation;

import com.xihanfun.framework.primitives.KernelMessages;

/**
 * 与调用上下文有关的消息键（RFC-0010）；文案在宿主的语言文件里。
 *
 * <p>键的文本只在 {@link KernelMessages} 写一遍，这里的常量引用它，取值相同。
 */
public final class InvocationMessages {
    /** 调用超过截止时间：截止时间在调用途中到达。 */
    public static final String DEADLINE_EXCEEDED = KernelMessages.DEADLINE_EXCEEDED;

    /** 调用开始时截止时间已过：调用器在 Resilience 阶段的内置步骤里核对。 */
    public static final String DEADLINE_BEFORE_START = KernelMessages.DEADLINE_BEFORE_START;

    /** 处理器或中间件抛出了未处理的异常；没有参数。 */
    public static final String UNHANDLED_EXCEPTION = KernelMessages.UNHANDLED_EXCEPTION;

    /** 调用被取消。 */
    public static final String CANCELLED = KernelMessages.CANCELLED;

    private InvocationMessages() {
    }
}
