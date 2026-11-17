package com.xihanfun.framework.plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * 一个健康项（{@code plugin.proto} {@code HealthItem}）：名字必须在清单 {@code contributes.health} 里声明，
 * 运行时丢弃未声明的项并记一条告警（{@code lifecycle.md} §4）。
 *
 * @param name 健康项名
 * @param status 状态
 * @param description 说明；没有时为空
 */
public record HealthItem(String name, HealthStatus status, Optional<String> description) {
    /**
     * 校验并创建健康项。
     *
     * @param name 健康项名
     * @param status 状态
     * @param description 说明
     */
    public HealthItem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(description, "description");
    }

    /**
     * 创建没有说明的健康项。
     *
     * @param name 健康项名
     * @param status 状态
     * @return 健康项
     */
    public static HealthItem of(String name, HealthStatus status) {
        return new HealthItem(name, status, Optional.empty());
    }

    /**
     * 加上说明。
     *
     * @param value 说明
     * @return 新的健康项
     */
    public HealthItem withDescription(String value) {
        return new HealthItem(name, status, Optional.of(Objects.requireNonNull(value, "value")));
    }
}
