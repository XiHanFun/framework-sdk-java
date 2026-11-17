package com.xihanfun.framework.plugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宿主交给插件、正在处理的调用（操作与事件投递）：停止时据此等在途调用完成（{@code lifecycle.md} §5 第 1 条），
 * 回调时据此判断租户是不是宿主此刻交给插件的（{@code invocation.md} §5，ADR-0195）。
 *
 * <p>线程安全。表的大小不超过在途调用数，调用结束即移除，不随时间增长。
 */
final class Calls {
    /** 计数、停止标记与租户表在同一把锁里改：开始停止之后再到的调用一个也登记不进来。 */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private int active;
    private boolean draining;

    /** 在途调用的租户 → 带着它的在途调用数。 */
    private final Map<String, Integer> tenants = new HashMap<>();

    /** 一次已登记的调用；关闭即结束。 */
    interface Call extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 登记一次调用。
     *
     * @param tenant 宿主随调用交给插件的租户
     * @return 登记；已经开始停止时为空，调用应以 {@code PLUGINS_UNAVAILABLE_001} 结束
     */
    Optional<Call> enter(Optional<String> tenant) {
        lock.lock();
        try {
            if (draining) {
                return Optional.empty();
            }
            active++;
            tenant.ifPresent(name -> tenants.merge(name, 1, Integer::sum));
        } finally {
            lock.unlock();
        }
        return Optional.of(new Call() {
            private boolean closed;

            @Override
            public void close() {
                lock.lock();
                try {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    active--;
                    tenant.ifPresent(name -> tenants.computeIfPresent(name, (key, count) -> count == 1 ? null : count - 1));
                    if (active == 0) {
                        idle.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    /** 开始停止：之后的 {@link #enter(Optional)} 都被拒绝。 */
    void drain() {
        lock.lock();
        try {
            draining = true;
        } finally {
            lock.unlock();
        }
    }

    boolean isDraining() {
        lock.lock();
        try {
            return draining;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 等在途调用全部结束。
     *
     * @return 全部结束时为真，等满时为假
     */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (active > 0 && nanos > 0) {
                nanos = idle.awaitNanos(nanos);
            }
            return active == 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断租户是不是宿主此刻交给插件处理的某个调用的租户。
     */
    boolean handed(String tenant) {
        lock.lock();
        try {
            return tenants.containsKey(tenant);
        } finally {
            lock.unlock();
        }
    }
}
