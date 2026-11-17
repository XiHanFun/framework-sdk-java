package com.xihanfun.framework.pipeline;

/**
 * 中间件在阶段内的位置：{@link #BEGINNING} 在前。
 */
public enum Placement {
    /** 阶段开头。 */
    BEGINNING,
    /** 阶段末尾，缺省位置。 */
    END
}
