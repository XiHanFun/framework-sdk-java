package com.xihanfun.framework.plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 一个事件订阅（{@code plugin.proto} {@code Subscription}）：主题、可选的 CloudEvents {@code type} 与订阅方式。
 *
 * <p>同一（主题，类型）对只能声明一次，不能以两种方式各声明一次（{@code handshake.md} §3 第 5 条）；运行时在监听之前核对。
 *
 * @param topic 主题，符合 RFC-0009 的主题文法
 * @param messageType 只投递该 {@code type} 的消息；空表示主题下全部
 * @param mode 订阅方式
 */
public record Subscription(String topic, Optional<String> messageType, SubscriptionMode mode) {
    /** 主题文法（RFC-0009），整串匹配。 */
    private static final Pattern TOPIC = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    /**
     * 校验并创建订阅。
     *
     * @param topic 主题
     * @param messageType 消息类型；给出时不能为空串
     * @param mode 订阅方式
     * @throws IllegalArgumentException 主题不合文法，或消息类型是空串
     */
    public Subscription {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(mode, "mode");
        if (!isTopic(topic)) {
            throw new IllegalArgumentException("The topic '" + topic + "' must match ^[a-z0-9][a-z0-9._-]{0,127}$.");
        }
        if (messageType.isPresent() && messageType.get().isEmpty()) {
            throw new IllegalArgumentException("A subscription message type must not be empty; leave it out to receive every type.");
        }
    }

    /**
     * 以竞争消费订阅主题下的全部消息。
     *
     * @param topic 主题
     * @return 订阅
     * @throws IllegalArgumentException 主题不合文法
     */
    public static Subscription of(String topic) {
        return new Subscription(topic, Optional.empty(), SubscriptionMode.COMPETING);
    }

    /**
     * 只订阅给定类型的消息。
     *
     * @param value CloudEvents {@code type}
     * @return 新的订阅
     */
    public Subscription withMessageType(String value) {
        return new Subscription(topic, Optional.of(Objects.requireNonNull(value, "value")), mode);
    }

    /**
     * 换订阅方式。
     *
     * @param value 订阅方式
     * @return 新的订阅
     */
    public Subscription withMode(SubscriptionMode value) {
        return new Subscription(topic, messageType, value);
    }

    /**
     * 判断主题是否符合 RFC-0009 的文法 {@code ^[a-z0-9][a-z0-9._-]{0,127}$}。
     *
     * @param topic 主题；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isTopic(String topic) {
        return topic != null && TOPIC.matcher(topic).matches();
    }

    /**
     * 判断一条投递是否交给这个订阅：主题相同、方式相同，类型为空或与信封的 {@code type} 相同（{@code invocation.md} §4）。
     */
    boolean accepts(Envelope envelope, SubscriptionMode delivered) {
        return topic.equals(envelope.topic()) && mode == delivered && messageType.map(envelope.type()::equals).orElse(true);
    }
}
