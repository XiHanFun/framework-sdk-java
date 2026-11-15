package com.xihanfun.framework.invocation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 截止时间的定时器：一个共享的守护线程只负责到点，回调交给新的虚拟线程执行，慢回调不会拖住其他定时器。
 */
final class Deadlines {
    /** 共享的定时线程；守护线程，不阻止 JVM 退出。 */
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private Deadlines() {
    }

    /**
     * 在截止时间置位状态的截止令牌；截止时间已过则立即置位。
     */
    static void schedule(CancellationState state, Instant deadline) {
        long nanos;
        try {
            nanos = Duration.between(Instant.now(), deadline).toNanos();
        } catch (ArithmeticException tooFar) {
            // 远到纳秒计数表示不了（约 292 年）：调用期间不会到期，不起定时器（与 .NET 超出定时范围时的处理一致）
            return;
        }
        if (nanos <= 0) {
            state.fireDeadlineAtStart();
            return;
        }
        TIMER.schedule(() -> {
            Thread.ofVirtual().name("xihan-invocation-deadline").start(state::fireDeadline);
        }, nanos, TimeUnit.NANOSECONDS);
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "xihan-invocation-timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
