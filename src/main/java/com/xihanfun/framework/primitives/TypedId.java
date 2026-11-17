package com.xihanfun.framework.primitives;

import java.util.Objects;

/**
 * 类型化标识（RFC-0001 TypedId）：{@code Tag} 只用于在编译期区分标识的种类，不承载数据，也不上线。
 *
 * <p>线上编码为底层值的编码（字符串、UUID 字符串、64 位整数等）。Java 擦除泛型，运行期的相等只比较底层值；
 * 区分种类靠编译期的类型参数。
 *
 * @param <Tag> 区分种类的标签类型
 * @param <V> 底层值的类型
 * @param value 底层值，不能为 {@code null}
 */
public record TypedId<Tag, V>(V value) {
    /**
     * 创建类型化标识。
     *
     * @param value 底层值，不能为 {@code null}
     */
    public TypedId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 创建类型化标识。
     *
     * @param value 底层值，不能为 {@code null}
     * @param <Tag> 区分种类的标签类型
     * @param <V> 底层值的类型
     * @return 标识
     */
    public static <Tag, V> TypedId<Tag, V> of(V value) {
        return new TypedId<>(value);
    }

    /**
     * 返回底层值的文本。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return value.toString();
    }
}
