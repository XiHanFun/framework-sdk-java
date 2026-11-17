package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
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
     * 返回 handle 阶段执行成功的响应对象（RFC-0011 规则 3），供 egress 阶段的中间件使用。XGI 调用在管道结束之后才把它编码成载荷
     * （规则 10「编码」，ADR-0236），所以中间件看到的是编码之前的对象，没有载荷。
     *
     * @param context 调用上下文
     * @return 响应对象；处理器还没有执行或没有成功时为空
     */
    public static Optional<Object> findResponse(Context context) {
        return scope(context).flatMap(scope -> {
            if (scope.typedRequest != null) {
                Result<?> result = scope.typedResult;
                return result == null ? Optional.empty() : result.value().map(Object.class::cast);
            }
            return scope.responded ? Optional.of(scope.response) : Optional.empty();
        });
    }

    private static Optional<InvocationScope> scope(Context context) {
        return Objects.requireNonNull(context, "context").findValue(InvocationScope.KEY);
    }
}
