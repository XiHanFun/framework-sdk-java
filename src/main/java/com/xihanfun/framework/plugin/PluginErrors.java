package com.xihanfun.framework.plugin;

import com.xihanfun.framework.primitives.Error;
import java.util.Map;
import java.util.Objects;

/**
 * 插件运行时用到的错误码（RFC-0017「错误码」，{@code conventions/error-codes.md}），以及不止一处要创建的错误。
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

    /** 插件不在可服务状态；也用于回调连不上宿主或没有可用的回调端点。 */
    public static final String UNAVAILABLE = "PLUGINS_UNAVAILABLE_001";

    /** 生命周期或健康回调抛出、配置节不是 JSON 对象、对方写来的错误不合法、回调的 gRPC 状态没有对应的错误。 */
    public static final String UNEXPECTED = "PLUGINS_UNEXPECTED_001";

    private PluginErrors() {
    }

    /**
     * 创建「回调所需的权限未声明或未授予」的错误（{@code invocation.md} §5）：发出回调之前的本地核对与宿主以
     * {@code PermissionDenied} 拒绝回调时给出同一个错误。
     *
     * @param pluginId 插件的清单 ID，写进参数 {@code id}
     * @param permission 回调所需的权限，如 {@value Host#PERMISSION_INVOKE}
     * @return {@code PLUGINS_DENIED_002}，消息键 {@value PluginMessages#CALLBACK_DENIED}，参数 {@code id} 与 {@code permission}
     */
    public static Error callbackDenied(String pluginId, String permission) {
        return Error.denied(CALLBACK_DENIED, PluginMessages.CALLBACK_DENIED,
                Map.of("id", Objects.requireNonNull(pluginId, "pluginId"), "permission", Objects.requireNonNull(permission, "permission")));
    }
}
