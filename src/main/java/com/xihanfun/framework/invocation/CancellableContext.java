package com.xihanfun.framework.invocation;

/**
 * 可以由调用方取消的上下文：{@link Context#withCancellation()} 等派生方法的结果。
 *
 * <p>用完必须取消：取消把这个上下文从父级的回调表里撤下，免得长寿命的父级积攒已经结束的子级。
 * 推荐 try-with-resources，{@link #close()} 等同于 {@link #cancel()}。
 *
 * <pre>{@code
 * try (CancellableContext scope = ctx.withTimeout(Duration.ofSeconds(2))) {
 *     Result<Unit> outcome = step.invoke(scope.context());
 * }
 * }</pre>
 */
public final class CancellableContext implements AutoCloseable {
    private final Context context;

    /** 这个上下文自己的取消状态；派生自它的上下文共享它。 */
    private final CancellationState state;

    CancellableContext(Context context, CancellationState state) {
        this.context = context;
        this.state = state;
    }

    /**
     * 返回上下文。
     *
     * @return 上下文
     */
    public Context context() {
        return context;
    }

    /**
     * 取消：触发外部令牌，在当前线程执行已登记的回调；重复取消没有效果。
     *
     * <p>截止令牌不受影响：之后截止时间到达，归因变为超时（RFC-0004）。
     */
    public void cancel() {
        state.cancel();
    }

    /**
     * 等同于 {@link #cancel()}。
     */
    @Override
    public void close() {
        cancel();
    }
}
