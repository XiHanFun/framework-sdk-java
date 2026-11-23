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

    /**
     * 终点拿到的上下文不是从这次调用派生的（有中间件把别的上下文交给了下游）时返回的错误。终点只能从上下文里找这次调用，找不到就不知道是
     * 哪个操作，所以这个错误没有参数 {@code id}；它不交给调用方：调用器在管道返回后按实例认出它，换成带 ID 的
     * {@link OperationErrors#notFound(String)}。
     */
    static final Error DETACHED = Error.notFound(OperationErrors.NOT_FOUND, OperationMessages.UNKNOWN);

    final OperationDescriptor descriptor;
    final OperationRequest request;

    /** 类型化调用的请求对象；XGI 调用为 {@code null}。 */
    final Object typedRequest;

    /** Validate 阶段解码出的请求对象；原始调用在这里，类型化调用为 {@code null}。 */
    volatile Object decodedRequest;

    /** 原始调用执行成功时的响应对象，管道结束之后才编码（RFC-0011 规则 10「编码」）；终点与中间件可能不在同一线程，所以是 volatile。 */
    volatile Object response;

    /** 原始调用的处理器是否执行成功、写回了响应。 */
    volatile boolean responded;

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
            // 中间件换成了不是从调用派生的上下文：没有可执行的操作；这里不知道操作 ID，由调用器补上
            return Result.fail(DETACHED);
        }
        if (scope.typedRequest != null) {
            Result<?> result = scope.descriptor.executeTyped(context, scope.typedRequest);
            scope.typedResult = result;
            return result.toUnit();
        }
        Object request = scope.decodedRequest;
        if (request == null) {
            // 有中间件绕过了 Validate 阶段直接调到终点（如换了一份管道）：在这里解码，载荷不合法照样不执行操作
            Result<?> decoded = scope.descriptor.decode(scope.request.payloadView());
            if (decoded.isFailed()) {
                return Result.fail(decoded.error().orElseThrow());
            }
            request = decoded.value().orElseThrow();
        }
        Result<?> result = scope.descriptor.executeDecoded(context, request);
        if (result.isOk()) {
            scope.response = result.value().orElseThrow();
            scope.responded = true;
        }
        return result.toUnit();
    }
}
