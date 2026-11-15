package com.xihanfun.framework.pipeline;

import com.xihanfun.framework.primitives.PipelineStage;
import java.util.Objects;
import java.util.Optional;

/**
 * 一个已登记的中间件：所在阶段、中间件名、命名实例名、归属与阶段内位置。
 */
public final class MiddlewareDescriptor {
    private final PipelineStage stage;
    private final String middleware;

    /** 按名字登记的实例名；默认实例为 {@code null}。 */
    private final String instance;

    private final String owner;
    private final Placement placement;

    MiddlewareDescriptor(PipelineStage stage, String middleware, String instance, String owner, Placement placement) {
        this.stage = stage;
        this.middleware = middleware;
        this.instance = instance;
        this.owner = owner;
        this.placement = placement;
    }

    /**
     * 返回所在阶段。
     *
     * @return 阶段
     */
    public PipelineStage stage() {
        return stage;
    }

    /**
     * 返回中间件名（类型名）。
     *
     * @return 中间件名
     */
    public String middleware() {
        return middleware;
    }

    /**
     * 返回按名字登记的实例名。
     *
     * @return 实例名；默认实例为空
     */
    public Optional<String> instance() {
        return Optional.ofNullable(instance);
    }

    /**
     * 返回归属：清单 ID 或 {@value PipelineBuilder#APP_OWNER}。
     *
     * @return 归属
     */
    public String owner() {
        return owner;
    }

    /**
     * 返回阶段内位置。
     *
     * @return 位置
     */
    public Placement placement() {
        return placement;
    }

    /**
     * 返回打印名：中间件名，命名实例为 {@code 中间件名[实例名]}。
     *
     * @return 打印名
     */
    public String display() {
        return instance == null ? middleware : middleware + "[" + instance + "]";
    }

    /**
     * 按全部字段比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof MiddlewareDescriptor that && stage == that.stage && middleware.equals(that.middleware)
                && Objects.equals(instance, that.instance) && owner.equals(that.owner) && placement == that.placement;
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(stage, middleware, instance, owner, placement);
    }

    /**
     * 返回 {@code 阶段 打印名}。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return stage.manifestName() + " " + display();
    }
}
