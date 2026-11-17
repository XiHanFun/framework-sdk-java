package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.time.Instant;
import java.util.Optional;

/**
 * Resilience 阶段的内置步骤：核对截止时间（RFC-0011 规则 10，ADR-0236），与 .NET 的 {@code DeadlineMiddleware} 同一位置。
 *
 * <p>进入时截止时间已到即以 {@code KERNEL_TIMEOUT_001} + {@code kernel.invocation.deadline_before_start} 结束，后面的中间件与处理器不执行；
 * 进入时不核对取消，构造上下文时也不做这项判断。途中的超时与取消按 RFC-0003 第 1 条归因。
 */
final class DeadlineMiddleware implements Middleware {
    @Override
    public Result<Unit> invoke(Context context, Step next) {
        Optional<Instant> deadline = context.deadline();
        if (deadline.isPresent() && !Instant.now().isBefore(deadline.get())) {
            return Result.fail(InvocationErrors.deadlineBeforeStart());
        }
        return next.invoke(context);
    }
}
