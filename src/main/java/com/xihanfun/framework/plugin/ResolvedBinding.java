package com.xihanfun.framework.plugin;

import java.util.Objects;

/**
 * 宿主解析后的一个资源绑定（{@code plugin.proto} {@code Binding}）：只含清单 {@code bindings} 声明、且运营方授予了
 * {@code binding:<绑定名>} 的项（{@code lifecycle.md} §2）。
 *
 * @param name 绑定名
 * @param type 资源类型，如 {@code connection:relational}
 * @param value 连接串或地址
 */
public record ResolvedBinding(String name, String type, String value) {
    /**
     * 校验并创建绑定。
     *
     * @param name 绑定名
     * @param type 资源类型
     * @param value 连接串或地址
     */
    public ResolvedBinding {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }

    /**
     * 返回不含连接串的文本：连接串里常有密钥，不进日志。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "ResolvedBinding[name=" + name + ", type=" + type + "]";
    }
}
