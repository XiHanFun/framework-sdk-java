package com.xihanfun.framework.plugin;

/**
 * 插件运行时用到的错误码（RFC-0017「错误码」，{@code conventions/error-codes.md}）。
 *
 * <p>业务失败走 {@code Status.error}，gRPC 状态只表示传输失败（{@code invocation.md} §3）；这些码出现在插件交给宿主的结果里，
 * 也出现在宿主回调失败时 {@link Host} 返回的结果里。
 */
public final class PluginErrors {
    /** 清单不是进程外插件、缺 {@code entry}，或写了 {@code contributes.pipeline}：与宿主在发现时拒绝的条件相同。 */
    public static final String INVALID_SOURCE = "PLUGINS_VALIDATION_001";

    /** 握手声明与清单不一致：操作未在 {@code contributes.operations} 声明，订阅的主题、方式不合法或重复。 */
    public static final String HANDSHAKE_MISMATCH = "PLUGINS_VALIDATION_004";

    /** 回调被拒：会话令牌无效、所需权限未声明或未授予，或带了宿主没有交给插件的租户。 */
    public static final String CALLBACK_DENIED = "PLUGINS_DENIED_002";

    /** 插件未就绪或正在停止；也用于宿主回调端点不可达。 */
    public static final String UNAVAILABLE = "PLUGINS_UNAVAILABLE_001";

    /** 配置、启动、停止或回调时的未预期失败。 */
    public static final String UNEXPECTED = "PLUGINS_UNEXPECTED_001";

    private PluginErrors() {
    }
}
