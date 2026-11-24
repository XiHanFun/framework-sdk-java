package com.xihanfun.framework.plugin;

import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 宿主解析后的一个资源绑定（{@code plugin.proto} {@code Binding}）：只含清单 {@code bindings} 声明、且运营方授予了
 * {@code binding:<绑定名>} 的项，连同它的属性（{@code lifecycle.md} §2、§7.2，ADR-0285）。
 *
 * <p>属性与宿主进程里的特性读到的相同：属性名区分大小写，运行时不折叠大小写、不改写，也不核对属性名的文法与上限（那是宿主的事）；
 * 值为空的属性照样在表里；各项的先后没有含义。没有属性时（含按较早的规范实现的宿主不发这个字段）是空表。{@code value} 为空表示来源只给了属性、
 * 没有主值，这不是错误，运行时也不拿属性拼出一个主值。
 *
 * <p>主值与属性值可能是凭据，不要写进日志、错误与健康条目：{@link #toString()} 只带绑定名、类型与属性名。
 *
 * @param name 绑定名
 * @param type 资源类型，如 {@code connection:relational}
 * @param value 主值：连接串或地址；来源没有给主值时为空串
 * @param properties 其余属性：属性名到属性值的只读表，没有属性时为空表
 */
public record ResolvedBinding(String name, String type, String value, Map<String, String> properties) {
    /**
     * 校验并创建绑定：属性拷贝成只读表，之后改传入的表不影响绑定。
     *
     * @param name 绑定名
     * @param type 资源类型
     * @param value 主值；没有时为空串
     * @param properties 其余属性；没有时为空表，属性名与属性值都不能是 {@code null}
     * @throws NullPointerException 任一参数、属性名或属性值是 {@code null}
     */
    public ResolvedBinding {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
    }

    /**
     * 创建没有属性的绑定。
     *
     * @param name 绑定名
     * @param type 资源类型
     * @param value 主值；没有时为空串
     */
    public ResolvedBinding(String name, String type, String value) {
        this(name, type, value, Map.of());
    }

    /**
     * 返回不含主值与属性值的文本：只带绑定名、类型与按升序排列的属性名，连接串与属性值里常有密钥，不进日志（{@code lifecycle.md} §7.2）。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "ResolvedBinding[name=" + name + ", type=" + type + ", properties=" + new TreeSet<>(properties.keySet()) + "]";
    }
}
