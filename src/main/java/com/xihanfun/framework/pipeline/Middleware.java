package com.xihanfun.framework.pipeline;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.Objects;

/**
 * 一个中间件：可以在进入下游前后做事，也可以不调 {@code next} 而短路。
 *
 * <p>一个实例被所有并发的调用共享，必须线程安全。{@link #name()} 是排序约束与打印用的中间件名，缺省是实现类的全名
 * （与 .NET 的类型全名对应）；lambda 没有稳定的类名，用 {@link #of(String, Middleware)} 给它一个名字。
 */
@FunctionalInterface
public interface Middleware {
    /**
     * 执行中间件。
     *
     * @param context 调用上下文
     * @param next 下游
     * @return 结果，不能为 {@code null}；可恢复失败返回失败的结果，不抛异常
     */
    Result<Unit> invoke(Context context, Step next);

    /**
     * 返回中间件名。
     *
     * @return 缺省是实现类的全名
     */
    default String name() {
        return getClass().getName();
    }

    /**
     * 给一个中间件（通常是 lambda）起名。
     *
     * @param name 中间件名，不能为空串
     * @param body 中间件的实现
     * @return 以 {@code name} 为名的中间件
     */
    static Middleware of(String name, Middleware body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A middleware needs a name.");
        }
        return new NamedMiddleware(name, body);
    }
}

/**
 * 以给定名字包装的中间件。
 */
record NamedMiddleware(String name, Middleware body) implements Middleware {
    @Override
    public Result<Unit> invoke(Context context, Step next) {
        return body.invoke(context, next);
    }

    @Override
    public String toString() {
        return name;
    }
}
