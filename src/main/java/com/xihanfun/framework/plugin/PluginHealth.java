package com.xihanfun.framework.plugin;

import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.LifecycleState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 插件自报的健康（{@code plugin.proto} {@code HealthResponse}）。
 *
 * <p>自报的状态只能是 {@code Started}、{@code Ready}、{@code Degraded}、{@code Draining}、{@code Failed}（{@code lifecycle.md} §1）；
 * 运行时以自己的生命周期为准：停止与失败之后不再采用自报的状态，可服务时自报的 {@code Ready}、{@code Degraded}、{@code Failed} 按合法迁移同步。
 *
 * @param state 生命周期状态
 * @param items 健康项
 * @param error {@code Degraded} 或 {@code Failed} 的原因；没有时为空
 */
public record PluginHealth(LifecycleState state, List<HealthItem> items, Optional<Error> error) {
    /**
     * 校验并创建健康，健康项复制成只读列表。
     *
     * @param state 生命周期状态
     * @param items 健康项
     * @param error 原因
     */
    public PluginHealth {
        Objects.requireNonNull(state, "state");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(error, "error");
    }

    /**
     * 创建没有原因的健康。
     *
     * @param state 生命周期状态
     * @param items 健康项
     * @return 健康
     */
    public static PluginHealth of(LifecycleState state, HealthItem... items) {
        return new PluginHealth(state, List.of(items), Optional.empty());
    }

    /**
     * 加上原因。
     *
     * @param value 原因
     * @return 新的健康
     */
    public PluginHealth withError(Error value) {
        return new PluginHealth(state, items, Optional.of(Objects.requireNonNull(value, "value")));
    }
}
