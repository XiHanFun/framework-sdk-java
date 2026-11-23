package com.xihanfun.framework.primitives;

import java.util.Map;

/**
 * SDK 与宿主共用的内核消息：消息键常量与默认英文模板（RFC-0011「错误码」，ADR-0233）。
 *
 * <p>错误码、消息键、参数名与 en-US 模板逐字取自向量 {@code conformance/vectors/primitives/kernel-messages.json}（模板以 .NET 的
 * {@code Localization/en-US.json} 为准），含清单读取的错误（{@code kernel.manifest.*}、{@code primitives.manifest.*}，ADR-0236）。
 * {@link MessageScope#resolve(String, Map)} 在进程默认的解析器翻译不出这些键时用它们，
 * 所以 {@link Error#message()} 与线上的 {@code message} 是可读的英文；宿主收到后仍按键用自己的目录再翻译。
 *
 * <p>这些键只在这里写一遍：各包的消息键常量（{@code InvocationMessages}、{@code OperationMessages}、{@code ManifestJson} 里的键）
 * 都引用这里的常量，名字照 .NET 的 {@code KernelMessages} 与 {@code ManifestMessages}。
 */
public final class KernelMessages {
    /** 读取了未初始化的结果；Java 没有结构体零值，本 SDK 不产生它。 */
    public static final String RESULT_UNINITIALIZED = "primitives.result.uninitialized";

    /** 调用超过截止时间：截止时间在调用途中到达；没有参数。 */
    public static final String DEADLINE_EXCEEDED = "kernel.invocation.deadline_exceeded";

    /** 调用开始时截止时间已过；没有参数。 */
    public static final String DEADLINE_BEFORE_START = "kernel.invocation.deadline_before_start";

    /** 调用在截止时间到达之前被调用方取消；没有参数。 */
    public static final String CANCELLED = "kernel.invocation.cancelled";

    /** 处理器或中间件抛出了未处理的异常；没有参数。 */
    public static final String UNHANDLED_EXCEPTION = "kernel.invocation.unhandled_exception";

    /** 操作未登记；参数 {@code id}。 */
    public static final String UNKNOWN_OPERATION = "kernel.operation.unknown";

    /** 操作载荷不合法；参数 {@code id} 与 {@code reason}（出错位置的 JSON 路径）。 */
    public static final String INVALID_OPERATION_PAYLOAD = "kernel.operation.invalid_payload";

    /** 类型化调用的请求或响应类型与登记不符；参数 {@code id}。 */
    public static final String OPERATION_TYPE_MISMATCH = "kernel.operation.type_mismatch";

    /** 管道成功结束却没有响应；参数 {@code id}。 */
    public static final String OPERATION_NO_RESPONSE = "kernel.operation.no_response";

    /** 操作 ID 重复登记；参数 {@code id} 与已登记者的 {@code owner}。 */
    public static final String DUPLICATE_OPERATION = "kernel.operation.duplicate";

    /** 操作登记表已满；参数 {@code capacity}。 */
    public static final String OPERATION_REGISTRY_FULL = "kernel.operation.registry_full";

    /** 清单是字面量 {@code null}；没有参数。 */
    public static final String MANIFEST_EMPTY = "kernel.manifest.empty";

    /** 清单文件不存在；参数 {@code path}。 */
    public static final String MANIFEST_FILE_MISSING = "kernel.manifest.file_missing";

    /** 清单不合 Schema；参数 {@code reason}（第一处问题的路径）。 */
    public static final String MANIFEST_INVALID = "kernel.manifest.invalid";

    /** 清单不是 JSON 或有重复成员；参数 {@code reason}。 */
    public static final String MANIFEST_INVALID_JSON = "kernel.manifest.invalid_json";

    /** 清单的 {@code bindings} 里名字重复；参数 {@code id}（清单 ID）。 */
    public static final String MANIFEST_DUPLICATE_BINDINGS = "primitives.manifest.duplicate_bindings";

    /** 清单的 {@code provides} 里能力 ID 重复；参数 {@code id}（清单 ID）。 */
    public static final String MANIFEST_DUPLICATE_PROVIDES = "primitives.manifest.duplicate_provides";

    /** 清单的 {@code requires} 里能力 ID 重复；参数 {@code id}（清单 ID）。 */
    public static final String MANIFEST_DUPLICATE_REQUIRES = "primitives.manifest.duplicate_requires";

    /** 非插件清单写了 {@code isolation} 或 {@code permissions}；参数 {@code id}（清单 ID）。 */
    public static final String MANIFEST_ISOLATION_NOT_ALLOWED = "primitives.manifest.isolation_not_allowed";

    /** 插件清单没有 {@code isolation}；参数 {@code id}（清单 ID）。 */
    public static final String MANIFEST_ISOLATION_REQUIRED = "primitives.manifest.isolation_required";

    /** 区域名：这些模板是 en-US。 */
    public static final String LOCALE = MessageScope.DEFAULT_LOCALE;

    /** 消息键 → en-US 模板。 */
    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry(RESULT_UNINITIALIZED, "The result is uninitialized: a default value is not a valid result."),
            Map.entry(DEADLINE_EXCEEDED, "The invocation exceeded its deadline."),
            Map.entry(DEADLINE_BEFORE_START, "The invocation exceeded its deadline before it started."),
            Map.entry(CANCELLED, "The invocation was canceled."),
            Map.entry(UNHANDLED_EXCEPTION, "An unhandled exception occurred during the invocation."),
            Map.entry(UNKNOWN_OPERATION, "Operation '{id}' is not registered."),
            Map.entry(INVALID_OPERATION_PAYLOAD, "The payload of operation '{id}' is not valid: {reason}"),
            Map.entry(OPERATION_TYPE_MISMATCH, "Operation '{id}' was invoked with request or response types that do not match its registration."),
            Map.entry(OPERATION_NO_RESPONSE, "Operation '{id}' completed without producing a response."),
            Map.entry(DUPLICATE_OPERATION, "Operation '{id}' is already registered by '{owner}'."),
            Map.entry(OPERATION_REGISTRY_FULL, "The operation registry has reached its capacity of {capacity}."),
            // 清单读取的错误（RFC-0002 解析规则 2，ADR-0236）：读取失败时错误码都是 KERNEL_CAPABILITY_006
            Map.entry(MANIFEST_EMPTY, "The manifest is empty."),
            Map.entry(MANIFEST_FILE_MISSING, "Manifest file '{path}' does not exist."),
            Map.entry(MANIFEST_INVALID, "The manifest is not valid: {reason}"),
            Map.entry(MANIFEST_INVALID_JSON, "The manifest is not valid JSON: {reason}"),
            Map.entry(MANIFEST_DUPLICATE_BINDINGS, "Manifest '{id}' declares the same binding name more than once in 'bindings'."),
            Map.entry(MANIFEST_DUPLICATE_PROVIDES, "Manifest '{id}' declares the same capability ID more than once in 'provides'."),
            Map.entry(MANIFEST_DUPLICATE_REQUIRES, "Manifest '{id}' declares the same capability ID more than once in 'requires'."),
            Map.entry(MANIFEST_ISOLATION_NOT_ALLOWED, "Manifest '{id}' is not a plugin and must not declare 'isolation' or 'permissions'."),
            Map.entry(MANIFEST_ISOLATION_REQUIRED, "Plugin manifest '{id}' must declare 'isolation'."));

    /** 同一份模板的目录形式。 */
    private static final MessageCatalog CATALOG = MessageCatalog.of(Map.of(LOCALE, TEMPLATES));

    private KernelMessages() {
    }

    /**
     * 返回默认英文模板。
     *
     * @return 只读的消息键 → 模板
     */
    public static Map<String, String> templates() {
        return TEMPLATES;
    }

    /**
     * 返回默认英文模板的目录，便于合并进应用或插件自己的目录。
     *
     * @return 只有 en-US 的目录
     */
    public static MessageCatalog catalog() {
        return CATALOG;
    }
}
