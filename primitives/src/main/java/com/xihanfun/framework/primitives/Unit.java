package com.xihanfun.framework.primitives;

/**
 * 没有值的成功结果的类型参数：{@code Result<Unit>}。
 *
 * <p>线上编码为空对象 {@code {}}，与 Go SDK 的 {@code struct{}} 一致。
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
