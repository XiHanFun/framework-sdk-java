package com.xihanfun.framework.primitives;

/**
 * 没有值的成功结果的类型参数：{@code Result<Unit>}。
 *
 * <p>作为载荷时编码为空对象 {@code {}}；{@code Result<Unit>} 的无值成功只写 {@code {"ok":true}}（RFC-0001「Result」）。
 */
public enum Unit {
    /** 唯一的值。 */
    VALUE;

    /**
     * 返回 {@code ()}。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "()";
    }
}
