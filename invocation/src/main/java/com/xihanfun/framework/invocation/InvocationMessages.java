package com.xihanfun.framework.invocation;

/**
 * 与调用上下文有关的消息键（RFC-0010）；文案在宿主的语言文件里。
 */
public final class InvocationMessages {
    /** 调用超过截止时间：截止时间在调用途中到达。 */
    public static final String DEADLINE_EXCEEDED = "kernel.invocation.deadline_exceeded";

    /** 调用开始时截止时间已过：建上下文时截止时间就已经过了。 */
    public static final String DEADLINE_BEFORE_START = "kernel.invocation.deadline_before_start";

    /** 处理器或中间件抛出了未处理的异常；没有参数。 */
    public static final String UNHANDLED_EXCEPTION = "kernel.invocation.unhandled_exception";

    /** 调用被取消。 */
    public static final String CANCELLED = "kernel.invocation.cancelled";

    private InvocationMessages() {
    }
}
