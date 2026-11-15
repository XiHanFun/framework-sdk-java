package com.xihanfun.framework.manifest;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 需要宿主注入的资源绑定。
 *
 * <p>构造只收必需字段（名字与资源类型），可选的 {@code required} 经 {@link #withRequired(boolean)} 设置；缺省为必需。
 */
public final class Binding {
    /** 绑定名文法。 */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** 资源类型文法：{@code <类别>[:<子类>]}。 */
    private static final Pattern TYPE = Pattern.compile("^[a-z]+(:[a-z]+)?$");

    private final String name;
    private final String type;

    /** 清单里写明的必需标记；没写为 {@code null}。 */
    private final Boolean required;

    private Binding(String name, String type, Boolean required) {
        this.name = name;
        this.type = type;
        this.required = required;
    }

    /**
     * 创建绑定，没写必需标记（缺省为必需）。
     *
     * @param name 绑定名，{@code ^[a-z][a-z0-9-]*$}
     * @param type 资源类型，如 {@code connection:relational}
     * @return 绑定
     * @throws IllegalArgumentException 名字或类型不合法
     */
    public static Binding of(String name, String type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("The binding name '" + name + "' must match ^[a-z][a-z0-9-]*$.");
        }
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("The binding type '" + type + "' of '" + name + "' must match <category>[:<subtype>].");
        }
        return new Binding(name, type, null);
    }

    /**
     * 返回写明了必需标记的绑定。
     *
     * @param value 是否必需
     * @return 新的绑定
     */
    public Binding withRequired(boolean value) {
        return new Binding(name, type, value);
    }

    /**
     * 返回绑定名。
     *
     * @return 绑定名
     */
    public String name() {
        return name;
    }

    /**
     * 返回资源类型。
     *
     * @return 资源类型
     */
    public String type() {
        return type;
    }

    /**
     * 返回清单里写明的必需标记。
     *
     * @return 标记；没写时为空
     */
    public Optional<Boolean> required() {
        return Optional.ofNullable(required);
    }

    /**
     * 判断绑定是否必需：没写时为必需。
     *
     * @return 必需时为真
     */
    public boolean isRequired() {
        return required == null || required;
    }

    /**
     * 按名字、类型与写明的必需标记比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Binding that && name.equals(that.name) && type.equals(that.type) && Objects.equals(required, that.required);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, type, required);
    }

    /**
     * 返回 {@code 名字 (类型)}。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return name + " (" + type + ")";
    }
}
