package com.xihanfun.framework.pipeline;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;

/**
 * 管道里的一步：中间件调用它进入下游，管道的终点也是一步。
 *
 * <p>同步签名、在调用线程上执行；按虚拟线程设计，阻塞等待下游不占用平台线程。可恢复失败返回失败的 {@link Result}，不抛异常。
 */
@FunctionalInterface
public interface Step {
    /**
     * 执行这一步及其下游。
     *
     * @param context 调用上下文
     * @return 结果，不能为 {@code null}
     */
    Result<Unit> invoke(Context context);
}
