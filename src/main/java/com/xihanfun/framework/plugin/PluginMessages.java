package com.xihanfun.framework.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * 插件运行时的消息键与内置目录（RFC-0010）：10 种区域各一个语言文件 {@code Localization/<区域>.json}，随 SDK 打包。
 *
 * <p>前五个键与参数和参考实现的插件协议目录相同，宿主按它们翻译插件交回的错误；{@link #HOST_UNAVAILABLE} 与
 * {@link #EVENT_UNSUBSCRIBED} 是本 SDK 的键，宿主的目录里没有时显示为「键 (参数)」。目录不随握手发给宿主，
 * 免得与宿主自己的同名键冲突；运行时只用它填写协议 {@code Error.text}。
 */
public final class PluginMessages {
    /** 目录名。 */
    public static final String CATALOG_NAME = "plugins.protocol";

    /** 插件未就绪或正在停止；参数 {@code id}（清单 ID）与 {@code state}（生命周期状态名）。 */
    public static final String UNAVAILABLE = "plugins.process.unavailable";

    /** 配置、启动、停止或回调失败；参数 {@code id}、{@code phase}、{@code reason}。 */
    public static final String FAILED = "plugins.process.failed";

    /** 回调所需的权限未在清单声明或未被授予；参数 {@code id} 与 {@code permission}。 */
    public static final String CALLBACK_DENIED = "plugins.callback.denied";

    /** 回调或发布带了宿主没有交给插件的租户；参数 {@code id} 与 {@code tenant}。 */
    public static final String CALLBACK_TENANT_DENIED = "plugins.callback.tenant_denied";

    /** 回调的会话令牌被宿主拒绝；没有参数。 */
    public static final String SESSION_INVALID = "plugins.callback.session_invalid";

    /** 宿主回调端点不可达或本 SDK 连不了它的网络类型；参数 {@code id} 与 {@code reason}。 */
    public static final String HOST_UNAVAILABLE = "plugins.host.unavailable";

    /** 投递的消息没有以这种方式订阅、类型也匹配的处理器；参数 {@code id}、{@code topic}、{@code type}、{@code mode}。 */
    public static final String EVENT_UNSUBSCRIBED = "plugins.event.unsubscribed";

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
