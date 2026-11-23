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
     * 返回当前调用的 XGI 请求；类型化调用的请求没有载荷。解码好的请求对象见 {@link #findDecodedRequest(Context)}。
     *
     * @param context 调用上下文
     * @return 请求；不在操作调用里时为空
     */
    public static Optional<OperationRequest> findRequest(Context context) {
        return scope(context).map(scope -> scope.request);
    }

    /**
     * 返回解码好的请求对象（RFC-0011 规则 3），供 Validate 阶段之后的中间件使用：原始调用在 Validate 阶段的内置步骤解码之后才有，
     * 排在它之前的中间件拿到的是空；类型化调用是传入的请求对象，一开始就有。
     *
     * @param context 调用上下文
     * @return 请求对象；不在操作调用里，或原始调用还没有解码时为空
     */
    public static Optional<Object> findDecodedRequest(Context context) {
        // 两个都没有时 map 给出空：类型化调用只有 typedRequest，原始调用解码之前两个都是 null
        return scope(context).map(scope -> scope.typedRequest != null ? scope.typedRequest : scope.decodedRequest);
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
