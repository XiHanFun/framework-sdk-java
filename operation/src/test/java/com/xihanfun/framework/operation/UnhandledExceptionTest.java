package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.invocation.CancellableContext;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * 处理器或中间件抛出的异常由调用器翻译为 {@code KERNEL_UNEXPECTED_001} + {@code kernel.invocation.unhandled_exception}
 * （RFC-0011 规则 5、10，ADR-0235）：没有参数，框架异常的错误放进 inner，取消不按异常翻译。
 */
class UnhandledExceptionTest {
    private static final String UNHANDLED = "kernel.invocation.unhandled_exception";

    private static OperationInvoker invoker(OperationHandler<Unit, Unit> handler, PipelineBuilder pipeline) {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("jobs.run"), Unit.class, Unit.class, handler);
        return new OperationInvoker(registry, pipeline);
    }

    private static Error xgi(OperationInvoker invoker, Context context) {
        return invoker.invoke(context, OperationRequest.of("jobs.run")).outcome().error().orElseThrow();
    }

    private static void assertUnhandled(Error error) {
        assertEquals("KERNEL_UNEXPECTED_001", error.code());
        assertEquals(Optional.of(UNHANDLED), error.messageKey());
        assertEquals(Map.of(), error.details(), "no arguments");
        assertEquals("An unhandled exception occurred during the invocation.", error.message());
    }

    @Test
    void handlerExceptionsBecomeUnexpectedErrors() {
        OperationInvoker invoker = invoker((context, request) -> {
            throw new IllegalStateException("boom");
        }, new PipelineBuilder());
        Error error = xgi(invoker, Context.background());
        assertUnhandled(error);
        assertEquals(Optional.empty(), error.inner(), "an ordinary exception carries no inner error");
        Result<Unit> typed = invoker.invoke(Context.background(), "jobs.run", Unit.VALUE, Unit.class);
        assertUnhandled(typed.error().orElseThrow());
    }

    @Test
    void middlewareExceptionsBecomeUnexpectedErrors() {
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Broken", (context, next) -> {
            throw new UnsupportedOperationException("no");
        }), PipelineStage.OBSERVE);
        assertUnhandled(xgi(invoker((context, request) -> Result.success(), pipeline), Context.background()));
    }

    @Test
    void frameworkErrorsGoIntoInner() {
        OperationInvoker invoker = invoker((context, request) -> {
            throw new FrameworkException("KERNEL_PIPELINE_001", "The ordering constraints in stage 'validate' form a cycle.");
        }, new PipelineBuilder());
        Error error = xgi(invoker, Context.background());
        assertUnhandled(error);
        Error inner = error.inner().orElseThrow();
        assertEquals("KERNEL_PIPELINE_001", inner.code());
        assertEquals("The ordering constraints in stage 'validate' form a cycle.", inner.message());
    }

    @Test
    void cancellationIsNotTranslatedAsAnException() {
        OperationInvoker invoker = invoker((context, request) -> {
            throw new CancellationException("stopped");
        }, new PipelineBuilder());
        try (CancellableContext scope = Context.background().withCancellation()) {
            scope.cancel();
            Error error = xgi(invoker, scope.context());
            assertEquals(InvocationErrors.CANCELLED, error.code(), "attributed by the tokens (RFC-0003 rule 1)");
        }
        // 上下文没有取消时，取消异常也只是一个未处理的异常
        assertUnhandled(xgi(invoker, Context.background()));
    }

    @Test
    void anExpiredDeadlineStopsTheCallBeforeTheFirstMiddleware() {
        // RFC-0011 规则 10：调用器在第一个中间件之前按时钟核对截止时间，已到即结束、管道不执行
        java.util.concurrent.atomic.AtomicInteger entered = new java.util.concurrent.atomic.AtomicInteger();
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Count", (context, next) -> {
            entered.incrementAndGet();
            return next.invoke(context);
        }), PipelineStage.OBSERVE);
        OperationInvoker invoker = invoker((context, request) -> Result.success(), pipeline);
        Context expired = Context.builder().deadline(java.time.Instant.now().minusSeconds(1)).build();
        Error error = xgi(invoker, expired);
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, error.code());
        assertEquals(Optional.of("kernel.invocation.deadline_before_start"), error.messageKey());
        assertEquals("The invocation exceeded its deadline before it started.", error.message());
        assertEquals(Optional.of("kernel.invocation.deadline_before_start"),
                invoker.invoke(expired, "jobs.run", Unit.VALUE, Unit.class).error().orElseThrow().messageKey());
        assertEquals(0, entered.get(), "the pipeline does not run");
        assertTrue(invoker.invoke(Context.builder().timeout(java.time.Duration.ofSeconds(30)).build(), OperationRequest.of("jobs.run")).isOk());
        assertEquals(1, entered.get());
    }

    @Test
    void aMissingResultIsAnUnhandledFault() {
        OperationInvoker invoker = invoker((context, request) -> null, new PipelineBuilder());
        assertUnhandled(xgi(invoker, Context.background()));
        assertTrue(invoker.invoke(Context.background(), "jobs.run", Unit.VALUE, Unit.class).isFailed());
    }
}
