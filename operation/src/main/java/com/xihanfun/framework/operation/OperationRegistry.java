package com.xihanfun.framework.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.primitives.FrameworkException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 操作登记表（RFC-0011 OperationRegistry）：ID 唯一，容量 {@value #CAPACITY}。
 *
 * <p>线程安全：登记在锁里核对重复与容量；按 ID 查找不取锁，看到的每个操作要么已登记、要么还没有。
 */
public final class OperationRegistry {
    /** 登记表的容量。 */
    public static final int CAPACITY = 10_000;

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, OperationDescriptor> descriptors = new ConcurrentHashMap<>();

    /** 只保护登记：重复与容量的判定和写入在同一把锁里完成。 */
    private final ReentrantLock registration = new ReentrantLock();

    /**
     * 创建空的登记表，载荷用 {@link OperationJson#newMapper()} 编解码。
     */
    public OperationRegistry() {
        this(OperationJson.newMapper());
    }

    /**
     * 创建空的登记表，载荷用给定的 {@link ObjectMapper} 编解码；它在登记表的生命期内不应再被修改配置。
     *
     * @param mapper 编解码请求与响应的 ObjectMapper
     */
    public OperationRegistry(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 登记一个操作，归属 {@value PipelineBuilder#APP_OWNER}，没有 Schema。
     *
     * @param contract 契约
     * @param requestType 请求类型
     * @param responseType 响应类型
     * @param handler 处理器
     * @param <Req> 请求类型
     * @param <Res> 响应类型
     * @return 登记的描述
     * @throws FrameworkException ID 重复（{@code KERNEL_OPERATION_001}）或登记表已满（{@code KERNEL_OPERATION_002}）
     */
    public <Req, Res> OperationDescriptor add(OperationContract contract, Class<Req> requestType, Class<Res> responseType,
            OperationHandler<Req, Res> handler) {
        return register(contract, requestType, responseType, handler, null, PipelineBuilder.APP_OWNER);
    }

    /**
     * 登记一个操作，没有 Schema。
     *
     * @param contract 契约
     * @param requestType 请求类型
     * @param responseType 响应类型
     * @param handler 处理器
     * @param owner 归属：清单 ID 或 {@value PipelineBuilder#APP_OWNER}
     * @param <Req> 请求类型
     * @param <Res> 响应类型
     * @return 登记的描述
     * @throws FrameworkException ID 重复（{@code KERNEL_OPERATION_001}）或登记表已满（{@code KERNEL_OPERATION_002}）
     */
    public <Req, Res> OperationDescriptor add(OperationContract contract, Class<Req> requestType, Class<Res> responseType,
            OperationHandler<Req, Res> handler, String owner) {
        return register(contract, requestType, responseType, handler, null, owner);
    }

    /**
     * 登记一个带 Schema 的操作。
     *
     * @param contract 契约
     * @param requestType 请求类型
     * @param responseType 响应类型
     * @param handler 处理器
     * @param schema 请求与响应的 JSON Schema
     * @param owner 归属：清单 ID 或 {@value PipelineBuilder#APP_OWNER}
     * @param <Req> 请求类型
     * @param <Res> 响应类型
     * @return 登记的描述
     * @throws FrameworkException ID 重复（{@code KERNEL_OPERATION_001}）或登记表已满（{@code KERNEL_OPERATION_002}）
     */
    public <Req, Res> OperationDescriptor add(OperationContract contract, Class<Req> requestType, Class<Res> responseType,
            OperationHandler<Req, Res> handler, OperationSchema schema, String owner) {
        return register(contract, requestType, responseType, handler, Objects.requireNonNull(schema, "schema"), owner);
    }

    /**
     * 按 ID 查找操作。
     *
     * @param id 操作 ID
     * @return 描述；没有登记时为空
     */
    public Optional<OperationDescriptor> find(String id) {
        return Optional.ofNullable(descriptors.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * 按 ID 升序列出全部操作。
     *
     * @return 只读的快照
     */
    public List<OperationDescriptor> list() {
        List<OperationDescriptor> snapshot = new ArrayList<>(descriptors.values());
        snapshot.sort(Comparator.comparing(descriptor -> descriptor.contract().id()));
        return List.copyOf(snapshot);
    }

    /**
     * 返回已登记的操作数。
     *
     * @return 操作数
     */
    public int size() {
        return descriptors.size();
    }

    /**
     * 返回编解码载荷的 ObjectMapper。
     */
    ObjectMapper mapper() {
        return mapper;
    }

    private <Req, Res> OperationDescriptor register(OperationContract contract, Class<Req> requestType, Class<Res> responseType,
            OperationHandler<Req, Res> handler, OperationSchema schema, String owner) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(responseType, "responseType");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(owner, "owner");
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("An operation owner must not be empty; use PipelineBuilder.APP_OWNER for the application.");
        }
        OperationDescriptor descriptor = OperationDescriptor.create(contract, schema, owner, requestType, responseType, handler, mapper);
        registration.lock();
        try {
            // 先判重复、再判容量：重复的 ID 不论表满不满都报重复
            if (descriptors.containsKey(contract.id())) {
                throw new FrameworkException(OperationErrors.DUPLICATE, "The operation '" + contract.id() + "' is already registered.");
            }
            if (descriptors.size() >= CAPACITY) {
                throw new FrameworkException(OperationErrors.REGISTRY_FULL, "The operation registry is full (" + CAPACITY + " operations).");
            }
            descriptors.put(contract.id(), descriptor);
        } finally {
            registration.unlock();
        }
        return descriptor;
    }
}
