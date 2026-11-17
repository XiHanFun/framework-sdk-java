package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.invocation.CancellableContext;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.pipeline.Placement;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.Maybe;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.Unit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 操作的登记与执行（RFC-0011）：登记与容量、XGI 与类型化入口、载荷的错误路径、嵌套与并发调用。
 */
class OperationInvokerTest {
    /** 请求。 */
    record CreateOrder(int quantity) {
    }

    /** 响应。 */
    record OrderCreated(String id, int quantity) {
    }

    /** 带可选成员的请求。 */
    record Note(String title, Maybe<String> text) {
    }

    private static final OperationHandler<CreateOrder, OrderCreated> CREATE = (context, request) -> {
        if (request.quantity() <= 0) {
            return Result.fail(Error.validation("ORDERS_VALIDATION_001", "orders.quantity.invalid", Map.of("quantity", request.quantity())));
        }
        return Result.ok(new OrderCreated("o-" + context.correlation().orElse(""), request.quantity()));
    };

    private static OperationInvoker newInvoker(PipelineBuilder pipeline) {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("orders.create").withStability(Stability.STABLE).withExpose(Expose.HTTP), CreateOrder.class,
                OrderCreated.class, CREATE, OperationSchema.of("{}", "{}"), PipelineBuilder.APP_OWNER);
        return new OperationInvoker(registry, pipeline);
    }

    private static Context correlated(String correlation) {
        return Context.builder().baggage(BaggageKeys.CORRELATION, correlation).build();
    }

    @Test
    void invokeRunsThePipelineAndEncodesTheResponse() {
        AtomicReference<String> seen = new AtomicReference<>();
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Observe", (context, next) -> {
            seen.set(Operations.find(context).orElseThrow().contract().id());
            return next.invoke(context);
        }), PipelineStage.OBSERVE);
        OperationInvoker invoker = newInvoker(pipeline);

        OperationResponse response = invoker.invoke(correlated("c1"), OperationRequest.of("orders.create", "{\"quantity\":2}"));
        assertTrue(response.isOk(), response.toString());
        assertEquals(Optional.of("{\"id\":\"o-c1\",\"quantity\":2}"), response.payloadText());
        assertEquals("orders.create", seen.get());

        OperationResponse failed = invoker.invoke(Context.background(), OperationRequest.of("orders.create", "{\"quantity\":0}"));
        assertEquals("ORDERS_VALIDATION_001", failed.outcome().error().orElseThrow().code());
        assertEquals(Optional.empty(), failed.payload());

        OperationResponse missing = invoker.invoke(Context.background(), OperationRequest.of("orders.missing"));
        assertEquals(OperationErrors.NOT_FOUND, missing.outcome().error().orElseThrow().code());
        assertEquals(Map.of("id", "orders.missing"), missing.outcome().error().orElseThrow().details());

        for (String payload : List.of("null", "[1]", "{", " null ", "{} {}")) {
            OperationResponse invalid = invoker.invoke(Context.background(), OperationRequest.of("orders.create", payload));
            assertEquals(OperationErrors.INVALID_PAYLOAD, invalid.outcome().error().orElseThrow().code(), payload);
        }
        OperationResponse empty = invoker.invoke(Context.background(), OperationRequest.of("orders.create"));
        assertEquals("ORDERS_VALIDATION_001", empty.outcome().error().orElseThrow().code(), "an empty payload is {}");
        OperationResponse blank = invoker.invoke(Context.background(), OperationRequest.of("orders.create", " \n"));
        // 只有空白不是 JSON（RFC-0001「JSON 文本」，ADR-0236）：以前当作 {}
        assertEquals(Map.of("id", "orders.create", "reason", "$"), blank.outcome().error().orElseThrow().details(), "a blank payload is not JSON");
    }

    @Test
    void typedInvokeChecksTypes() {
        OperationInvoker invoker = newInvoker(new PipelineBuilder());
        Result<OrderCreated> created = invoker.invoke(correlated("c2"), "orders.create", new CreateOrder(3), OrderCreated.class);
        assertEquals(Optional.of(new OrderCreated("o-c2", 3)), created.value());
        Result<String> mismatch = invoker.invoke(Context.background(), "orders.create", new CreateOrder(3), String.class);
        assertEquals(OperationErrors.TYPE_MISMATCH, mismatch.error().orElseThrow().code());
        assertEquals(Map.of("id", "orders.create"), mismatch.error().orElseThrow().details(), "type names are language specific (ADR-0236)");
        Result<OrderCreated> wrongRequest = invoker.invoke(Context.background(), "orders.create", "not a request", OrderCreated.class);
        assertEquals(OperationErrors.TYPE_MISMATCH, wrongRequest.error().orElseThrow().code());
        Result<Object> widened = invoker.invoke(Context.background(), "orders.create", new CreateOrder(1), Object.class);
        assertTrue(widened.isOk(), "the response type may be a supertype");
        assertEquals(OperationErrors.NOT_FOUND, invoker.invoke(Context.background(), "orders.missing", new CreateOrder(1), OrderCreated.class)
                .error().orElseThrow().code());
    }

    @Test
    void registryRejectsDuplicatesAndBadIds() {
        OperationRegistry registry = new OperationRegistry();
        OperationHandler<Unit, Unit> handler = (context, request) -> Result.success();
        IllegalArgumentException badId = assertThrows(IllegalArgumentException.class, () -> OperationContract.of("Orders.Create"));
        assertEquals("The operation ID 'Orders.Create' must match <resource>.<verb>.", badId.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OperationContract.of("orders.create").withPermission("p").withPolicy("q"),
                "permission and policy are exclusive");
        assertThrows(IllegalArgumentException.class, () -> OperationContract.of("orders.create").withPolicy("q").withPermission("p"));
        registry.add(OperationContract.of("orders.create"), Unit.class, Unit.class, handler);
        FrameworkException duplicate = assertThrows(FrameworkException.class,
                () -> registry.add(OperationContract.of("orders.create"), Unit.class, Unit.class, handler));
        assertEquals(OperationErrors.DUPLICATE, duplicate.code());
        List<OperationDescriptor> list = registry.list();
        assertEquals(1, list.size());
        assertEquals(PipelineBuilder.APP_OWNER, list.get(0).owner());
        assertTrue(OperationId.isValid("order-lines.list"));
        assertFalse(OperationId.isValid("orders"));
        assertThrows(IllegalArgumentException.class, () -> registry.add(OperationContract.of("orders.list"), Unit.class, Unit.class, handler, ""));
    }

    @Test
    void registryHoldsAtMostTenThousandOperations() {
        OperationRegistry registry = new OperationRegistry();
        OperationHandler<Unit, Unit> handler = (context, request) -> Result.success();
        for (int index = 0; index < OperationRegistry.CAPACITY; index++) {
            registry.add(OperationContract.of("items-" + index + ".list"), Unit.class, Unit.class, handler);
        }
        assertEquals(10_000, registry.size());
        FrameworkException full = assertThrows(FrameworkException.class,
                () -> registry.add(OperationContract.of("items.overflow"), Unit.class, Unit.class, handler));
        assertEquals(OperationErrors.REGISTRY_FULL, full.code());
        assertEquals("The operation registry has reached its capacity of 10000.", full.getMessage());
        assertEquals(Map.of("capacity", 10000), full.toError().details());
        FrameworkException duplicate = assertThrows(FrameworkException.class,
                () -> registry.add(OperationContract.of("items-0.list"), Unit.class, Unit.class, handler));
        assertEquals(OperationErrors.DUPLICATE, duplicate.code(), "a duplicate is reported as such even when the registry is full");
        assertEquals("items-0.list", registry.list().get(0).contract().id());
    }

    @Test
    void payloadErrorsNameOnlyThePath() {
        OperationInvoker invoker = newInvoker(new PipelineBuilder());
        Map<String, String> cases = Map.of(
                "{\"quantity\":\"2\"}", "$.quantity",
                "{\"quantity\":2.5}", "$.quantity",
                "{\"quantity\":true}", "$.quantity",
                "[1]", "$");
        cases.forEach((payload, path) -> {
            Error error = invoker.invoke(Context.background(), OperationRequest.of("orders.create", payload)).outcome().error().orElseThrow();
            assertEquals(OperationErrors.INVALID_PAYLOAD, error.code(), payload);
            assertEquals(Optional.of("kernel.operation.invalid_payload"), error.messageKey());
            assertEquals(Map.of("id", "orders.create", "reason", path), error.details(), payload);
        });
        Error isNull = invoker.invoke(Context.background(), OperationRequest.of("orders.create", "null")).outcome().error().orElseThrow();
        assertEquals(Map.of("id", "orders.create", "reason", "$"), isNull.details());
        OperationResponse unknown = invoker.invoke(correlated("c3"), OperationRequest.of("orders.create", "{\"quantity\":1,\"extra\":true}"));
        assertTrue(unknown.isOk(), "unknown request fields are ignored");

        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("notes.create"), Note.class, Note.class, (context, request) -> Result.ok(request));
        OperationInvoker notes = new OperationInvoker(registry);
        assertEquals(Optional.of("{\"title\":\"t\",\"text\":null}"),
                notes.invoke(Context.background(), OperationRequest.of("notes.create", "{\"title\":\"t\"}")).payloadText(),
                "a missing Maybe member is none");
        assertEquals(OperationErrors.INVALID_PAYLOAD, notes.invoke(Context.background(), OperationRequest.of("notes.create", "{\"title\":1}"))
                .outcome().error().orElseThrow().code(), "numbers are not coerced into strings");
    }

    @Test
    void nestedInvocationsKeepTheirOwnScope() {
        List<String> seen = new CopyOnWriteArrayList<>();
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Observe", (context, next) -> {
            String id = Operations.find(context).orElseThrow().contract().id();
            seen.add(id + ":before");
            Result<Unit> outcome = next.invoke(context);
            seen.add(Operations.find(context).orElseThrow().contract().id() + ":after");
            return outcome;
        }), PipelineStage.OBSERVE);
        OperationRegistry registry = new OperationRegistry();
        AtomicReference<OperationInvoker> self = new AtomicReference<>();
        registry.add(OperationContract.of("audits.write"), Unit.class, Unit.class, (context, request) -> Result.success());
        registry.add(OperationContract.of("orders.create"), CreateOrder.class, OrderCreated.class, (context, request) -> {
            OperationResponse audit = self.get().invoke(context, OperationRequest.of("audits.write"));
            if (!audit.isOk()) {
                return Result.fail(audit.outcome().error().orElseThrow());
            }
            return Result.ok(new OrderCreated("o", request.quantity()));
        });
        self.set(new OperationInvoker(registry, pipeline));
        OperationResponse response = self.get().invoke(Context.background(), OperationRequest.of("orders.create", "{\"quantity\":1}"));
        assertTrue(response.isOk(), response.toString());
        assertEquals(List.of("orders.create:before", "audits.write:before", "audits.write:after", "orders.create:after"), seen,
                "the outer middleware still sees the outer operation after a nested call");
    }

    @Test
    void invocationsRunConcurrently() throws Exception {
        OperationInvoker invoker = newInvoker(new PipelineBuilder());
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<OperationResponse>> futures = new ArrayList<>();
            for (int index = 1; index <= 500; index++) {
                int quantity = index;
                futures.add(threads.submit(() -> invoker.invoke(correlated("c" + quantity), OperationRequest.of("orders.create", "{\"quantity\":" + quantity + "}"))));
            }
            for (int index = 1; index <= 500; index++) {
                OperationResponse response = futures.get(index - 1).get(5, TimeUnit.SECONDS);
                assertEquals(Optional.of("{\"id\":\"o-c" + index + "\",\"quantity\":" + index + "}"), response.payloadText());
            }
        }
    }

    @Test
    void handlersSeeTheCancellationOfTheirContext() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("jobs.run"), Unit.class, Unit.class,
                (context, request) -> context.cancellation().<Result<Unit>>map(Result::fail).orElseGet(Result::success));
        OperationInvoker invoker = new OperationInvoker(registry);
        try (CancellableContext scope = Context.background().withCancellation()) {
            scope.cancel();
            OperationResponse response = invoker.invoke(scope.context(), OperationRequest.of("jobs.run"));
            assertEquals(InvocationErrors.CANCELLED, response.outcome().error().orElseThrow().code());
        }
    }

    @Test
    void egressMiddlewareSeesTheResponseBeforeItIsEncoded() {
        AtomicReference<Object> written = new AtomicReference<>();
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Egress", (context, next) -> {
            Result<Unit> outcome = next.invoke(context);
            Operations.findResponse(context).ifPresent(written::set);
            return outcome;
        }), PipelineStage.EGRESS, Placement.END, "app");
        newInvoker(pipeline).invoke(correlated("c4"), OperationRequest.of("orders.create", "{\"quantity\":4}"));
        assertEquals(new OrderCreated("o-c4", 4), written.get(), "the response object; it is encoded after the pipeline (ADR-0236)");
    }

    @Test
    void contractsAndSchemasValidateThemselves() {
        OperationContract contract = OperationContract.of("orders.get");
        assertEquals(Stability.STABLE, contract.stability());
        assertEquals(Set.of(), contract.expose(), "not published unless exposed");
        assertEquals(Set.of(Expose.HTTP, Expose.AI), contract.withExpose(Expose.AI, Expose.HTTP).expose());
        assertEquals(Optional.of("orders.read"), contract.withPermission("orders.read").permission());
        assertEquals(Optional.of(Expose.COMMAND_LINE), Expose.parse("commandline"));
        assertThrows(IllegalArgumentException.class, () -> OperationSchema.of("{}", "[]"));
        assertThrows(IllegalArgumentException.class, () -> OperationSchema.of("not json", "{}"));
        assertEquals("{\"type\":\"object\"}", OperationSchema.of("{\"type\":\"object\"}", "{}").request());
    }

    @Test
    void theReadmeExampleRuns() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("orders.create").withStability(Stability.EXPERIMENTAL).withExpose(Expose.HTTP),
                CreateOrder.class, OrderCreated.class,
                (context, request) -> request.quantity() > 0
                        ? Result.ok(new OrderCreated("o-" + context.correlation().orElse(""), request.quantity()))
                        : Result.fail(Error.validation("ORDERS_VALIDATION_001", "orders.quantity.invalid", Map.of("quantity", request.quantity()))));
        PipelineBuilder pipeline = new PipelineBuilder()
                .useAt(Middleware.of("Observe", (context, next) -> next.invoke(context)), PipelineStage.OBSERVE, Placement.END, PipelineBuilder.APP_OWNER);
        OperationInvoker invoker = new OperationInvoker(registry, pipeline);
        Context context = Context.builder().timeout(java.time.Duration.ofSeconds(2)).baggage(BaggageKeys.CORRELATION, "c1").build();
        OperationResponse response = invoker.invoke(context, OperationRequest.of("orders.create", "{\"quantity\":2}"));
        assertEquals(Optional.of("{\"id\":\"o-c1\",\"quantity\":2}"), response.payloadText());
    }

    @Test
    void aSuccessfulPipelineMustReachTheOperation() {
        PipelineBuilder pipeline = new PipelineBuilder().useAt(Middleware.of("Swallow", (context, next) -> Result.success()), PipelineStage.OBSERVE);
        OperationInvoker invoker = newInvoker(pipeline);
        Error error = invoker.invoke(Context.background(), OperationRequest.of("orders.create", "{\"quantity\":1}")).outcome().error().orElseThrow();
        assertEquals(OperationErrors.UNEXPECTED, error.code());
        assertEquals(Optional.of("kernel.operation.no_response"), error.messageKey());
        assertEquals(Map.of("id", "orders.create"), error.details());
        Result<OrderCreated> typed = invoker.invoke(Context.background(), "orders.create", new CreateOrder(1), OrderCreated.class);
        assertEquals(OperationErrors.UNEXPECTED, typed.error().orElseThrow().code());
    }
}
