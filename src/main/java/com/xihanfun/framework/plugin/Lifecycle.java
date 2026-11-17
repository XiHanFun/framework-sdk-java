package com.xihanfun.framework.plugin;

import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.LifecycleState;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 插件这一侧的生命周期状态机（{@code lifecycle.md} §1、§7）：迁移按原语层的合法迁移表核对，非法的迁移不发生；
 * 每次迁移唤醒等待变化的健康推送。
 */
final class Lifecycle {
    /** 状态、原因与两个计数在同一把锁里读写；用 ReentrantLock，等待变化的虚拟线程不钉住载体线程。 */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private LifecycleState state = LifecycleState.DECLARED;
    private Error error;

    /** 每次迁移或 {@link #touch()} 加一，等待方据此判断错过了没有。 */
    private long version;

    /** 只在真正迁移时加一：健康的快照据此判断上一次求得的结果还是不是当前状态的。 */
    private long transitions;

    LifecycleState state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    Optional<Error> error() {
        lock.lock();
        try {
            return Optional.ofNullable(error);
        } finally {
            lock.unlock();
        }
    }

    boolean is(LifecycleState... candidates) {
        LifecycleState current = state();
        for (LifecycleState candidate : candidates) {
            if (current == candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * 迁移到新状态并记下原因；迁移不合法（含目标就是当前状态）时什么都不变。
     *
     * @return 迁移了时为真
     */
    boolean transition(LifecycleState to, Error reason) {
        lock.lock();
        try {
            if (!state.canTransitionTo(to)) {
                return false;
            }
            state = to;
            error = reason;
            version++;
            transitions++;
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 不改状态，只唤醒等待方：停止应答之后让健康推送结束。 */
    void touch() {
        lock.lock();
        try {
            version++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    long version() {
        lock.lock();
        try {
            return version;
        } finally {
            lock.unlock();
        }
    }

    long transitions() {
        lock.lock();
        try {
            return transitions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 等到版本号不再是 {@code seen} 或等满 {@code timeout}。
     *
     * @return 当前版本号
     */
    long awaitChange(long seen, Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (version == seen && nanos > 0) {
                nanos = changed.awaitNanos(nanos);
            }
            return version;
        } finally {
            lock.unlock();
        }
    }

    /** 只在测试与诊断里用：等到某个状态或超时。 */
    boolean awaitState(LifecycleState target, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        lock.lock();
        try {
            while (state != target) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                changed.await(remaining, TimeUnit.NANOSECONDS);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
