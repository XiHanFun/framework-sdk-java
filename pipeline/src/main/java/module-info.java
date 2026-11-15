/**
 * 固定阶段的执行管道（RFC-0004）：中间件按阶段贡献，排序确定、可打印、可测试。
 */
module com.xihanfun.framework.pipeline {
    exports com.xihanfun.framework.pipeline;

    requires transitive com.xihanfun.framework.invocation;
}
