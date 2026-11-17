package com.xihanfun.framework.primitives;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 九态生命周期（RFC-0001）。
 *
 * <p>{@link #number()} 与 .NET 枚举和插件协议的 {@code LifecycleState} 一致；{@link #stateName()} 是向量与线上编码用的名字。
 * 合法迁移表是规范性内容，完整列表在 {@code spec/conformance/vectors/primitives/lifecycle-transitions.json}。
 */
public enum LifecycleState {
    /** 已声明：清单已读取。 */
    DECLARED("Declared"),
    /** 已解析：依赖已求解。 */
    RESOLVED("Resolved"),
    /** 已配置。 */
    CONFIGURED("Configured"),
    /** 已启动，尚未就绪。 */
    STARTED("Started"),
    /** 就绪，可服务。 */
    READY("Ready"),
    /** 降级，仍可服务。 */
    DEGRADED("Degraded"),
    /** 排空：不再接收新调用。 */
    DRAINING("Draining"),
    /** 已停止：终态。 */
    STOPPED("Stopped"),
    /** 失败，可以重新解析。 */
    FAILED("Failed");

    /**
     * 合法迁移的完整列表；未列出的组合一律非法。
     *
     * <p>枚举常量的构造里不能引用尚未创建的常量，所以表在全部常量创建之后的静态初始化里建立
     */
    private static final Map<LifecycleState, Set<LifecycleState>> TRANSITIONS;

    static {
        EnumMap<LifecycleState, Set<LifecycleState>> transitions = new EnumMap<>(LifecycleState.class);
        transitions.put(DECLARED, EnumSet.of(RESOLVED, FAILED));
        transitions.put(RESOLVED, EnumSet.of(CONFIGURED, FAILED));
        transitions.put(CONFIGURED, EnumSet.of(STARTED, FAILED));
        transitions.put(STARTED, EnumSet.of(READY, DEGRADED, DRAINING, FAILED));
        transitions.put(READY, EnumSet.of(DEGRADED, DRAINING, FAILED));
        transitions.put(DEGRADED, EnumSet.of(READY, DRAINING, FAILED));
        transitions.put(DRAINING, EnumSet.of(STOPPED, FAILED));
        transitions.put(STOPPED, EnumSet.noneOf(LifecycleState.class));
        transitions.put(FAILED, EnumSet.of(RESOLVED));
        transitions.replaceAll((state, targets) -> Collections.unmodifiableSet(targets));
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /** 向量与线上编码用的名字。 */
    private final String stateName;

    LifecycleState(String stateName) {
        this.stateName = stateName;
    }

    /**
     * 按名字解析状态，区分大小写。
     *
     * @param name 状态名，如 {@code Ready}
     * @return 状态；名字未知时为空
     */
    public static Optional<LifecycleState> parse(String name) {
        for (LifecycleState state : values()) {
            if (state.stateName.equals(name)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    /**
     * 按协议数值取状态。
     *
     * @param number 数值，{@code 0} 到 {@code 8}
     * @return 状态；数值未知时为空
     */
    public static Optional<LifecycleState> fromNumber(int number) {
        LifecycleState[] states = values();
        return number >= 0 && number < states.length ? Optional.of(states[number]) : Optional.empty();
    }

    /**
     * 返回向量与线上编码用的名字。
     *
     * @return 名字，如 {@code Ready}
     */
    public String stateName() {
        return stateName;
    }

    /**
     * 返回与插件协议一致的数值。
     *
     * @return 数值
     */
    public int number() {
        return ordinal();
    }

    /**
     * 判断迁移是否合法。
     *
     * @param to 目标状态
     * @return 合法时为真
     */
    public boolean canTransitionTo(LifecycleState to) {
        return TRANSITIONS.get(this).contains(to);
    }

    /**
     * 返回从本状态出发的合法目标。
     *
     * @return 只读集合
     */
    public Set<LifecycleState> transitions() {
        return TRANSITIONS.get(this);
    }

    /**
     * 判断是否可服务：{@link #READY} 或 {@link #DEGRADED}。
     *
     * @return 可服务时为真
     */
    public boolean isServing() {
        return this == READY || this == DEGRADED;
    }

    /**
     * 判断是否终态：{@link #STOPPED}。
     *
     * @return 终态时为真
     */
    public boolean isTerminal() {
        return this == STOPPED;
    }
}
