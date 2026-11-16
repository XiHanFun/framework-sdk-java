package com.xihanfun.framework.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * 管道的排序、打印与执行：逐项移植自 Go SDK 的 pipeline_test.go，另测按名字的约束、构建快照与并发执行。
 */
class PipelineBuilderTest {
    private static Middleware recording(String name, List<String> log) {
        return Middleware.of(name, (context, next) -> {
            log.add(name + ":before");
            Result<Unit> outcome = next.invoke(context);
            log.add(name + ":after");
            return outcome;
        });
    }

    private static Middleware passing(String name) {
        return Middleware.of(name, (context, next) -> next.invoke(context));
    }

    /** 按实现类全名命名的中间件。 */
    static final class AuditMiddleware implements Middleware {
        @Override
        public Result<Unit> invoke(Context context, Step next) {
            return next.invoke(context);
        }
    }

    @Test
    void orderFollowsStagePlacementOwnerAndRegistration() {
        List<String> log = new ArrayList<>();
        PipelineBuilder builder = new PipelineBuilder()
                .useAt(recording("Egress", log), PipelineStage.EGRESS, Placement.END, "app")
                .useAt(recording("ObserveLate", log), PipelineStage.OBSERVE, Placement.END, "xihan.b")
                .useAt(recording("ObserveEarly", log), PipelineStage.OBSERVE, Placement.BEGINNING, "xihan.z")
                .useAt(recording("ObserveA", log), PipelineStage.OBSERVE, Placement.END, "xihan.a")
                .use(recording("Transaction", log));
        assertEquals("observe      ObserveEarly\nobserve      ObserveA\nobserve      ObserveLate\ntransaction  Transaction\negress       Egress\n",
                builder.dump());
        Step step = builder.build(context -> {
            log.add("terminal");
            return Result.success();
        });
        assertTrue(step.invoke(Context.background()).isOk());
        assertEquals("ObserveEarly:before,ObserveA:before,ObserveLate:before,Transaction:before,Egress:before,terminal,Egress:after,"
                + "Transaction:after,ObserveLate:after,ObserveA:after,ObserveEarly:after", String.join(",", log));
    }

    @Test
    void constraintsReorderWithinAStage() {
        List<String> log = new ArrayList<>();
        PipelineBuilder builder = new PipelineBuilder()
                .useAt(recording("A", log), PipelineStage.VALIDATE, Placement.END, "app")
                .useAt(recording("B", log), PipelineStage.VALIDATE, Placement.END, "app")
                .useAt(recording("C", log), PipelineStage.VALIDATE, Placement.END, "app")
                .before("C", "A")
                .after("B", "C");
        assertEquals(List.of("C", "A", "B"), builder.describe().stream().map(MiddlewareDescriptor::middleware).toList());
    }

    @Test
    void cyclesAndBadConstraintsAreRejected() {
        PipelineBuilder cycle = new PipelineBuilder()
                .useAt(passing("A"), PipelineStage.VALIDATE)
                .useAt(passing("B"), PipelineStage.VALIDATE)
                .before("A", "B")
                .before("B", "A");
        assertCode(PipelineErrors.CYCLE, cycle::describe);
        assertCode(PipelineErrors.CYCLE, () -> cycle.build(context -> Result.success()));

        PipelineBuilder unregistered = new PipelineBuilder().useAt(passing("A"), PipelineStage.VALIDATE).before("A", "Missing");
        assertCode(PipelineErrors.REGISTRATION, unregistered::describe);
        // 约束主体在声明时必须已登记（RFC-0004 排序规则 7）
        assertCode(PipelineErrors.REGISTRATION, () -> new PipelineBuilder().useAt(passing("A"), PipelineStage.VALIDATE).after("Missing", "A"));

        PipelineBuilder crossStage = new PipelineBuilder()
                .useAt(passing("A"), PipelineStage.VALIDATE)
                .useAt(passing("B"), PipelineStage.EGRESS)
                .before("A", "B");
        assertCode(PipelineErrors.REGISTRATION, crossStage::describe);

        // 重复登记在登记时即报
        PipelineBuilder duplicate = new PipelineBuilder().useAt(passing("A"), PipelineStage.VALIDATE);
        assertCode(PipelineErrors.REGISTRATION, () -> duplicate.useAt(passing("A"), PipelineStage.VALIDATE));

        assertCode(PipelineErrors.UNKNOWN_STAGE, () -> PipelineBuilder.parseStage("middle"));
        assertEquals(PipelineStage.HANDLE, PipelineBuilder.parseStage("handle"));
    }

    @Test
    void namedInstancesAreMatchedByTheirNames() {
        List<String> log = new ArrayList<>();
        PipelineBuilder builder = new PipelineBuilder()
                .useNamedInstance("guard-a", recording("Guard", log), PipelineStage.OBSERVE, Placement.END, "xihan.plugins.a")
                .useNamedInstance("guard-b", recording("Guard", log), PipelineStage.OBSERVE, Placement.END, "xihan.plugins.b");
        assertEquals("observe      Guard[guard-a]\nobserve      Guard[guard-b]\n", builder.dump());
        assertEquals(Optional.of("guard-a"), builder.describe().get(0).instance());
        builder.useAt(passing("Other"), PipelineStage.OBSERVE, Placement.END, "app");
        // 按名字登记的实例只认实例名，中间件名对不上它们（与 .NET 的 FindByName 相同，ADR-0236）
        assertMessage(PipelineErrors.REGISTRATION, "Middleware 'Guard' is not registered, so no ordering constraint can be declared for it.",
                () -> builder.before("Guard", "Other"));
        builder.before("Other", "Guard");
        assertMessage(PipelineErrors.REGISTRATION, "The ordering constraint of middleware 'Other' references unregistered middleware 'Guard'.", builder::describe);

        // 实例名与另一个没有名字的中间件同名即歧义：主体在声明时报，目标在排序时报
        PipelineBuilder clash = new PipelineBuilder()
                .useNamedInstance("Audit", passing("Guard"), PipelineStage.OBSERVE, Placement.END, "app")
                .useAt(passing("Audit"), PipelineStage.OBSERVE)
                .useAt(passing("Other"), PipelineStage.OBSERVE);
        String ambiguous = "The middleware name 'Audit' matches several registrations, so ordering constraints by name are ambiguous for it.";
        assertMessage(PipelineErrors.REGISTRATION, ambiguous, () -> clash.before("Audit", "Other"));
        clash.after("Other", "Audit");
        assertMessage(PipelineErrors.REGISTRATION, ambiguous, clash::dump);

        // 按实例名声明的约束可用（排序规则 7）
        PipelineBuilder named = new PipelineBuilder()
                .useNamedInstance("guard-a", passing("Guard"), PipelineStage.OBSERVE, Placement.END, "xihan.plugins.a")
                .useNamedInstance("guard-b", passing("Guard"), PipelineStage.OBSERVE, Placement.END, "xihan.plugins.b")
                .before("guard-b", "guard-a");
        assertEquals("observe      Guard[guard-b]\nobserve      Guard[guard-a]\n", named.dump());
        assertCode(PipelineErrors.REGISTRATION,
                () -> named.useNamedInstance("guard-a", passing("Guard"), PipelineStage.OBSERVE, Placement.END, "app"));
    }

    @Test
    void shortCircuitSkipsDownstream() {
        List<String> log = new ArrayList<>();
        Error denied = Error.denied("ORDERS_DENIED_001", "orders.denied");
        Step step = new PipelineBuilder()
                .useAt(Middleware.of("Deny", (context, next) -> Result.fail(denied)), PipelineStage.AUTHORIZE)
                .useAt(recording("Handle", log), PipelineStage.HANDLE)
                .build(context -> Result.success());
        Result<Unit> outcome = step.invoke(Context.background());
        assertEquals("ORDERS_DENIED_001", outcome.error().orElseThrow().code());
        assertTrue(log.isEmpty());
    }

    @Test
    void middlewareNamesAreStable() {
        PipelineBuilder builder = new PipelineBuilder().useAt(new AuditMiddleware(), PipelineStage.OBSERVE);
        assertEquals(AuditMiddleware.class.getName(), builder.describe().get(0).middleware(), "a class names its middleware");
        Middleware lambda = (context, next) -> next.invoke(context);
        assertThrows(IllegalArgumentException.class, () -> builder.useAt(lambda, PipelineStage.OBSERVE), "a lambda needs a name");
        assertThrows(IllegalArgumentException.class, () -> builder.useAt(passing("X"), PipelineStage.OBSERVE, Placement.END, ""));
    }

    @Test
    void builtStepsAreSnapshotsAndRunConcurrently() throws Exception {
        AtomicInteger count = new AtomicInteger();
        PipelineBuilder builder = new PipelineBuilder().useAt(Middleware.of("Count", (context, next) -> {
            count.incrementAndGet();
            return next.invoke(context);
        }), PipelineStage.OBSERVE);
        List<String> seen = new CopyOnWriteArrayList<>();
        Step step = builder.build(context -> {
            seen.add(context.correlation().orElseThrow());
            return Result.success();
        });
        builder.useAt(Middleware.of("Late", (context, next) -> Result.fail(Error.denied("ORDERS_DENIED_001", "orders.denied"))), PipelineStage.AUTHORIZE);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result<Unit>>> futures = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                Context context = Context.background().withBaggage("xihan.correlation", "c" + index);
                futures.add(threads.submit(() -> step.invoke(context)));
            }
            for (Future<Result<Unit>> future : futures) {
                assertTrue(future.get(5, TimeUnit.SECONDS).isOk(), "a later registration does not change a built pipeline");
            }
        }
        assertEquals(200, count.get());
        assertEquals(200, seen.size());
        assertEquals(2, builder.describe().size());
    }

    @Test
    void registrationsAndConstraintsAreUnbounded() {
        // 与 .NET 的 PipelineBuilder 一样不设上限：1100 个中间件与 4396 条约束（超过上一版的 1024 与 4096）照常排序、构建与执行
        PipelineBuilder builder = new PipelineBuilder();
        int count = 1_100;
        for (int index = 0; index < count; index++) {
            builder.useNamedInstance("m" + index, passing("Many"), PipelineStage.VALIDATE, Placement.END, PipelineBuilder.APP_OWNER);
        }
        for (int round = 0; round < 4; round++) {
            for (int index = 1; index < count; index++) {
                // 每个都排在前一个之前：顺序整体倒过来
                builder.before("m" + index, "m" + (index - 1));
            }
        }
        List<MiddlewareDescriptor> ordered = builder.describe();
        assertEquals(count, ordered.size());
        assertEquals(Optional.of("m" + (count - 1)), ordered.get(0).instance());
        assertEquals(Optional.of("m0"), ordered.get(count - 1).instance());
        assertTrue(builder.build(context -> Result.success()).invoke(Context.background()).isOk());
    }

    @Test
    void copiesAreIndependent() {
        PipelineBuilder original = new PipelineBuilder()
                .useAt(passing("A"), PipelineStage.VALIDATE)
                .useAt(passing("B"), PipelineStage.VALIDATE)
                .before("B", "A");
        PipelineBuilder copy = original.copy().useAt(passing("C"), PipelineStage.VALIDATE, Placement.BEGINNING, "xihan.kernel.core");
        assertEquals("validate     C\nvalidate     B\nvalidate     A\n", copy.dump(), "the copy keeps the constraints and adds its own");
        assertEquals("validate     B\nvalidate     A\n", original.dump(), "the original is unchanged");
        assertCode(PipelineErrors.REGISTRATION, () -> original.copy().useAt(passing("A"), PipelineStage.VALIDATE));
    }

    @Test
    void stepsMustReturnAResult() {
        Step step = new PipelineBuilder().useAt(Middleware.of("Broken", (context, next) -> null), PipelineStage.OBSERVE)
                .build(context -> Result.success());
        assertThrows(IllegalStateException.class, () -> step.invoke(Context.background()));
    }

    private static void assertMessage(String code, String message, Executable action) {
        FrameworkException thrown = assertThrows(FrameworkException.class, action);
        assertEquals(code, thrown.code());
        assertEquals(message, thrown.getMessage());
    }

    private static void assertCode(String code, Executable action) {
        FrameworkException thrown = assertThrows(FrameworkException.class, action);
        assertEquals(code, thrown.code(), thrown.getMessage());
    }
}
