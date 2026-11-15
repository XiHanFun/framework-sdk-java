package com.xihanfun.framework.pipeline;

/**
 * 内核登记的管道错误码；都是配置期错误，以 {@link com.xihanfun.framework.primitives.FrameworkException} 抛出。
 */
public final class PipelineErrors {
    /** 中间件顺序约束成环。 */
    public static final String CYCLE = "KERNEL_PIPELINE_001";

    /** 阶段名未知。 */
    public static final String UNKNOWN_STAGE = "KERNEL_PIPELINE_002";

    /** 中间件重复登记，或顺序约束引用了未登记、跨阶段或有歧义的中间件。 */
    public static final String REGISTRATION = "KERNEL_PIPELINE_003";

    private PipelineErrors() {
    }
}
