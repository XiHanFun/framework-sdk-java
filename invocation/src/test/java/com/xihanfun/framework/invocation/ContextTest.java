package com.xihanfun.framework.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.primitives.BaggageKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 调用上下文：逐项移植自 Go SDK 的 context_test.go，另测取消的归因、回调与并发。
 */
class ContextTest {
    @Test
    void newGeneratesTraceAndKeepsBaggage() {
        Context context = Context.builder().baggage(Map.of(BaggageKeys.TENANT, "t1")).build();
        assertEquals(32, context.traceId().length());
        assertEquals(16, context.spanId().length());
        assertEquals(Optional.of("t1"), context.tenant());
        assertEquals(Optional.empty(), context.principal());
        assertEquals(Optional.empty(), context.sampled(), "a generated trace is the root of the chain");

        Context derived = context.withBaggage(BaggageKeys.CORRELATION, "c1").withBaggage("xihan.feature-flag", "on");
        assertEquals(Optional.of("c1"), derived.correlation());
        assertEquals(Optional.empty(), context.correlation(), "derived baggage must not leak back");
        assertEquals(Optional.empty(), derived.withoutUnregisteredBaggage().findBaggage("xihan.feature-flag"), "unregistered keys are stripped");
        assertEquals(Optional.of("c1"), derived.withoutUnregisteredBaggage().correlation());

        ContextKey<Integer> key = ContextKey.named("key");
        derived.items().put(key, 1);
        assertEquals(Optional.of(1), context.items().find(key), "items are shared between derived contexts");

        TraceParent parent = TraceParent.parse(context.traceParent()).orElseThrow();
        assertEquals(context.traceId(), parent.traceId());
        assertEquals(context.spanId(), parent.spanId());
        assertTrue(parent.sampled());
    }

    @Test
    void deadlineCancelsAndTranslates() throws InterruptedException {
        Context context = Context.builder().timeout(Duration.ofMillis(20)).build();
        assertTrue(context.hasDeadline());
        Duration remaining = context.remainingTime(Instant.now()).orElseThrow();
        assertTrue(remaining.compareTo(Duration.ofMillis(20)) <= 0, "remaining " + remaining);
        assertTrue(context.awaitCancellation(Duration.ofSeconds(5)));
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, context.cancellation().orElseThrow().code());
        assertEquals("kernel.invocation.deadline_exceeded", context.cancellation().orElseThrow().messageKey().orElseThrow());
        assertTrue(context.isExpired(Instant.now()));

        try (CancellableContext cancelled = Context.background().withCancellation()) {
            cancelled.cancel();
            assertTrue(cancelled.context().awaitCancellation(Duration.ofSeconds(5)));
            assertEquals(InvocationErrors.CANCELLED, cancelled.context().cancellation().orElseThrow().code());
        }
        assertEquals(Optional.empty(), Context.background().cancellation(), "no error without cancellation");
        assertFalse(Context.background().awaitCancellation(Duration.ofMillis(10)));
    }

    @Test
    void traceParentParsing() {
        assertTrue(TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01").isPresent());
        for (String bad : List.of("", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331",
                "00-00000000000000000000000000000000-b7ad6b7169203331-01", "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",
                "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01")) {
            assertEquals(Optional.empty(), TraceParent.parse(bad), bad + " must not parse");
        }
        Context context = Context.builder().traceParent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00").build();
        assertEquals("0af7651916cd43dd8448eb211c80319c", context.traceId());
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00", context.traceParent(), "an unsampled upstream stays unsampled");
        assertEquals(Optional.of(false), context.sampled());
        Context ignored = Context.builder().traceParent("garbage").build();
        assertEquals(Optional.empty(), ignored.sampled(), "an invalid traceparent counts as no upstream");
        Context traced = Context.builder().trace("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331").build();
        assertEquals(Optional.of(true), traced.sampled(), "a trace without flags counts as sampled upstream");
        assertTrue(TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-03").orElseThrow().sampled(), "the sampled bit");
        assertThrows(IllegalArgumentException.class, () -> Context.builder().trace("xyz", "b7ad6b7169203331"));
    }

    @Test
    void malformedBaggageKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Context.background().withBaggage("tenant", "t1"));
        assertThrows(IllegalArgumentException.class, () -> Context.builder().baggage("xihan.Tenant", "t1"));
        Context.Builder builder = Context.builder();
        for (int index = 0; index < Context.BAGGAGE_CAPACITY; index++) {
            builder.baggage("xihan.k" + index, "v");
        }
        assertThrows(IllegalStateException.class, () -> builder.baggage("xihan.overflow", "v"));
    }

    @Test
    void theDeadlineTokenWinsOnceItFires() throws InterruptedException {
        // RFC-0004：归因只看令牌；调用方先取消时报取消，截止令牌随后触发即改报超时，不论调用方是否也已取消
        CancellableContext scope = Context.background().withTimeout(Duration.ofMillis(50));
        scope.cancel();
        assertEquals(InvocationErrors.CANCELLED, scope.context().cancellation().orElseThrow().code());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scope.context().cancellation().orElseThrow().code().equals(InvocationErrors.CANCELLED) && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, scope.context().cancellation().orElseThrow().code());

        Context expired = Context.builder().deadline(Instant.now().minusSeconds(1)).build();
        assertEquals(InvocationErrors.DEADLINE_EXCEEDED, expired.cancellation().orElseThrow().code(), "a past deadline fires at once");
    }

    @Test
    void parentsCancelChildrenButNotTheOtherWay() throws InterruptedException {
        try (CancellableContext parent = Context.background().withCancellation(); CancellableContext child = parent.context().withCancellation()) {
            CancellableContext sibling = parent.context().withCancellation();
            sibling.cancel();
            assertTrue(sibling.context().isCancelled());
            assertFalse(parent.context().isCancelled(), "a child does not cancel its parent");

            AtomicInteger calls = new AtomicInteger();
            child.context().onCancelled(calls::incrementAndGet);
            Registration withdrawn = child.context().onCancelled(() -> calls.addAndGet(100));
            withdrawn.close();
            Context sharing = child.context().withBaggage(BaggageKeys.TENANT, "t1");
            parent.cancel();
            assertTrue(sharing.awaitCancellation(Duration.ofSeconds(5)), "derived contexts share the cancellation");
            assertEquals(InvocationErrors.CANCELLED, child.context().cancellation().orElseThrow().code());
            assertEquals(1, calls.get(), "callbacks run once and withdrawn ones not at all");
            child.cancel();
            assertEquals(1, calls.get());
            child.context().onCancelled(calls::incrementAndGet);
            assertEquals(2, calls.get(), "a callback registered after cancellation runs at once");
        }
    }

    @Test
    void downstreamDeadlinesAreNeverLaterThanUpstream() {
        Instant soon = Instant.now().plusSeconds(10);
        Context upstream = Context.builder().deadline(soon).build();
        try (CancellableContext later = upstream.withDeadline(soon.plusSeconds(60)); CancellableContext earlier = upstream.withTimeout(Duration.ofSeconds(1))) {
            assertEquals(Optional.of(soon), later.context().deadline());
            assertTrue(earlier.context().deadline().orElseThrow().isBefore(soon));
        }
    }

    @Test
    void valuesFollowDerivationAndItemsAreBounded() {
        ContextKey<String> key = ContextKey.named("operation");
        Context root = Context.background();
        Context branch = root.withValue(key, "orders.create");
        assertEquals(Optional.of("orders.create"), branch.findValue(key));
        assertEquals(Optional.empty(), root.findValue(key), "values are only visible downstream of the derivation");
        assertEquals(Optional.of("orders.list"), branch.withValue(key, "orders.list").findValue(key));
        assertSame(root.items(), branch.items());

        Items items = Context.background().items();
        for (int index = 0; index < Items.CAPACITY; index++) {
            items.put(ContextKey.named("k" + index), index);
        }
        assertThrows(IllegalStateException.class, () -> items.put(ContextKey.named("overflow"), 0));
        ContextKey<Integer> replaced = ContextKey.named("replaced");
        assertEquals(Optional.empty(), items.remove(replaced));
    }

    @Test
    void callbacksRunExactlyOnceUnderConcurrency() throws Exception {
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int round = 0; round < 20; round++) {
                CancellableContext scope = Context.background().withCancellation();
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int index = 0; index < 50; index++) {
                    futures.add(threads.submit(() -> {
                        start.await();
                        scope.context().onCancelled(calls::incrementAndGet);
                        return null;
                    }));
                    futures.add(threads.submit(() -> {
                        start.await();
                        scope.cancel();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
                assertEquals(50, calls.get(), "every callback runs exactly once");
            }
        }
    }

    @Test
    void failingCallbacksDoNotStopTheOthers() {
        CancellableContext scope = Context.background().withCancellation();
        List<String> seen = new CopyOnWriteArrayList<>();
        scope.context().onCancelled(() -> {
            throw new IllegalStateException("first");
        });
        scope.context().onCancelled(() -> seen.add("second"));
        IllegalStateException thrown = assertThrows(IllegalStateException.class, scope::cancel);
        assertEquals("first", thrown.getMessage());
        assertEquals(List.of("second"), seen);
    }
}
