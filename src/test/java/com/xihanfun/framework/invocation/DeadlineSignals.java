package com.xihanfun.framework.invocation;

import java.lang.reflect.Field;

/**
 * 测试辅助：不动墙上时钟、直接触发上下文的截止令牌。
 *
 * <p>截止令牌由定时器按 {@code System.nanoTime()} 触发，墙上时钟（{@code Instant.now()}）与它可能不一致：令牌已触发时时钟
 * 还没到截止时间。测试经它确定地造出这种先后。测试在类路径上运行，与 {@link CancellationState} 同包，可以调它包内可见的
 * {@link CancellationState#fireDeadline()}；上下文的取消状态是私有字段，只能经反射拿到，生产代码不为测试多出成员。
 */
public final class DeadlineSignals {
    private DeadlineSignals() {
    }

    /**
     * 触发上下文自己的截止令牌，并执行登记的取消回调。
     *
     * @param context 上下文
     */
    public static void fire(Context context) {
        try {
            Field field = Context.class.getDeclaredField("cancellation");
            field.setAccessible(true);
            ((CancellationState) field.get(context)).fireDeadline();
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError("Cannot reach the cancellation state of the context.", failed);
        }
    }
}
