package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.invocation.CancellableContext;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.pipeline.Placement;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/**
 * 处理器或中间件抛出的异常由调用器翻译为 {@code KERNEL_UNEXPECTED_001} + {@code kernel.invocation.unhandled_exception}
 * （RFC-0011 规则 5、10，ADR-0235）：没有参数，框架异常的错误放进 inner，取消不按异常翻译。三个内置步骤的位置与 .NET 的内置中间件相同
 * （ADR-0236）：Observe 阶段翻译异常，Resilience 阶段核对截止时间，Validate 阶段解码。
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
    void anExpiredDeadlineStopsTheCallInTheResilienceStage() {
        // RFC-0011 规则 10（ADR-0236）：截止时间由 Resilience 阶段开头的内置步骤核对，Ingress 与 Observe 阶段的中间件照常执行
        java.util.concurrent.atomic.AtomicInteger observed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger authorized = new java.util.concurrent.atomic.AtomicInteger();
        PipelineBuilder pipeline = new PipelineBuilder()
                .useAt(Middleware.of("Count", (context, next) -> {
                    observed.incrementAndGet();
                    return next.invoke(context);
                }), PipelineStage.OBSERVE)
                .useAt(Middleware.of("Authorize", (context, next) -> {
                    authorized.incrementAndGet();
                    return next.invoke(context);
                }), PipelineStage.AUTHORIZE);
        OperationInvoker invoker = invoker((context, request) -> Result.success(), pipeline);
        Context expired = Context.builder().deadline(java.time.Instant.now().minusSeconds(1)).build();
        Error error = xgi(invoker, expired);
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, error.code());
        assertEquals(Optional.of("kernel.invocation.deadline_before_start"), error.messageKey());
        assertEquals("The invocation exceeded its deadline before it started.", error.message());
        assertEquals(Optional.of("kernel.invocation.deadline_before_start"),
                invoker.invoke(expired, "jobs.run", Unit.VALUE, Unit.class).error().orElseThrow().messageKey());
        assertEquals(2, observed.get(), "the observe stage runs before the deadline check");
        assertEquals(0, authorized.get(), "later stages do not run");
        // 截止时间先于解码：两样都不对时报截止时间
        assertEquals(Optional.of("kernel.invocation.deadline_before_start"),
                invoker.invoke(expired, OperationRequest.of("jobs.run", "{")).outcome().error().orElseThrow().messageKey());
        assertTrue(invoker.invoke(Context.builder().timeout(java.time.Duration.ofSeconds(30)).build(), OperationRequest.of("jobs.run")).isOk());
        assertEquals(1, authorized.get());
    }

    /** 等到截止令牌触发（{@link Context#cancellationError()} 报超时），最多 5 秒；外部令牌先触发时仍接着等。 */
    private static void awaitDeadline(Context context) {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!context.cancellationError().map(Error::code).filter(InvocationErrors.DEADLINE_EXCEEDED::equals).isPresent() && System.nanoTime() < until) {
            try {
                context.awaitCancellation(java.time.Duration.ofMillis(10));
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void aCancellationResultAfterTheDeadlineFiredIsATimeout() {
        // RFC-0003 规则 1（ADR-0236）：下游把取消写成结果，截止令牌已触发即改报超时；只有调用方取消时保持取消；两者都触发也是超时
        OperationInvoker invoker = invoker((context, request) -> {
            awaitDeadline(context);
            return Result.fail(InvocationErrors.cancelled());
        }, new PipelineBuilder());
        Error timedOut = xgi(invoker, Context.builder().timeout(java.time.Duration.ofMillis(50)).build());
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, timedOut.code());
        assertEquals(Optional.of("kernel.invocation.deadline_exceeded"), timedOut.messageKey());
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, invoker.invoke(Context.builder().timeout(java.time.Duration.ofMillis(50)).build(), "jobs.run",
                Unit.VALUE, Unit.class).error().orElseThrow().code(), "the typed entry follows the same rule");

        OperationInvoker cancelling = invoker((context, request) -> Result.fail(InvocationErrors.cancelled()), new PipelineBuilder());
        try (CancellableContext scope = Context.background().withCancellation()) {
            scope.cancel();
            Error cancelled = xgi(cancelling, scope.context());
            assertEquals(InvocationErrors.CANCELLED, cancelled.code(), "only the caller cancelled");
            assertEquals(Optional.of("kernel.invocation.cancelled"), cancelled.messageKey());
        }
        try (CancellableContext scope = Context.builder().timeout(java.time.Duration.ofMillis(50)).build().withCancellation()) {
            scope.cancel();
            Error both = xgi(invoker, scope.context());
            assertEquals(InvocationErrors.DEADLINE_EXCEEDED, both.code(), "the deadline fired too: a timeout");
            assertEquals(Optional.of("kernel.invocation.deadline_exceeded"), both.messageKey());
        }
        // 截止时间没到、调用方也没取消时，写成取消的结果原样返回
        assertEquals(InvocationErrors.CANCELLED, xgi(cancelling, Context.builder().timeout(java.time.Duration.ofSeconds(30)).build()).code());
        // 其他失败不改写
        Error denied = Error.denied("ORDERS_DENIED_001", "orders.denied");
        OperationInvoker denying = invoker((context, request) -> {
            awaitDeadline(context);
            return Result.fail(denied);
        }, new PipelineBuilder());
        assertEquals(denied, xgi(denying, Context.builder().timeout(java.time.Duration.ofMillis(50)).build()));
    }

    @Test
    void aCancellationThrownAfterTheDeadlineFiredIsATimeout() {
        // 抛出路径同一规则：Observe 阶段的内置步骤按令牌归因，截止令牌已触发即超时
        OperationInvoker invoker = invoker((context, request) -> {
            awaitDeadline(context);
            throw new CancellationException("stopped");
        }, new PipelineBuilder());
        Error timedOut = xgi(invoker, Context.builder().timeout(java.time.Duration.ofMillis(50)).build());
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, timedOut.code());
        assertEquals(Optional.of("kernel.invocation.deadline_exceeded"), timedOut.messageKey());
        try (CancellableContext scope = Context.builder().timeout(java.time.Duration.ofMillis(50)).build().withCancellation()) {
            scope.cancel();
            assertEquals(InvocationErrors.DEADLINE_EXCEEDED, xgi(invoker, scope.context()).code(), "both fired: a timeout");
        }
    }

    @Test
    void exceptionsBeforeTheObserveStepReachTheCaller() {
        // Observe 阶段的内置步骤只接住排在它之后的异常：Ingress 阶段与 Observe 阶段开头归属排在 xihan.kernel.core 之前的照常抛出
        Middleware broken = Middleware.of("Broken", (context, next) -> {
            throw new UnsupportedOperationException("no");
        });
        for (PipelineBuilder pipeline : List.of(new PipelineBuilder().useAt(broken, PipelineStage.INGRESS),
                new PipelineBuilder().useAt(broken, PipelineStage.OBSERVE, Placement.BEGINNING, PipelineBuilder.APP_OWNER))) {
            OperationInvoker invoker = invoker((context, request) -> Result.success(), pipeline);
            assertThrows(UnsupportedOperationException.class, () -> invoker.invoke(Context.background(), OperationRequest.of("jobs.run")));
        }
        // 归属排在 xihan.kernel.core 之后的、同位置的中间件在内置步骤之后执行，它的异常被翻译
        OperationInvoker later = invoker((context, request) -> Result.success(),
                new PipelineBuilder().useAt(broken, PipelineStage.OBSERVE, Placement.BEGINNING, "xihan.plugins.audit"));
        assertUnhandled(xgi(later, Context.background()));
    }

    @Test
    void payloadsAreDecodedInTheValidateStage() {
        // 解码在 Validate 阶段开头：Authorize 阶段先拒绝时载荷不解码，报的是拒绝
        Error denied = Error.denied("ORDERS_DENIED_001", "orders.denied");
        OperationInvoker invoker = invoker((context, request) -> Result.success(),
                new PipelineBuilder().useAt(Middleware.of("Deny", (context, next) -> Result.fail(denied)), PipelineStage.AUTHORIZE));
        assertEquals("ORDERS_DENIED_001", invoker.invoke(Context.background(), OperationRequest.of("jobs.run", "{")).outcome().error()
                .orElseThrow().code());
        // Validate 阶段之后的中间件只在载荷合法时执行
        java.util.concurrent.atomic.AtomicInteger handled = new java.util.concurrent.atomic.AtomicInteger();
        OperationInvoker counting = invoker((context, request) -> Result.success(), new PipelineBuilder().useAt(Middleware.of("Count",
                (context, next) -> {
                    handled.incrementAndGet();
                    return next.invoke(context);
                }), PipelineStage.TRANSACTION));
        assertEquals(OperationErrors.INVALID_PAYLOAD, counting.invoke(Context.background(), OperationRequest.of("jobs.run", "[")).outcome()
                .error().orElseThrow().code());
        assertEquals(0, handled.get());
    }

    @Test
    void aMissingResultIsAnUnhandledFault() {
        OperationInvoker invoker = invoker((context, request) -> null, new PipelineBuilder());
        assertUnhandled(xgi(invoker, Context.background()));
        assertTrue(invoker.invoke(Context.background(), "jobs.run", Unit.VALUE, Unit.class).isFailed());
    }
}
