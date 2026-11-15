package com.xihanfun.framework.invocation;

/**
 * 一次回调登记；关闭即撤销，回调尚未执行时不再执行。
 *
 * <p>可以用 try-with-resources 管理；重复关闭没有效果。
 */
@FunctionalInterface
public interface Registration extends AutoCloseable {
    /**
     * 撤销登记。
     */
    @Override
    void close();
}
