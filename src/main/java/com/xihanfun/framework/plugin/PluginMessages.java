package com.xihanfun.framework.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * 插件运行时的消息键与内置目录（RFC-0010，{@code invocation.md} §8）：10 种区域各一个语言文件 {@code Localization/<区域>.json}，随 SDK 打包。
 *
 * <p>这些键、参数与错误码是规范定下的、SDK 运行时与宿主共用的键（向量 {@code plugin-messages.json}），参数就是错误的 {@code details}，
 * 不另加成员；{@code id} 一律是插件的清单 ID。宿主有这些键的目录，SDK 不随握手发送它们（{@code handshake.md} §3 第 6 条），
 * 带这些键的错误交给宿主时 {@code text} 留空、由宿主翻译成调用方的语言。本目录供插件在本地渲染，例如写日志或交给不经宿主的调用方。
 *
 * <p>另有一个不在 {@code plugin-messages.json} 里的键 {@value #ENVELOPE_INVALID}：它是消息子系统的键（RFC-0009「信封」），
 * {@code Host.publish} 在本地拒绝编不成 UTF-8 的 {@code data} 时用它（{@code invocation.md} §5，ADR-0315），模板与宿主
 * 消息目录里的同一个键逐字相同，收进本目录是为了插件在本地也能渲染这个错误。
 */
public final class PluginMessages {
    /** 目录名。 */
    public static final String CATALOG_NAME = "plugins.protocol";

    /** 不在可服务状态（{@code Ready}、{@code Degraded}）时到达的调用与投递；参数 {@code id} 与 {@code state}（生命周期状态名），错误码 {@code PLUGINS_UNAVAILABLE_001}。 */
    public static final String UNAVAILABLE = "plugins.process.unavailable";

    /** 生命周期或健康回调抛出、配置节不是 JSON 对象、对方写来的错误不合法、没有对应的 gRPC 状态；参数 {@code id}、{@code phase}、{@code reason}，错误码 {@code PLUGINS_UNEXPECTED_001}。 */
    public static final String FAILED = "plugins.process.failed";

    /** 回调所需的权限未在清单声明或未被授予；参数 {@code id} 与 {@code permission}，错误码 {@code PLUGINS_DENIED_002}。 */
    public static final String CALLBACK_DENIED = "plugins.callback.denied";

    /** 回调带了不属于在途调用的租户；参数 {@code id} 与 {@code tenant}，错误码 {@code PLUGINS_DENIED_002}。 */
    public static final String CALLBACK_TENANT_DENIED = "plugins.callback.tenant_denied";

    /** 宿主拒绝了回调的会话令牌；没有参数，错误码 {@code PLUGINS_DENIED_002}。 */
    public static final String SESSION_INVALID = "plugins.callback.session_invalid";

    /** 回调连不上宿主，或没有可用的回调端点；参数 {@code id} 与 {@code reason}，错误码 {@code PLUGINS_UNAVAILABLE_001}。 */
    public static final String HOST_UNAVAILABLE = "plugins.host.unavailable";

    /** 投递找不到（主题，方式，类型）匹配的订阅；参数 {@code id}、{@code topic}、{@code type}、{@code mode}，错误码 {@code KERNEL_NOTFOUND_001}。 */
    public static final String EVENT_UNSUBSCRIBED = "plugins.event.unsubscribed";

    /** 发布的信封在本地就不合法（以文本设置的 {@code data} 编不成 UTF-8）；参数 {@code reason}，错误码 {@code MESSAGING_VALIDATION_002}（RFC-0009「信封」）。 */
    public static final String ENVELOPE_INVALID = "messaging.envelope.invalid";

    /** 内置目录；类首次使用时读语言文件。 */
    private static final PluginCatalog CATALOG = load();

    private PluginMessages() {
    }

    /**
     * 返回内置目录。
     *
     * @return 10 种区域的目录
     */
    public static PluginCatalog catalog() {
        return CATALOG;
    }

    /**
     * 返回默认英文模板。
     *
     * @return 只读的消息键 → en-US 模板
     */
    public static Map<String, String> templates() {
        return CATALOG.locales().get("en-US");
    }

    private static PluginCatalog load() {
        try {
            return PluginCatalog.load(CATALOG_NAME, locale -> PluginMessages.class.getResourceAsStream("Localization/" + locale + ".json"));
        } catch (IOException missing) {
            // 语言文件随 SDK 打包，读不出说明构件损坏
            throw new UncheckedIOException("The built-in plugin message catalog cannot be read.", missing);
        }
    }
}
