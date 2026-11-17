package com.xihanfun.framework.plugin;

import static com.xihanfun.framework.plugin.RunningPlugin.details;
import static com.xihanfun.framework.plugin.RunningPlugin.invokeRequest;
import static com.xihanfun.framework.plugin.RunningPlugin.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.operation.OperationContract;
import com.xihanfun.framework.operation.OperationRegistry;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 停止与收尾（{@code lifecycle.md} §7.5、§7.7）：{@code Stop} 的宽限与取消、启动之前的 {@code Stop}、停止回调只调一次，
 * stdin 关闭的收尾与 3 秒时限。
 */
class PluginShutdownTest {
    @TempDir
    Path directory;

    /** 没有成员的请求。 */
    record Empty() {
    }

    /** 记下停止回调的原因与先后。 */
    static final class Recorder implements PluginHooks {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public Result<Unit> stop(Context context, String reason, Duration grace) {
            events.add("stop:" + reason);
            return Result.success();
        }
    }

    /** 一个等到上下文取消（或被放行）才返回的操作；进入时与看到取消时各记一笔。 */
    private static OperationRegistry waiting(CountDownLatch entered, CountDownLatch release, List<String> events) {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("samples.wait"), Empty.class, Empty.class, (context, request) -> {
            entered.countDown();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!context.isCancelled() && release.getCount() > 0 && System.nanoTime() < deadline) {
                    context.awaitCancellation(Duration.ofMillis(20));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            events.add(context.isCancelled() ? "cancelled" : "finished");
            return Result.ok(request);
        });
        return registry;
    }

    @Test
    void stopWaitsForInFlightCallsAndRejectsNewOnes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recorder hooks = new Recorder();
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(waiting(entered, release, hooks.events)).hooks(hooks));
        try {
            plugin.ready("{}");
            AtomicReference<PluginProto.InvokeResponse> slow = new AtomicReference<>();
            Thread caller = Thread.ofVirtual().start(() -> slow.set(plugin.stub().invoke(invokeRequest("samples.wait", "{}"))));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            AtomicReference<PluginProto.StopResponse> stopped = new AtomicReference<>();
            Thread stopper = Thread.ofVirtual().start(() -> stopped.set(plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(5).build())));
            // Stop 先进入 Draining，再等在途的调用；此时到达的调用以 unavailable 结束
            assertTrue(plugin.runtime.lifecycle.awaitState(LifecycleState.DRAINING, Duration.ofSeconds(10)));
            PluginProto.Status refused = plugin.stub().invoke(invokeRequest("samples.wait", "{}")).getStatus();
            assertEquals(object("id", "xihan.tests.sample", "state", "Draining"), details(refused.getError()));
            assertNull(stopped.get(), "Stop returns after the in-flight call");
            release.countDown();
            caller.join();
            stopper.join();
            assertTrue(slow.get().getStatus().getOk(), "the in-flight call completes");
            assertTrue(stopped.get().getStatus().getOk());
            assertEquals(List.of("finished", "stop:"), hooks.events, "the stop hook runs after the in-flight call, with the reason as it came");
        } finally {
            plugin.close();
        }
    }

    @Test
    void stopCancelsInFlightCallsWhenTheGraceRunsOut() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        Recorder hooks = new Recorder();
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(waiting(entered, never, hooks.events)).hooks(hooks));
        try {
            plugin.ready("{}");
            Thread.ofVirtual().start(() -> plugin.stub().invoke(invokeRequest("samples.wait", "{}")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long begin = System.nanoTime();
            PluginProto.StopResponse stopped = plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(1).setReason("restart").build());
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(stopped.getStatus().getOk());
            assertTrue(took >= 900 && took < 5000, "Stop waits the grace and no longer: " + took + " ms");
            // 宽限到了触发在途调用的取消信号、不再等，然后调停止回调
            assertTrue(hooks.events.contains("stop:restart"), hooks.events.toString());
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
            assertTrue(hooks.events.contains("cancelled"), hooks.events.toString());
        } finally {
            plugin.close();
        }
    }

    @Test
    void stopBeforeAStartAnswersOkWithoutTheStopHook() throws Exception {
        Recorder hooks = new Recorder();
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks));
        try {
            plugin.handshake();
            // grace_seconds 为 0 按 5 秒；启动回调没有成功过时不调停止回调（lifecycle.md §7.5）
            assertTrue(plugin.stub().stop(PluginProto.StopRequest.newBuilder().setReason("unload").build()).getStatus().getOk());
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(5)));
            assertEquals(List.of(), hooks.events);
            assertEquals(LifecycleState.RESOLVED, plugin.runtime.lifecycle.state(), "no transition is legal from Resolved to Stopped");
        } finally {
            plugin.close();
        }
    }

    @Test
    void theStopHookFailureOrThrowIsReportedAndThePluginStillStops() throws Exception {
        PluginHooks hooks = new PluginHooks() {
            @Override
            public Result<Unit> stop(Context context, String reason, Duration grace) {
                throw new IllegalArgumentException("cannot flush");
            }
        };
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks));
        try {
            plugin.ready("{}");
            PluginProto.StopResponse stopped = plugin.stub().stop(PluginProto.StopRequest.newBuilder().setGraceSeconds(2).build());
            assertEquals("PLUGINS_UNEXPECTED_001", stopped.getStatus().getError().getCode());
            assertEquals(object("id", "xihan.tests.sample", "phase", "stop", "reason", "IllegalArgumentException"), details(stopped.getStatus().getError()));
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
            assertEquals(LifecycleState.STOPPED, plugin.runtime.lifecycle.state());
        } finally {
            plugin.close();
        }
    }

    @Test
    void stdinCloseDrainsCancelsInFlightCallsAndCallsTheStopHook() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        Recorder hooks = new Recorder();
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().operations(waiting(entered, never, hooks.events)).hooks(hooks));
        try {
            plugin.ready("{}");
            Thread.ofVirtual().start(() -> plugin.stub().invoke(invokeRequest("samples.wait", "{}")));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            long begin = System.nanoTime();
            plugin.closeStdin();
            // 可服务的插件进入 Draining，立即触发在途调用的取消信号并等它们，再调停止回调（reason 为 shutdown）
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 3000, "the in-flight call is cancelled at once");
            assertEquals(List.of("cancelled", "stop:shutdown"), hooks.events);
            assertEquals(LifecycleState.STOPPED, plugin.runtime.lifecycle.state());
            assertTrue(plugin.logs().contains("stdin closed"), plugin.logs());
        } finally {
            plugin.close();
        }
    }

    @Test
    void stdinCloseFinishesWithinThreeSecondsEvenIfTheStopHookHangs() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean called = new AtomicBoolean();
        PluginHooks hooks = new PluginHooks() {
            @Override
            public Result<Unit> stop(Context context, String reason, Duration grace) {
                called.set(true);
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Result.success();
            }
        };
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks));
        try {
            plugin.ready("{}");
            long begin = System.nanoTime();
            plugin.closeStdin();
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(10)));
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(called.get());
            assertTrue(took >= 2500 && took < 4500, "the shutdown is bounded by 3 seconds: " + took + " ms");
        } finally {
            release.countDown();
            plugin.close();
        }
    }

    @Test
    void stdinCloseBeforeAStartDoesNotCallTheStopHook() throws Exception {
        Recorder hooks = new Recorder();
        RunningPlugin plugin = RunningPlugin.start(directory, PluginOptions.builder().hooks(hooks));
        try {
            plugin.handshake();
            assertTrue(plugin.configure("{}").getStatus().getOk());
            plugin.closeStdin();
            assertTrue(plugin.runtime.awaitStopped(Duration.ofSeconds(5)));
            assertEquals(List.of(), hooks.events);
            assertEquals(LifecycleState.CONFIGURED, plugin.runtime.lifecycle.state(), "only a servable plugin moves to Draining");
        } finally {
            plugin.close();
        }
    }
}
