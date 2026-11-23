package com.xihanfun.framework.operation;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.pipeline.Placement;
import com.xihanfun.framework.pipeline.Step;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
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
    /** 内置步骤的归属：内核清单（与 .NET 的内置中间件相同）。 */
    public static final String KERNEL_OWNER = "xihan.kernel.core";

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
     * <p>调用器在管道上加三个内置步骤，位置与 .NET 的内置中间件相同（RFC-0011 规则 10，ADR-0236）：都按归属 {@value #KERNEL_OWNER}
     * 放在所在阶段的 {@link Placement#BEGINNING}，与同位置的其他中间件按 RFC-0004 的规则排序（归属的序数序、同归属按登记顺序，
     * 内置步骤登记在使用者的中间件之后）：
     * <ul>
     *   <li>Observe：翻译异常，只接住排在它之后的中间件与处理器抛出的异常</li>
     *   <li>Resilience：进入时截止时间已到即 {@code KERNEL_TIMEOUT_001} + {@code kernel.invocation.deadline_before_start}</li>
     *   <li>Validate：原始调用在这里解码载荷，失败即 {@code KERNEL_VALIDATION_001}；类型化调用跳过</li>
     * </ul>
     *
     * @param registry 登记表
     * @param pipeline 管道构建器；只在构造时复制一次，不改动它
     * @throws FrameworkException 管道排序失败（{@code KERNEL_PIPELINE_00x}）
     */
    public OperationInvoker(OperationRegistry registry, PipelineBuilder pipeline) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline").copy()
                .useAt(new ObserveMiddleware(), PipelineStage.OBSERVE, Placement.BEGINNING, KERNEL_OWNER)
                .useAt(new DeadlineMiddleware(), PipelineStage.RESILIENCE, Placement.BEGINNING, KERNEL_OWNER)
                .useAt(new ValidationMiddleware(), PipelineStage.VALIDATE, Placement.BEGINNING, KERNEL_OWNER)
                .build(InvocationScope::dispatch);
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
     * 执行一个 XGI 请求：未登记返回 {@code KERNEL_NOTFOUND_001}（不进管道）；否则经完整管道执行，载荷在 Validate 阶段解码，
     * 不合法为 {@code KERNEL_VALIDATION_001}。管道成功结束之后才把响应编码成载荷，写不出（类型化的浮点成员是 NaN 或无穷、
     * 序列化不支持的值）时为 {@code KERNEL_UNEXPECTED_001} + {@code kernel.invocation.unhandled_exception}，没有载荷。
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
            return OperationResponse.failed(OperationErrors.notFound(request.id()));
        }
        InvocationScope scope = new InvocationScope(descriptor.get(), request, null);
        Result<Unit> outcome = run(context.withValue(InvocationScope.KEY, scope), request.id());
        if (outcome.isFailed()) {
            return OperationResponse.failed(outcome.error().orElseThrow());
        }
        if (!scope.responded) {
            // 管道成功结束却没有执行到终点：有中间件没调下游就返回了成功，成功的 XGI 响应必须带载荷
            return OperationResponse.failed(noResponse(request.id()));
        }
        // 管道结束之后才编码：中间件看到的是编码之前的结果；写不出时以 unhandled_exception 结束，不抛给调用方（RFC-0011 规则 10「编码」）
        return scope.descriptor.encode(scope.response).match(OperationResponse::owning, OperationResponse::failed);
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
     * @return 结果：未登记为 {@code KERNEL_NOTFOUND_001}，类型与登记不符为 {@code KERNEL_VALIDATION_002}（只带 {@code id}，ADR-0236）；
     *     进入管道前只做这两项核对，按这个顺序
     */
    public <Req, Res> Result<Res> invoke(Context context, String id, Req request, Class<Res> responseType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseType, "responseType");
        Optional<OperationDescriptor> found = registry.find(id);
        if (found.isEmpty()) {
            return Result.fail(OperationErrors.notFound(id));
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
     * 执行管道；异常由 Observe 阶段的内置步骤翻译，排在它之前的中间件抛出的异常原样交给调用方（RFC-0011 规则 10）。
     *
     * <p>有中间件把不是这次调用派生的上下文交给了下游时，终点找不到这次调用、不知道是哪个操作（{@link InvocationScope#DETACHED}）；
     * 这里知道，换成带 ID 的「操作未登记」，调用方拿到的错误总能填出 {@code Operation '{id}' is not registered.}。
     */
    private Result<Unit> run(Context context, String id) {
        Result<Unit> outcome = pipeline.invoke(context);
        // 按实例比较：这个错误只由终点在找不到调用状态时返回，途中的中间件原样传回结果或只换了一层结果（toUnit、map）时仍是同一个实例
        if (outcome.error().orElse(null) == InvocationScope.DETACHED) {
            return Result.fail(OperationErrors.notFound(id));
        }
        return outcome;
    }

    private static Error noResponse(String id) {
        return Error.unexpected(InvocationErrors.UNHANDLED_EXCEPTION, OperationMessages.NO_RESPONSE, Map.of("id", id));
    }
}
