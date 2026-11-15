package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 让操作经过完整管道执行（RFC-0011 IOperationInvoker）。
 *
 * <p>不可变、线程安全：管道在构造时构建一次，此后对构建器的改动不影响它；同一个调用器可以被任意多个虚拟线程同时使用。
 * 每次调用从给定的上下文派生出自己的上下文，操作状态随它传递，嵌套与并发的调用互不干扰。
 */
public final class OperationInvoker {
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
        Result<Unit> outcome = pipeline.invoke(context.withValue(InvocationScope.KEY, scope));
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
            return Result.fail(Error.validation(OperationErrors.TYPE_MISMATCH, OperationMessages.TYPE_MISMATCH, Map.of("id", id,
                    "expected", descriptor.requestType().getSimpleName() + " -> " + descriptor.responseType().getSimpleName(),
                    "actual", request.getClass().getSimpleName() + " -> " + responseType.getSimpleName())));
        }
        InvocationScope scope = new InvocationScope(descriptor, OperationRequest.of(id), request);
        Result<Unit> outcome = pipeline.invoke(context.withValue(InvocationScope.KEY, scope));
        if (outcome.isFailed()) {
            return Result.fail(outcome.error().orElseThrow());
        }
        Result<?> result = scope.typedResult;
        if (result == null) {
            return Result.fail(noResponse(id));
        }
        return result.map(responseType::cast);
    }

    private static Error notFound(String id) {
        return Error.notFound(OperationErrors.NOT_FOUND, OperationMessages.UNKNOWN, Map.of("id", id));
    }

    private static Error noResponse(String id) {
        return Error.unexpected(OperationErrors.UNEXPECTED, OperationMessages.NO_RESPONSE, Map.of("id", id));
    }
}
