package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.InterruptedIOException;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Observe 阶段的内置步骤：翻译异常（RFC-0011 规则 5、10，ADR-0236），与 .NET 的 {@code ObserveMiddleware} 同一位置。
 *
 * <p>只接住排在它之后的中间件与处理器抛出的 {@link Exception}，一律翻译为 {@code KERNEL_UNEXPECTED_001} +
 * {@code kernel.invocation.unhandled_exception}，异常本身写进 {@code System.Logger}、不上线；框架异常的错误放进 {@code inner}。
 * 排在它之前的（Ingress 阶段，或 Observe 阶段 Beginning 上归属排在 {@value OperationInvoker#KERNEL_OWNER} 之前的）抛出的异常原样交给调用方。
 * {@link java.lang.Error}（虚拟机错误、断言失败）不翻译、照常抛出。
 */
final class ObserveMiddleware implements Middleware {
    /** 未处理的异常写到这里：JDK 的平台日志，缺省经 java.util.logging 写到 stderr，应用可以换成自己的日志实现。 */
    private static final System.Logger LOGGER = System.getLogger(OperationInvoker.class.getName());

    @Override
    public Result<Unit> invoke(Context context, Step next) {
        try {
            return next.invoke(context);
        } catch (Exception thrown) {
            return Result.fail(translate(context, thrown));
        }
    }

    /**
     * 取消不按异常翻译：异常是取消引起的、上下文也确实取消了，按令牌归因为超时或取消（RFC-0003 第 1 条）；
     * 框架异常的错误放进 inner；其余异常没有内部错误。
     */
    private static Error translate(Context context, Exception thrown) {
        if (isCancellation(thrown)) {
            if (thrown instanceof InterruptedException) {
                // 吞掉中断异常要恢复中断标志，调用线程之后的等待仍能看到中断
                Thread.currentThread().interrupt();
            }
            Optional<Error> cancellation = context.cancellationError();
            if (cancellation.isPresent()) {
                return cancellation.get();
            }
        }
        String id = context.findValue(InvocationScope.KEY).map(scope -> scope.descriptor.contract().id()).orElse("?");
        LOGGER.log(System.Logger.Level.ERROR, "The operation '" + id + "' threw an unhandled exception.", thrown);
        if (thrown instanceof FrameworkException framework) {
            return InvocationErrors.unhandledException(framework.toError());
        }
        return InvocationErrors.unhandledException();
    }

    /**
     * 判断异常是不是取消引起的：沿原因链找取消与中断，链长有上限，成环的原因链不会死循环。
     */
    private static boolean isCancellation(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof CancellationException || current instanceof InterruptedException
                    || current instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
