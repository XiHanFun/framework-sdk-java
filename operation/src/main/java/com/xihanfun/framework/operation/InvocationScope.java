package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.ContextKey;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;

/**
 * 一次操作调用的状态：描述、请求，以及 handle 阶段写回的响应。
 *
 * <p>随派生的上下文传递（{@link Context#withValue(ContextKey, Object)}），不放进共享的对象袋：同一个上下文上并发或嵌套的调用
 * 各有各的状态，嵌套调用返回后外层中间件看到的仍是外层的操作。
 */
final class InvocationScope {
    /** 上下文里的键。 */
    static final ContextKey<InvocationScope> KEY = ContextKey.named("xihan.operation.invocation");

    final OperationDescriptor descriptor;
    final OperationRequest request;

    /** 类型化调用的请求对象；XGI 调用为 {@code null}。 */
    final Object typedRequest;

    /** handle 阶段写回的响应载荷；终点与中间件可能不在同一线程，所以是 volatile。 */
    volatile byte[] responsePayload;

    /** 类型化调用的结果。 */
    volatile Result<?> typedResult;

    InvocationScope(OperationDescriptor descriptor, OperationRequest request, Object typedRequest) {
        this.descriptor = descriptor;
        this.request = request;
        this.typedRequest = typedRequest;
    }

    /**
     * 管道的终点：在 handle 阶段之后执行当前操作，并把响应写回状态。
     */
    static Result<Unit> dispatch(Context context) {
        InvocationScope scope = context.findValue(KEY).orElse(null);
        if (scope == null) {
            // 中间件换成了不是从调用派生的上下文：没有可执行的操作
            return Result.fail(Error.notFound(OperationErrors.NOT_FOUND, OperationMessages.UNKNOWN));
        }
        if (scope.typedRequest != null) {
            Result<?> result = scope.descriptor.executeTyped(context, scope.typedRequest);
            scope.typedResult = result;
            return result.toUnit();
        }
        OperationResponse response = scope.descriptor.execute(context, scope.request.payloadView());
        if (response.isOk()) {
            scope.responsePayload = response.payloadView();
        }
        return response.outcome();
    }
}
