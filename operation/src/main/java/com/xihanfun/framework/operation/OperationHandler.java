package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;

/**
 * 处理一个操作（RFC-0011 {@code IOperation<TRequest, TResponse>}）。
 *
 * <p>可恢复失败返回失败的 {@link Result}，不抛异常；成功值不能为 {@code null}。一个处理器实例被所有并发的调用共享，必须线程安全；
 * 同步签名，在调用线程上执行，在虚拟线程上阻塞等待 I/O 不占用平台线程。应检查 {@link Context#isCancelled()}，
 * 取消后尽早返回 {@link Context#cancellation()} 的错误。
 *
 * @param <Req> 请求类型
 * @param <Res> 响应类型
 */
@FunctionalInterface
public interface OperationHandler<Req, Res> {
    /**
     * 处理请求。
     *
     * @param context 调用上下文
     * @param request 请求
     * @return 结果，不能为 {@code null}
     */
    Result<Res> handle(Context context, Req request);
}
