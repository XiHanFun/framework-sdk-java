package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.Optional;

/**
 * Validate 阶段的内置步骤：解码载荷（RFC-0011 规则 2、10，ADR-0236），与 .NET 的 {@code ValidationMiddleware} 同一位置。
 *
 * <p>原始调用在这里把载荷解码成请求，失败即以 {@code KERNEL_VALIDATION_001} 结束、后面的中间件与处理器不执行；类型化调用跳过。
 * 排在它之前的中间件（Ingress 到 Authorize 阶段）不会让 SDK 解码载荷。
 */
final class ValidationMiddleware implements Middleware {
    @Override
    public Result<Unit> invoke(Context context, Step next) {
        Optional<InvocationScope> found = context.findValue(InvocationScope.KEY);
        if (found.isPresent() && found.get().typedRequest == null) {
            InvocationScope scope = found.get();
            Result<?> decoded = scope.descriptor.decode(scope.request.payloadView());
            if (decoded.isFailed()) {
                return Result.fail(decoded.error().orElseThrow());
            }
            scope.decodedRequest = decoded.value().orElseThrow();
        }
        return next.invoke(context);
    }
}
