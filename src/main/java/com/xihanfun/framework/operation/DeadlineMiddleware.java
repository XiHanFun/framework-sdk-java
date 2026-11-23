package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.time.Instant;
import java.util.Optional;

/**
 * Resilience 阶段的内置步骤：核对截止时间（RFC-0011 规则 10，ADR-0236），与 .NET 的 {@code DeadlineMiddleware} 同一位置。
 *
 * <p>进入时截止时间已到即以 {@code KERNEL_TIMEOUT_001} + {@code kernel.invocation.deadline_before_start} 结束，后面的中间件与处理器不执行；
 * 进入时不核对取消，构造上下文时也不做这项判断。途中的超时与取消按 RFC-0003 第 1 条归因：下游把取消写成了结果（{@code KERNEL_CANCELLED_001}）
 * 而截止令牌已触发时，改报 {@code KERNEL_TIMEOUT_001} + {@code kernel.invocation.deadline_exceeded}，调用方同时取消也一样。
 * 抛出路径由 Observe 阶段的内置步骤按同一规则归因（{@link Context#cancellationError()} 先看截止令牌）。
 */
final class DeadlineMiddleware implements Middleware {
    @Override
    public Result<Unit> invoke(Context context, Step next) {
        Optional<Instant> deadline = context.deadline();
        if (deadline.isPresent() && !Instant.now().isBefore(deadline.get())) {
            return Result.fail(InvocationErrors.deadlineBeforeStart());
        }
        Result<Unit> outcome = next.invoke(context);
        // 下游把取消接住并写成了结果：它只知道取消发生了，不知道是哪个令牌触发的；下游若按时钟判断是否已过截止时间，
        // 会与定时器差几毫秒而把超时报成取消。这里看令牌：截止令牌已触发即超时，外部令牌是否也已触发不论（与抛出路径同一规则，ADR-0092）
        if (outcome != null && outcome.error().map(Error::code).filter(InvocationErrors.CANCELLED::equals).isPresent() && deadlineFired(context)) {
            return Result.fail(InvocationErrors.deadlineExceeded());
        }
        return outcome;
    }

    /** 截止令牌已触发：{@link Context#cancellationError()} 在截止令牌触发时总是报超时，不论外部令牌。 */
    private static boolean deadlineFired(Context context) {
        return context.cancellationError().map(Error::code).filter(InvocationErrors.DEADLINE_EXCEEDED::equals).isPresent();
    }
}
