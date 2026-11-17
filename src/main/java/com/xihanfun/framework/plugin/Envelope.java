package com.xihanfun.framework.plugin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * CloudEvents 1.0 信封的平铺（RFC-0009，{@code plugin.proto} {@code Envelope}）：宿主投递给插件的消息，也是插件经宿主发布的消息。
 *
 * <p>不可变：{@link #data()} 返回副本。扩展属性的名字原样保留，例如租户在 {@code xihantenant} 里。
 */
public final class Envelope {
    /** CloudEvents 的规范版本。 */
    public static final String SPEC_VERSION = "1.0";

    /** 缺省的 {@code datacontenttype}。 */
    public static final String JSON_CONTENT_TYPE = "application/json";

    /** 承载租户的扩展属性（RFC-0009「信封」）。 */
    public static final String TENANT_EXTENSION = "xihantenant";

    private final String topic;
    private final String id;
    private final String source;
    private final String type;
    private final String specVersion;
    private final String subject;
    private final String time;
    private final String dataContentType;
    private final byte[] data;
    private final Map<String, String> extensions;

    private Envelope(Builder builder) {
        this.topic = builder.topic;
        this.id = builder.id;
        this.source = builder.source;
        this.type = builder.type;
        this.specVersion = builder.specVersion;
        this.subject = builder.subject;
        this.time = builder.time;
        this.dataContentType = builder.dataContentType;
        this.data = builder.data.clone();
        this.extensions = Map.copyOf(new TreeMap<>(builder.extensions));
    }

    /**
     * 创建构建器。
     *
     * @param topic 主题
     * @param id CloudEvents {@code id}
     * @param source CloudEvents {@code source}
     * @param type CloudEvents {@code type}
     * @return 构建器；{@code specversion} 缺省 {@value #SPEC_VERSION}，{@code datacontenttype} 缺省 {@value #JSON_CONTENT_TYPE}，没有数据
     */
    public static Builder builder(String topic, String id, String source, String type) {
        return new Builder(topic, id, source, type);
    }

    /**
     * 返回主题。
     *
     * @return 主题
     */
    public String topic() {
        return topic;
    }

    /**
     * 返回 {@code id}。
     *
     * @return 消息 ID
     */
    public String id() {
        return id;
    }

    /**
     * 返回 {@code source}。
     *
     * @return 来源
     */
    public String source() {
        return source;
    }

    /**
     * 返回 {@code type}。
     *
     * @return 消息类型
     */
    public String type() {
        return type;
    }

    /**
     * 返回 {@code specversion}。
     *
     * @return 规范版本
     */
    public String specVersion() {
        return specVersion;
    }

    /**
     * 返回 {@code subject}。
     *
     * @return 主体；没有时为空
     */
    public Optional<String> subject() {
        return Optional.ofNullable(subject);
    }

    /**
     * 返回 {@code time}（RFC 3339）。
     *
     * @return 时间；没有时为空
     */
    public Optional<String> time() {
        return Optional.ofNullable(time);
    }

    /**
     * 返回 {@code datacontenttype}。
     *
     * @return 内容类型
     */
    public String dataContentType() {
        return dataContentType;
    }

    /**
     * 返回 {@code data}。
     *
     * @return 数据的副本
     */
    public byte[] data() {
        return data.clone();
    }

    /**
     * 按 UTF-8 返回 {@code data} 的文本。
     *
     * @return 文本
     */
    public String dataText() {
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 返回扩展属性。
     *
     * @return 只读表
     */
    public Map<String, String> extensions() {
        return extensions;
    }

    /**
     * 返回 {@code xihantenant} 扩展属性。
     *
     * @return 租户；没有时为空
     */
    public Optional<String> tenant() {
        return Optional.ofNullable(extensions.get(TENANT_EXTENSION));
    }

    /**
     * 按全部属性比较，数据按字节比较。
     *
     * @param other 另一个对象
     * @return 全部属性相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Envelope that && topic.equals(that.topic) && id.equals(that.id) && source.equals(that.source)
                && type.equals(that.type) && specVersion.equals(that.specVersion) && Objects.equals(subject, that.subject)
                && Objects.equals(time, that.time) && dataContentType.equals(that.dataContentType) && Arrays.equals(data, that.data)
                && extensions.equals(that.extensions);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(topic, id, source, type, specVersion, subject, time, dataContentType, Arrays.hashCode(data), extensions);
    }

    /**
     * 返回便于日志的文本：主题、ID 与类型，不含数据。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "Envelope[topic=" + topic + ", id=" + id + ", type=" + type + "]";
    }

    /**
     * 信封的构建器；不是线程安全的。
     */
    public static final class Builder {
        private final String topic;
        private final String id;
        private final String source;
        private final String type;
        private String specVersion = SPEC_VERSION;
        private String subject;
        private String time;
        private String dataContentType = JSON_CONTENT_TYPE;
        private byte[] data = new byte[0];
        private final Map<String, String> extensions = new TreeMap<>();

        private Builder(String topic, String id, String source, String type) {
            this.topic = Objects.requireNonNull(topic, "topic");
            this.id = Objects.requireNonNull(id, "id");
            this.source = Objects.requireNonNull(source, "source");
            this.type = Objects.requireNonNull(type, "type");
        }

        /**
         * 设置 {@code specversion}。
         *
         * @param value 规范版本
         * @return 本构建器
         */
        public Builder specVersion(String value) {
            this.specVersion = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置 {@code subject}。
         *
         * @param value 主体
         * @return 本构建器
         */
        public Builder subject(String value) {
            this.subject = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置 {@code time}（RFC 3339）。
         *
         * @param value 时间
         * @return 本构建器
         */
        public Builder time(String value) {
            this.time = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置 {@code datacontenttype}。
         *
         * @param value 内容类型
         * @return 本构建器
         */
        public Builder dataContentType(String value) {
            this.dataContentType = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置 {@code data}。
         *
         * @param value 数据；复制一份
         * @return 本构建器
         */
        public Builder data(byte[] value) {
            this.data = Objects.requireNonNull(value, "value").clone();
            return this;
        }

        /**
         * 以 UTF-8 文本设置 {@code data}，例如一段 JSON。
         *
         * @param value 文本
         * @return 本构建器
         */
        public Builder data(String value) {
            this.data = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /**
         * 设置一个扩展属性。
         *
         * @param name 属性名
         * @param value 属性值
         * @return 本构建器
         */
        public Builder extension(String name, String value) {
            extensions.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * 设置多个扩展属性。
         *
         * @param values 属性
         * @return 本构建器
         */
        public Builder extensions(Map<String, String> values) {
            Objects.requireNonNull(values, "values").forEach(this::extension);
            return this;
        }

        /**
         * 构建信封。
         *
         * @return 信封
         */
        public Envelope build() {
            return new Envelope(this);
        }
    }
}
