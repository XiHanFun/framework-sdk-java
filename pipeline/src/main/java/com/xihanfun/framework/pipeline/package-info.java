/**
 * 固定阶段的执行管道（RFC-0004）：中间件按十一个阶段贡献，排序确定、可打印、可测试。
 *
 * <p>签名是同步的，按虚拟线程上的并发执行设计：{@link com.xihanfun.framework.pipeline.PipelineBuilder#build(Step)} 构建出的
 * {@link com.xihanfun.framework.pipeline.Step} 不可变，可以被任意多个线程同时调用；中间件实例被所有调用共享，必须线程安全。
 * 配置错误（成环、未知阶段、重复登记、约束目标不合法）抛出带 {@code KERNEL_PIPELINE_00x} 的
 * {@link com.xihanfun.framework.primitives.FrameworkException}。
 */
package com.xihanfun.framework.pipeline;
