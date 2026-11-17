package com.xihanfun.framework.invocation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 请求作用域对象袋（RFC-0003 {@code Items}）：不跨进程传播，同一次调用派生出的上下文共享同一个袋，可以并发读写。
 *
 * <p>容量 {@value #CAPACITY} 个条目，超出是编程错误；值不能为 {@code null}。需要只在某个派生分支里可见的值时用
 * {@link Context#withValue(ContextKey, Object)}。
 */
public final class Items {
    /** 一个袋最多的条目数。 */
    public static final int CAPACITY = 1024;

    private final ConcurrentHashMap<ContextKey<?>, Object> values = new ConcurrentHashMap<>();

    /** 只保护「新增键时的容量判定」；读取与替换已有键不取锁。 */
    private final ReentrantLock growth = new ReentrantLock();

    Items() {
    }

    /**
     * 放入一个值，已有同一键时替换。
     *
     * @param key 键
     * @param value 值
     * @param <T> 值的类型
     * @throws IllegalStateException 袋已满且键是新的
     */
    public <T> void put(ContextKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (values.replace(key, value) != null) {
            return;
        }
        // 新增键：容量判定与写入在同一把锁里完成，并发的新增不会一起越过上限
        growth.lock();
        try {
            if (!values.containsKey(key) && values.size() >= CAPACITY) {
                throw new IllegalStateException("The invocation items cannot hold more than " + CAPACITY + " entries.");
            }
            values.put(key, value);
        } finally {
            growth.unlock();
        }
    }

    /**
     * 取一个值。
     *
     * @param key 键
     * @param <T> 值的类型
     * @return 值；没有时为空
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        // 值只能经同一个类型化的键放入，转换是安全的
        return Optional.ofNullable((T) values.get(key));
    }

    /**
     * 移除一个值。
     *
     * @param key 键
     * @param <T> 值的类型
     * @return 被移除的值；没有时为空
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> remove(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable((T) values.remove(key));
    }

    /**
     * 返回条目数。
     *
     * @return 条目数
     */
    public int size() {
        return values.size();
    }
}
