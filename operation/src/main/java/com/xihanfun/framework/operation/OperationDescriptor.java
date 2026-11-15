package com.xihanfun.framework.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
import java.util.Optional;

/**
 * 登记表里的一个操作：契约、可选的 Schema、归属、请求与响应类型，以及执行入口。
 *
 * <p>不可变、线程安全；执行入口只在本包里经调用器使用，让每次调用都经过完整管道。
 */
public final class OperationDescriptor {
    private final OperationContract contract;

    /** Schema；没有为 {@code null}。 */
    private final OperationSchema schema;

    private final String owner;
    private final Class<?> requestType;
    private final Class<?> responseType;
    private final Executor<?, ?> executor;

    private <Req, Res> OperationDescriptor(OperationContract contract, OperationSchema schema, String owner, Executor<Req, Res> executor) {
        this.contract = contract;
        this.schema = schema;
        this.owner = owner;
        this.requestType = executor.requestType();
        this.responseType = executor.responseType();
        this.executor = executor;
    }

    static <Req, Res> OperationDescriptor create(OperationContract contract, OperationSchema schema, String owner, Class<Req> requestType,
            Class<Res> responseType, OperationHandler<Req, Res> handler, ObjectMapper mapper) {
        return new OperationDescriptor(contract, schema, owner, new Executor<>(contract.id(), requestType, responseType, handler, mapper));
    }

    /**
     * 返回契约。
     *
     * @return 契约
     */
    public OperationContract contract() {
        return contract;
    }

    /**
     * 返回请求与响应的 Schema。
     *
     * @return Schema；没有时为空
     */
    public Optional<OperationSchema> schema() {
        return Optional.ofNullable(schema);
    }

    /**
     * 返回归属：清单 ID 或 {@value com.xihanfun.framework.pipeline.PipelineBuilder#APP_OWNER}。
     *
     * @return 归属
     */
    public String owner() {
        return owner;
    }

    /**
     * 返回请求类型。
     *
     * @return 请求类型
     */
    public Class<?> requestType() {
        return requestType;
    }

    /**
     * 返回响应类型。
     *
     * @return 响应类型
     */
    public Class<?> responseType() {
        return responseType;
    }

    /**
     * 解码载荷、执行、编码响应。
     */
    OperationResponse execute(Context context, byte[] payload) {
        return executor.execute(context, payload);
    }

    /**
     * 以请求对象执行，不经 JSON；调用方已核对类型。
     */
    Result<?> executeTyped(Context context, Object request) {
        return executor.executeTyped(context, request);
    }

    /**
     * 返回操作 ID。
     *
     * @return 操作 ID
     */
    @Override
    public String toString() {
        return contract.id();
    }

    /** 不经反射调用处理器的执行入口：类型参数在登记时就确定了。 */
    private record Executor<Req, Res>(String id, Class<Req> requestType, Class<Res> responseType, OperationHandler<Req, Res> handler,
            ObjectMapper mapper) {
        OperationResponse execute(Context context, byte[] payload) {
            Result<Req> request = OperationJson.decode(mapper, id, payload, requestType);
            if (request.isFailed()) {
                return OperationResponse.failed(request.error().orElseThrow());
            }
            Result<Res> result = handle(context, request.value().orElseThrow());
            if (result.isFailed()) {
                return OperationResponse.failed(result.error().orElseThrow());
            }
            Result<byte[]> encoded = OperationJson.encode(mapper, id, result.value().orElseThrow());
            return encoded.match(OperationResponse::owning, OperationResponse::failed);
        }

        Result<Res> executeTyped(Context context, Object request) {
            return handle(context, requestType.cast(request));
        }

        private Result<Res> handle(Context context, Req request) {
            Result<Res> result = handler.handle(context, request);
            if (result == null) {
                throw new IllegalStateException("The handler of '" + id + "' returned no result.");
            }
            return result;
        }
    }
}
