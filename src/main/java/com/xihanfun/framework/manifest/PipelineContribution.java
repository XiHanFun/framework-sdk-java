package com.xihanfun.framework.manifest;

import com.xihanfun.framework.primitives.PipelineStage;
import java.util.List;
import java.util.Objects;

/**
 * 贡献的一个管道中间件（RFC-0004）。
 *
 * <p>构造只收中间件名与阶段，次序约束经 {@link #withBefore(List)} 与 {@link #withAfter(List)} 设置。
 */
public final class PipelineContribution {
    private final String middleware;
    private final PipelineStage stage;
    private final List<String> before;
    private final List<String> after;

    private PipelineContribution(String middleware, PipelineStage stage, List<String> before, List<String> after) {
        this.middleware = middleware;
        this.stage = stage;
        this.before = before;
        this.after = after;
    }

    /**
     * 创建中间件贡献，没有次序约束。
     *
     * @param middleware 中间件名，不能为空串
     * @param stage 阶段
     * @return 贡献
     * @throws IllegalArgumentException 中间件名为空
     */
    public static PipelineContribution of(String middleware, PipelineStage stage) {
        Objects.requireNonNull(middleware, "middleware");
        Objects.requireNonNull(stage, "stage");
        if (middleware.isEmpty()) {
            throw new IllegalArgumentException("A pipeline contribution needs a middleware name.");
        }
        return new PipelineContribution(middleware, stage, List.of(), List.of());
    }

    /**
     * 返回带「在这些中间件之前」约束的贡献。
     *
     * @param names 中间件名
     * @return 新的贡献
     */
    public PipelineContribution withBefore(List<String> names) {
        return new PipelineContribution(middleware, stage, List.copyOf(names), after);
    }

    /**
     * 返回带「在这些中间件之后」约束的贡献。
     *
     * @param names 中间件名
     * @return 新的贡献
     */
    public PipelineContribution withAfter(List<String> names) {
        return new PipelineContribution(middleware, stage, before, List.copyOf(names));
    }

    /**
     * 返回中间件名。
     *
     * @return 中间件名
     */
    public String middleware() {
        return middleware;
    }

    /**
     * 返回阶段。
     *
     * @return 阶段
     */
    public PipelineStage stage() {
        return stage;
    }

    /**
     * 返回「在这些中间件之前」的约束。
     *
     * @return 只读列表；没有时为空
     */
    public List<String> before() {
        return before;
    }

    /**
     * 返回「在这些中间件之后」的约束。
     *
     * @return 只读列表；没有时为空
     */
    public List<String> after() {
        return after;
    }

    /**
     * 按全部字段比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof PipelineContribution that && middleware.equals(that.middleware) && stage == that.stage
                && before.equals(that.before) && after.equals(that.after);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(middleware, stage, before, after);
    }

    /**
     * 返回 {@code 阶段 中间件名}。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return stage.wireName() + " " + middleware;
    }
}
