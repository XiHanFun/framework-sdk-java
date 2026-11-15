package com.xihanfun.framework.invocation;

/**
 * 与调用上下文有关的消息键（RFC-0010）；文案在宿主的语言文件里。
 */
public final class InvocationMessages {
    /** 调用超过截止时间。 */
    public static final String DEADLINE_EXCEEDED = "kernel.invocation.deadline_exceeded";

    /** 调用被取消。 */
    public static final String CANCELLED = "kernel.invocation.cancelled";

    private InvocationMessages() {
    }
}
