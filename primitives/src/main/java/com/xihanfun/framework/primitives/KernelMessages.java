package com.xihanfun.framework.primitives;

import java.util.Map;

/**
 * SDK 与宿主共用的内核消息的默认英文模板（RFC-0011「错误码」，ADR-0233）。
 *
 * <p>错误码、消息键、参数名与 en-US 模板逐字取自向量 {@code conformance/vectors/primitives/kernel-messages.json}（模板以 .NET 的
 * {@code Localization/en-US.json} 为准）。{@link MessageScope#resolve(String, Map)} 在进程默认的解析器翻译不出这些键时用它们，
 * 所以 {@link Error#message()} 与线上的 {@code message} 是可读的英文；宿主收到后仍按键用自己的目录再翻译。
 */
public final class KernelMessages {
    /** 读取了未初始化的结果；Java 没有结构体零值，本 SDK 不产生它。 */
    public static final String RESULT_UNINITIALIZED = "primitives.result.uninitialized";

    /** 区域名：这些模板是 en-US。 */
    public static final String LOCALE = MessageScope.DEFAULT_LOCALE;

    /** 消息键 → en-US 模板。 */
    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry(RESULT_UNINITIALIZED, "The result is uninitialized: a default value is not a valid result."),
            Map.entry("kernel.invocation.deadline_exceeded", "The invocation exceeded its deadline."),
            Map.entry("kernel.invocation.deadline_before_start", "The invocation exceeded its deadline before it started."),
            Map.entry("kernel.invocation.cancelled", "The invocation was canceled."),
            Map.entry("kernel.operation.unknown", "Operation '{id}' is not registered."),
            Map.entry("kernel.operation.invalid_payload", "The payload of operation '{id}' is not valid: {reason}"),
            Map.entry("kernel.operation.type_mismatch", "Operation '{id}' handles '{expected}', but '{actual}' was supplied."),
            Map.entry("kernel.operation.no_response", "Operation '{id}' completed without producing a response."),
            Map.entry("kernel.operation.duplicate", "Operation '{id}' is already registered by '{owner}'."),
            Map.entry("kernel.operation.registry_full", "The operation registry has reached its capacity of {capacity}."));

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
