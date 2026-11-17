package com.xihanfun.framework.invocation;

import java.util.Objects;

/**
 * 上下文里的值与对象袋条目的类型化键：按实例区分，同名的两个键是两个键。
 *
 * <p>通常作为 {@code static final} 常量声明在使用它的类里，不同的包互不冲突。
 *
 * @param <T> 值的类型
 */
public final class ContextKey<T> {
    /** 便于调试的名字。 */
    private final String name;

    private ContextKey(String name) {
        this.name = name;
    }

    /**
     * 创建一个新的键。
     *
     * @param name 便于调试的名字，不能为空串
     * @param <T> 值的类型
     * @return 新的键
     */
    public static <T> ContextKey<T> named(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A context key needs a name.");
        }
        return new ContextKey<>(name);
    }

    /**
     * 返回名字。
     *
     * @return 名字
     */
    public String name() {
        return name;
    }

    /**
     * 返回名字。
     *
     * @return 名字
     */
    @Override
    public String toString() {
        return name;
    }
}
