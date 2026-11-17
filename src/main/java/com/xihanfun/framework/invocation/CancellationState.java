package com.xihanfun.framework.invocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一组上下文共享的取消状态：截止令牌与外部令牌两个来源，父级取消时连带子级。
 *
 * <p>归因只看令牌（RFC-0004「Resilience 阶段」，ADR-0095）：截止令牌已触发即超时，不论外部令牌是否也已触发；
 * 截止令牌没有触发、外部令牌已触发即取消。两个令牌各自独立地置位，所以先被调用方取消、随后又到了截止时间的调用，
 * 在截止令牌触发之后报超时，与 .NET 的定时取消一致。
 */
final class CancellationState {
    /** 永不取消、没有截止时间：{@link Context#background()} 的状态。 */
    static final CancellationState NEVER = new CancellationState(null, null);

    /** 什么都不做的登记。 */
    private static final Registration NONE = () -> {
    };

    /** 父级状态；只有 {@link #NEVER} 为 {@code null}。 */
    private final CancellationState parent;

    /** 生效的截止时间：自己的与父级的取较早者；没有为 {@code null}。 */
    private final Instant deadline;

    /** 截止令牌：自己的定时器已触发。父级的截止令牌另看父级。 */
    private volatile boolean deadlineFired;

    /** 外部令牌：调用方经 {@link CancellableContext#cancel()} 取消了。 */
    private volatile boolean cancelled;

    /** 保护回调表；回调在锁外执行，回调里再登记或撤销不会死锁。 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 待执行的回调；分派之后为 {@code null}，此后登记的回调立即执行。只含仍在用的登记，撤销即移除。 */
    private LinkedHashSet<Callback> callbacks = new LinkedHashSet<>();

    /** 在父级上的登记：自己先取消时撤销，父级的回调表因此只含仍在用的子级。 */
    private volatile Registration upstream;

    private CancellationState(CancellationState parent, Instant deadline) {
        this.parent = parent;
        this.deadline = deadline;
    }

    /**
     * 派生子级状态：可由调用方取消，可带比父级更早的截止时间。
     */
    static CancellationState child(CancellationState parent, Instant ownDeadline) {
        Instant effective = earlier(parent.deadline, ownDeadline);
        CancellationState state = new CancellationState(parent, effective);
        if (parent != NEVER) {
            state.upstream = parent.onCancelled(state::dispatch);
        }
        // 只有自己的截止时间早于父级时才起定时器：否则父级的截止令牌先触发，经父级连带
        if (ownDeadline != null && (parent.deadline == null || ownDeadline.isBefore(parent.deadline))) {
            Deadlines.schedule(state, ownDeadline);
        }
        return state;
    }

    /**
     * 根状态：只有截止时间，没有父级取消。
     */
    static CancellationState root(Instant deadline) {
        return deadline == null ? NEVER : child(NEVER, deadline);
    }

    Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * 截止令牌是否已触发：自己的或任一祖先的。按循环而不是递归沿父链查找，深的派生链也不会耗尽栈。
     */
    boolean isDeadlineFired() {
        for (CancellationState state = this; state != null; state = state.parent) {
            if (state.deadlineFired) {
                return true;
            }
        }
        return false;
    }

    /**
     * 外部令牌是否已触发：自己的或任一祖先的。
     */
    boolean isCancelledExternally() {
        for (CancellationState state = this; state != null; state = state.parent) {
            if (state.cancelled) {
                return true;
            }
        }
        return false;
    }

    boolean isCancelled() {
        return isDeadlineFired() || isCancelledExternally();
    }

    /**
     * 外部取消：置位外部令牌并执行回调；重复调用没有效果。
     */
    void cancel() {
        cancelled = true;
        dispatch();
    }

    /**
     * 截止时间到达：置位截止令牌并执行回调。由定时器调用，或在创建时截止时间已过而立即调用。
     */
    void fireDeadline() {
        deadlineFired = true;
        dispatch();
    }

    /**
     * 登记取消回调：已经取消时立即在当前线程执行；否则在取消的线程上执行一次。
     */
    Registration onCancelled(Runnable action) {
        if (this == NEVER) {
            return NONE;
        }
        Callback callback = new Callback(action);
        lock.lock();
        try {
            if (callbacks != null) {
                callbacks.add(callback);
                return () -> remove(callback);
            }
        } finally {
            lock.unlock();
        }
        action.run();
        return NONE;
    }

    private void remove(Callback callback) {
        lock.lock();
        try {
            if (callbacks != null) {
                callbacks.remove(callback);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 执行全部回调，只执行一次：先在锁里取走回调表，再在锁外逐个执行。一个回调抛出不影响其余回调，
     * 全部执行完再把第一个异常抛给触发取消的一方，其余的作为被抑制的异常附在它上面。
     */
    private void dispatch() {
        List<Callback> pending;
        lock.lock();
        try {
            if (callbacks == null) {
                return;
            }
            pending = new ArrayList<>(callbacks);
            callbacks = null;
        } finally {
            lock.unlock();
        }
        Registration registration = upstream;
        if (registration != null) {
            registration.close();
        }
        RuntimeException failure = null;
        for (Callback callback : pending) {
            try {
                callback.action().run();
            } catch (RuntimeException thrown) {
                if (failure == null) {
                    failure = thrown;
                } else {
                    failure.addSuppressed(thrown);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Instant earlier(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isBefore(second) ? first : second;
    }

    /** 一个回调；按实例区分，同一个动作登记两次是两个回调。 */
    private record Callback(Runnable action) {
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
