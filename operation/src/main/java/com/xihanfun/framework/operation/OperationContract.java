package com.xihanfun.framework.operation;

import com.xihanfun.framework.primitives.Stability;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 操作契约（RFC-0011 OperationContract）：操作 ID、稳定性、暴露的协议与授权要求。
 *
 * <p>构造只收操作 ID，其余经 {@code with…} 设置：稳定性缺省 {@link Stability#STABLE}；暴露的协议缺省为空，即不对外发布
 * （与插件协议「空表示不对外发布」一致，要发布须显式写明）；授权要求的权限码与策略名二选一，冲突在设置时即拒绝。
 */
public final class OperationContract {
    private final String id;
    private final Stability stability;
    private final Set<Expose> expose;

    /** 权限码；没有为 {@code null}。 */
    private final String permission;

    /** 策略名；没有为 {@code null}。 */
    private final String policy;

    private OperationContract(String id, Stability stability, Set<Expose> expose, String permission, String policy) {
        this.id = id;
        this.stability = stability;
        this.expose = expose;
        this.permission = permission;
        this.policy = policy;
    }

    /**
     * 创建契约：稳定、不对外发布、没有授权要求。
     *
     * @param id 操作 ID，{@code <resource>.<verb>}
     * @return 契约
     * @throws IllegalArgumentException 操作 ID 不合文法
     */
    public static OperationContract of(String id) {
        return new OperationContract(OperationId.validate(id), Stability.STABLE, Set.of(), null, null);
    }

    /**
     * 返回换了稳定性的契约。
     *
     * @param value 稳定性
     * @return 新的契约
     */
    public OperationContract withStability(Stability value) {
        return new OperationContract(id, Objects.requireNonNull(value, "value"), expose, permission, policy);
    }

    /**
     * 返回换了暴露协议的契约。
     *
     * @param values 协议；空表示不对外发布
     * @return 新的契约
     */
    public OperationContract withExpose(Set<Expose> values) {
        Objects.requireNonNull(values, "values");
        Set<Expose> copy = values.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(values));
        return new OperationContract(id, stability, copy, permission, policy);
    }

    /**
     * 返回换了暴露协议的契约。
     *
     * @param values 协议
     * @return 新的契约
     */
    public OperationContract withExpose(Expose... values) {
        return withExpose(Set.of(values));
    }

    /**
     * 返回要求权限码的契约。
     *
     * @param value 权限码，如 {@code orders.write}
     * @return 新的契约
     * @throws IllegalArgumentException 已声明策略，或权限码为空串
     */
    public OperationContract withPermission(String value) {
        requireNonEmpty(value, "permission");
        if (policy != null) {
            throw exclusive();
        }
        return new OperationContract(id, stability, expose, value, null);
    }

    /**
     * 返回要求策略的契约。
     *
     * @param value 策略名
     * @return 新的契约
     * @throws IllegalArgumentException 已声明权限码，或策略名为空串
     */
    public OperationContract withPolicy(String value) {
        requireNonEmpty(value, "policy");
        if (permission != null) {
            throw exclusive();
        }
        return new OperationContract(id, stability, expose, null, value);
    }

    /**
     * 返回操作 ID。
     *
     * @return 操作 ID
     */
    public String id() {
        return id;
    }

    /**
     * 返回稳定性。
     *
     * @return 稳定性
     */
    public Stability stability() {
        return stability;
    }

    /**
     * 返回暴露的协议。
     *
     * @return 只读集合，按 {@link Expose} 的枚举顺序；空表示不对外发布
     */
    public Set<Expose> expose() {
        return expose;
    }

    /**
     * 返回要求的权限码。
     *
     * @return 权限码；没有时为空
     */
    public Optional<String> permission() {
        return Optional.ofNullable(permission);
    }

    /**
     * 返回要求的策略名。
     *
     * @return 策略名；没有时为空
     */
    public Optional<String> policy() {
        return Optional.ofNullable(policy);
    }

    /**
     * 按全部字段比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof OperationContract that && id.equals(that.id) && stability == that.stability && expose.equals(that.expose)
                && Objects.equals(permission, that.permission) && Objects.equals(policy, that.policy);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, stability, expose, permission, policy);
    }

    /**
     * 返回操作 ID。
     *
     * @return 操作 ID
     */
    @Override
    public String toString() {
        return id;
    }

    private IllegalArgumentException exclusive() {
        return new IllegalArgumentException("The operation '" + id + "' must declare either a permission or a policy.");
    }

    private void requireNonEmpty(String value, String what) {
        Objects.requireNonNull(value, what);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The " + what + " of the operation '" + id + "' must not be empty.");
        }
    }
}
