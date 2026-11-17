package com.xihanfun.framework.primitives;

import java.util.Map;
import java.util.Optional;

/**
 * 按区域把消息键解析成已格式化的文案（RFC-0010 IMessageResolver）。
 *
 * <p>实现必须线程安全：{@link Error#message()} 会在任意线程上经 {@link MessageScope} 调用它。
 */
@FunctionalInterface
public interface MessageResolver {
    /**
     * 解析消息键。
     *
     * @param key 消息键
     * @param locale 区域名（BCP 47），如 {@code zh-CN}
     * @param arguments 模板参数
     * @return 文案；没有该键、没有可用的区域或占位符缺参数时为空
     */
    Optional<String> resolve(String key, String locale, Map<String, ?> arguments);
}
