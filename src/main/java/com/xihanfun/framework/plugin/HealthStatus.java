package com.xihanfun.framework.plugin;

/**
 * 健康项的状态（{@code plugin.proto} {@code HealthStatus}）；任一项 {@link #DEGRADED} 或 {@link #UNHEALTHY} 时宿主把插件记为
 * {@code Degraded}（{@code lifecycle.md} §1）。
 */
public enum HealthStatus {
    /** 健康。 */
    HEALTHY(1),
    /** 降级，仍可服务。 */
    DEGRADED(2),
    /** 不健康。 */
    UNHEALTHY(3);

    /** 协议里的数值。 */
    private final int number;

    HealthStatus(int number) {
        this.number = number;
    }

    /**
     * 返回协议里的数值。
     *
     * @return 数值
     */
    public int number() {
        return number;
    }
}
