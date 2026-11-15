package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import java.util.Objects;
import java.util.Optional;

/**
 * 管道里的中间件读取当前操作调用的入口。
 */
public final class Operations {
    private Operations() {
    }

    /**
     * 返回当前调用的操作描述。
     *
     * @param context 调用上下文
     * @return 描述；不在操作调用里时为空
     */
    public static Optional<OperationDescriptor> find(Context context) {
        return scope(context).map(scope -> scope.descriptor);
    }

    /**
     * 返回当前调用的 XGI 请求；类型化调用的请求没有载荷。
     *
     * @param context 调用上下文
     * @return 请求；不在操作调用里时为空
     */
    public static Optional<OperationRequest> findRequest(Context context) {
        return scope(context).map(scope -> scope.request);
    }

    /**
     * 返回 handle 阶段写回的响应载荷：只有 XGI 调用成功之后才有，供 egress 阶段的中间件使用。
     *
     * @param context 调用上下文
     * @return 载荷的副本；还没有时为空
     */
    public static Optional<byte[]> findResponsePayload(Context context) {
        return scope(context).map(scope -> scope.responsePayload).map(byte[]::clone);
    }

    private static Optional<InvocationScope> scope(Context context) {
        return Objects.requireNonNull(context, "context").findValue(InvocationScope.KEY);
    }
}
