package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * 让操作经过完整管道执行（RFC-0011 IOperationInvoker）。
 *
 * <p>不可变、线程安全：管道在构造时构建一次，此后对构建器的改动不影响它；同一个调用器可以被任意多个虚拟线程同时使用。
 * 每次调用从给定的上下文派生出自己的上下文，操作状态随它传递，嵌套与并发的调用互不干扰。
 */
public final class OperationInvoker {
    /** 未处理的异常写到这里：JDK 的平台日志，缺省经 java.util.logging 写到 stderr，应用可以换成自己的日志实现。 */
    private static final System.Logger LOGGER = System.getLogger(OperationInvoker.class.getName());

    private final OperationRegistry registry;
    private final Step pipeline;

    /**
     * 用登记表与空管道创建调用器。
     *
     * @param registry 登记表
     */
    public OperationInvoker(OperationRegistry registry) {
        this(registry, new PipelineBuilder());
    }

    /**
     * 用登记表与管道创建调用器：管道的终点在 handle 阶段之后分派当前操作。
     *
     * @param registry 登记表
     * @param pipeline 管道构建器；只在构造时读一次
     * @throws FrameworkException 管道排序失败（{@code KERNEL_PIPELINE_00x}）
     */
    public OperationInvoker(OperationRegistry registry, PipelineBuilder pipeline) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline").build(InvocationScope::dispatch);
    }

    /**
     * 返回登记表。
     *
     * @return 登记表
     */
    public OperationRegistry registry() {
        return registry;
    }

    /**
     * 执行一个 XGI 请求：未登记返回 {@code KERNEL_NOTFOUND_001}（不进管道）；否则经完整管道执行，载荷不合法为
     * {@code KERNEL_VALIDATION_001}，成功时带响应类型的 JSON。
     *
     * @param context 调用上下文
     * @param request XGI 请求
     * @return XGI 响应
     */
    public OperationResponse invoke(Context context, OperationRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");
        Optional<OperationDescriptor> descriptor = registry.find(request.id());
        if (descriptor.isEmpty()) {
            return OperationResponse.failed(notFound(request.id()));
        }
        InvocationScope scope = new InvocationScope(descriptor.get(), request, null);
        Result<Unit> outcome = run(context.withValue(InvocationScope.KEY, scope), request.id());
        if (outcome.isFailed()) {
            return OperationResponse.failed(outcome.error().orElseThrow());
        }
        byte[] payload = scope.responsePayload;
        if (payload == null) {
            // 管道成功结束却没有执行到终点：有中间件没调下游就返回了成功，成功的 XGI 响应必须带载荷
            return OperationResponse.failed(noResponse(request.id()));
        }
        return OperationResponse.owning(payload);
    }

    /**
     * 类型化入口：不经 JSON，直接以请求对象经完整管道执行。
     *
     * @param context 调用上下文
     * @param id 操作 ID
     * @param request 请求对象，必须是登记的请求类型的实例
     * @param responseType 期望的响应类型，必须能接收登记的响应类型
     * @param <Req> 请求类型
     * @param <Res> 响应类型
     * @return 结果：未登记为 {@code KERNEL_NOTFOUND_001}，类型与登记不符为 {@code KERNEL_VALIDATION_002}
     */
    public <Req, Res> Result<Res> invoke(Context context, String id, Req request, Class<Res> responseType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Optional<OperationDescriptor> found = registry.find(id);
        if (found.isEmpty()) {
            return Result.fail(notFound(id));
        }
        OperationDescriptor descriptor = found.get();
        // 请求按实例判断、响应按可赋值判断：登记的类型的子类实例与它的父类型都算相符，擦除后的泛型参数不参与判断
        if (!descriptor.requestType().isInstance(request) || !responseType.isAssignableFrom(descriptor.responseType())) {
            // 只带 id：类型名是实现语言的，各语言写不出同样的参数（ADR-0236）
            return Result.fail(Error.validation(OperationErrors.TYPE_MISMATCH, OperationMessages.TYPE_MISMATCH, Map.of("id", id)));
        }
        InvocationScope scope = new InvocationScope(descriptor, OperationRequest.of(id), request);
        Result<Unit> outcome = run(context.withValue(InvocationScope.KEY, scope), id);
        if (outcome.isFailed()) {
            return Result.fail(outcome.error().orElseThrow());
        }
        Result<?> result = scope.typedResult;
        if (result == null) {
            return Result.fail(noResponse(id));
        }
        return result.map(responseType::cast);
    }

    /**
     * 执行管道并翻译抛出的异常（RFC-0011 规则 5、10）：SDK 没有 Observe 阶段，由调用器承担。
     *
     * <p>{@link Exception} 一律翻译为 {@code KERNEL_UNEXPECTED_001} + {@code kernel.invocation.unhandled_exception}，异常本身写进
     * {@code System.Logger}，不上线；{@link java.lang.Error}（虚拟机错误、断言失败）不翻译、照常抛出。
     */
    private Result<Unit> run(Context context, String id) {
        // SDK 没有截止时间中间件：调用器在第一个中间件之前按时钟核对，已到即结束、管道不执行（RFC-0011 规则 10）
        Optional<Instant> deadline = context.deadline();
        if (deadline.isPresent() && !Instant.now().isBefore(deadline.get())) {
            return Result.fail(InvocationErrors.deadlineBeforeStart());
        }
        try {
            return pipeline.invoke(context);
        } catch (Exception thrown) {
            return Result.fail(translate(context, id, thrown));
        }
    }

    /**
     * 取消不按异常翻译：异常是取消引起的、上下文也确实取消了，按令牌归因为超时或取消（RFC-0003 第 1 条）；
     * 框架异常的错误放进 inner；其余异常没有内部错误。
     */
    private static Error translate(Context context, String id, Exception thrown) {
        if (isCancellation(thrown)) {
            if (thrown instanceof InterruptedException) {
                // 吞掉中断异常要恢复中断标志，调用线程之后的等待仍能看到中断
                Thread.currentThread().interrupt();
            }
            Optional<Error> cancellation = context.cancellation();
            if (cancellation.isPresent()) {
                return cancellation.get();
            }
        }
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

    private static Error notFound(String id) {
        return Error.notFound(OperationErrors.NOT_FOUND, OperationMessages.UNKNOWN, Map.of("id", id));
    }

    private static Error noResponse(String id) {
        return Error.unexpected(OperationErrors.UNEXPECTED, OperationMessages.NO_RESPONSE, Map.of("id", id));
    }
}
