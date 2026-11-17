package com.xihanfun.framework.primitives;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息模板的参数表（RFC-0010）。
 */
public final class MessageArguments {
    private MessageArguments() {
    }

    /**
     * 把位置参数转成以 {@code "0"}、{@code "1"}… 为键的只读参数表，供 {@code {0}} 形式的占位符使用。
     *
     * @param values 位置参数；元素可以为 {@code null}
     * @return 只读参数表
     */
    public static Map<String, Object> positional(Object... values) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index++) {
            arguments.put(Integer.toString(index), values[index]);
        }
        return Collections.unmodifiableMap(arguments);
    }
}
