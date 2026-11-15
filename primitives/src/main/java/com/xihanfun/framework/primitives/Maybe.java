package com.xihanfun.framework.primitives;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 可能没有的值（RFC-0001 Maybe&lt;T&gt;）：有值或无值，类型层面不可为 {@code null}。
 *
 * <p>不可变、线程安全。与 {@link Optional} 的分工：本 SDK 的访问器返回 {@code Optional}；需要线上编码的数据字段
 * （请求、响应、事件的成员）用 {@code Maybe}，它的编码是值本身或 {@code null}。两者可经 {@link #toOptional()} 与
 * {@link #fromOptional(Optional)} 互转。
 *
 * @param <T> 值的类型
 */
public final class Maybe<T> {
    /** 共享的无值实例。 */
    private static final Maybe<?> NONE = new Maybe<>(null);

    /** 值；无值时为 {@code null}。 */
    private final T value;

    private Maybe(T value) {
        this.value = value;
    }

    /**
     * 创建有值的 Maybe。
     *
     * @param value 值，不能为 {@code null}
     * @param <T> 值的类型
     * @return 有值的 Maybe
     */
    public static <T> Maybe<T> some(T value) {
        return new Maybe<>(Objects.requireNonNull(value, "value"));
    }

    /**
     * 返回无值的 Maybe。
     *
     * @param <T> 值的类型
     * @return 无值的 Maybe
     */
    @SuppressWarnings("unchecked")
    public static <T> Maybe<T> none() {
        // 无值实例不持有任何 T，按任意类型参数共享是安全的
        return (Maybe<T>) NONE;
    }

    /**
     * 把可能为 {@code null} 的引用转成 Maybe：{@code null} 为无值。
     *
     * @param value 值；可以为 {@code null}
     * @param <T> 值的类型
     * @return Maybe
     */
    public static <T> Maybe<T> ofNullable(T value) {
        return value == null ? none() : new Maybe<>(value);
    }

    /**
     * 把 {@link Optional} 转成 Maybe。
     *
     * @param optional Optional
     * @param <T> 值的类型
     * @return Maybe
     */
    public static <T> Maybe<T> fromOptional(Optional<T> optional) {
        return ofNullable(Objects.requireNonNull(optional, "optional").orElse(null));
    }

    /**
     * 判断是否有值。
     *
     * @return 有值时为真
     */
    public boolean hasValue() {
        return value != null;
    }

    /**
     * 转成 {@link Optional}。
     *
     * @return Optional；无值时为空
     */
    public Optional<T> toOptional() {
        return Optional.ofNullable(value);
    }

    /**
     * 返回值，无值时返回备选值。
     *
     * @param fallback 备选值
     * @return 值或备选值
     */
    public T orElse(T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * 返回值，无值时调用备选的提供者。
     *
     * @param fallback 备选值的提供者
     * @return 值或备选值
     */
    public T orElseGet(Supplier<? extends T> fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return value != null ? value : fallback.get();
    }

    /**
     * 返回值。
     *
     * @return 值
     * @throws NoSuchElementException 无值
     */
    public T orElseThrow() {
        if (value == null) {
            throw new NoSuchElementException("The maybe has no value.");
        }
        return value;
    }

    /**
     * 变换有值的 Maybe；无值原样传递。
     *
     * @param mapper 变换，结果不能为 {@code null}
     * @param <U> 新值的类型
     * @return 新的 Maybe
     */
    public <U> Maybe<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return value == null ? none() : some(mapper.apply(value));
    }

    /**
     * 把值交给返回 Maybe 的函数（.NET 的 {@code Bind}）；无值原样传递。
     *
     * @param binder 下一步
     * @param <U> 新值的类型
     * @return 下一步的 Maybe
     */
    public <U> Maybe<U> flatMap(Function<? super T, Maybe<U>> binder) {
        Objects.requireNonNull(binder, "binder");
        return value == null ? none() : Objects.requireNonNull(binder.apply(value), "The binder returned no maybe.");
    }

    /**
     * 按有值或无值分别求值。
     *
     * @param onSome 有值时的求值
     * @param onNone 无值时的求值
     * @param <R> 求值的类型
     * @return 求值结果
     */
    public <R> R match(Function<? super T, ? extends R> onSome, Supplier<? extends R> onNone) {
        Objects.requireNonNull(onSome, "onSome");
        Objects.requireNonNull(onNone, "onNone");
        return value != null ? onSome.apply(value) : onNone.get();
    }

    /**
     * 把无值转成给定错误的失败。
     *
     * @param error 无值时的失败原因
     * @return 有值时成功，无值时失败
     */
    public Result<T> toResult(Error error) {
        Objects.requireNonNull(error, "error");
        return value != null ? Result.ok(value) : Result.fail(error);
    }

    /**
     * 值相等或都无值时相等。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Maybe<?> that && Objects.equals(value, that.value);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    /**
     * 返回 {@code Some(值)} 或 {@code None}。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return value != null ? "Some(" + value + ")" : "None";
    }
}
