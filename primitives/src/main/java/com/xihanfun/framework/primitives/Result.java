package com.xihanfun.framework.primitives;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 成功或失败（RFC-0001 Result / Result&lt;T&gt;）：成功带值，失败带 {@link Error}，二者互斥。
 *
 * <p>不可变、线程安全。成功值不能为 {@code null}：成功但没有值用 {@code Result<Unit>}（{@link #success()}），
 * 可能没有的值用 {@code Result<Maybe<T>>}。可恢复失败走 {@code Result}，不抛异常。
 *
 * <p>线上编码：{@code {"ok":true,"value":…}} 或 {@code {"ok":false,"error":{…}}}，见
 * {@code com.xihanfun.framework.primitives.json.PrimitivesModule}。
 *
 * @param <T> 成功值的类型
 */
public final class Result<T> {
    /** 成功值；失败时为 {@code null}。 */
    private final T value;

    /** 失败原因；成功时为 {@code null}。 */
    private final Error error;

    private Result(T value, Error error) {
        this.value = value;
        this.error = error;
    }

    /**
     * 创建成功结果。
     *
     * @param value 成功值，不能为 {@code null}
     * @param <T> 值的类型
     * @return 成功结果
     * @throws NullPointerException 值为 {@code null}；没有值的成功用 {@link #success()}
     */
    public static <T> Result<T> ok(T value) {
        return new Result<>(Objects.requireNonNull(value, "A successful result needs a value; use Result.success() when there is none."), null);
    }

    /**
     * 创建没有值的成功结果。
     *
     * @return 值为 {@link Unit#VALUE} 的成功结果
     */
    public static Result<Unit> success() {
        return ok(Unit.VALUE);
    }

    /**
     * 创建失败结果。
     *
     * @param error 失败原因
     * @param <T> 成功值的类型
     * @return 失败结果
     */
    public static <T> Result<T> fail(Error error) {
        return new Result<>(null, Objects.requireNonNull(error, "A failed result needs an error."));
    }

    /**
     * 判断是否成功。
     *
     * @return 成功时为真
     */
    public boolean isOk() {
        return error == null;
    }

    /**
     * 判断是否失败。
     *
     * @return 失败时为真
     */
    public boolean isFailed() {
        return error != null;
    }

    /**
     * 返回成功值。
     *
     * @return 成功值；失败时为空
     */
    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    /**
     * 返回失败原因。
     *
     * @return 失败原因；成功时为空
     */
    public Optional<Error> error() {
        return Optional.ofNullable(error);
    }

    /**
     * 返回成功值，失败时返回备选值。
     *
     * @param fallback 备选值
     * @return 成功值或备选值
     */
    public T orElse(T fallback) {
        return error == null ? value : fallback;
    }

    /**
     * 返回成功值；失败时抛出带同一错误码的 {@link FrameworkException}（.NET 的 {@code ValueOrThrow}）。
     *
     * @return 成功值
     * @throws FrameworkException 结果是失败
     */
    public T orElseThrow() {
        throwIfFailed();
        return value;
    }

    /**
     * 失败时抛出带同一错误码的 {@link FrameworkException}，成功时什么都不做（.NET 的 {@code ThrowIfFailure}）。
     *
     * @throws FrameworkException 结果是失败
     */
    public void throwIfFailed() {
        if (error != null) {
            throw new FrameworkException(error.code(), error.message());
        }
    }

    /**
     * 丢掉值，只保留成功与否。
     *
     * @return 没有值的结果
     */
    public Result<Unit> toUnit() {
        return error == null ? success() : fail(error);
    }

    /**
     * 把成功值变换成另一种类型；失败原样传递。
     *
     * @param mapper 变换，结果不能为 {@code null}
     * @param <U> 新值的类型
     * @return 新的结果
     */
    public <U> Result<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return error == null ? ok(mapper.apply(value)) : fail(error);
    }

    /**
     * 把成功值交给返回 {@code Result} 的函数（.NET 的 {@code Bind}）；失败原样传递。
     *
     * @param binder 下一步，结果不能为 {@code null}
     * @param <U> 新值的类型
     * @return 下一步的结果
     */
    public <U> Result<U> flatMap(Function<? super T, Result<U>> binder) {
        Objects.requireNonNull(binder, "binder");
        return error == null ? Objects.requireNonNull(binder.apply(value), "The binder returned no result.") : fail(error);
    }

    /**
     * 按成功或失败分别求值。
     *
     * @param onOk 成功时的求值
     * @param onFailed 失败时的求值
     * @param <R> 求值的类型
     * @return 求值结果
     */
    public <R> R match(Function<? super T, ? extends R> onOk, Function<? super Error, ? extends R> onFailed) {
        Objects.requireNonNull(onOk, "onOk");
        Objects.requireNonNull(onFailed, "onFailed");
        return error == null ? onOk.apply(value) : onFailed.apply(error);
    }

    /**
     * 成功值相等或失败原因相等时相等。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Result<?> that && Objects.equals(value, that.value) && Objects.equals(error, that.error);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(value, error);
    }

    /**
     * 返回 {@code Ok(值)} 或 {@code Fail(码: 消息)}，便于日志。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return error == null ? "Ok(" + value + ")" : "Fail(" + error + ")";
    }
}
