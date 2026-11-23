package com.xihanfun.framework.plugin;

import com.xihanfun.framework.internal.WireNames;
import java.util.Optional;

/**
 * 订阅方式（RFC-0009「订阅方式」）：宿主按它向消息代理订阅主题，投递时在 {@code HandleEventRequest.mode} 里带上这次投递的方式。
 */
public enum SubscriptionMode {
    /** 竞争消费：订阅了主题的宿主实例里只有一个把消息交给插件；协议里的空值同它。 */
    COMPETING("competing"),
    /** 广播：每个在线的宿主实例都把消息交给自己的插件实例，尽力而为、不进死信。 */
    BROADCAST("broadcast");

    /** 协议里的名字。 */
    private final String wireName;

    SubscriptionMode(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按协议里的名字解析：空串同 {@link #COMPETING}（{@code invocation.md} §4）。
     *
     * @param name 名字
     * @return 方式；名字未知时为空
     */
    public static Optional<SubscriptionMode> parse(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.of(COMPETING);
        }
        return WireNames.find(values(), SubscriptionMode::wireName, name);
    }

    /**
     * 返回协议里的名字。
     *
     * @return 名字，如 {@code competing}
     */
    public String wireName() {
        return wireName;
    }
}
