package com.xihanfun.framework.plugin;

import com.xihanfun.framework.internal.GeneralizedUtf8;
import com.xihanfun.framework.primitives.JsonText;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * CloudEvents 1.0 信封的平铺（RFC-0009，{@code plugin.proto} {@code Envelope}）：宿主投递给插件的消息，也是插件经宿主发布的消息。
 *
 * <p>不可变：{@link #data()} 返回副本。扩展属性的名字原样保留，例如租户在 {@code xihantenant} 里。
 *
 * <p>{@link #time()} 是 {@link Instant}（精确到纳秒）。发布时写成 RFC 3339 的 UTC 文本：以 {@code Z} 结尾，秒的小数去掉末尾的 0，全为 0 时不写小数点，
 * 最多九位，如 {@code 2026-11-25T03:25:00.5Z}；SDK 不在本地核对（ADR-0306），{@code 0001}–{@code 9999} 年之外的时刻照写（年份不止四位或带负号），
 * 由宿主以 {@code messaging.envelope.invalid_time} 拒绝那一项。收到的信封里，宿主写来的文本按 RFC-0003 规则 8 读成 {@link Instant}
 * （{@code invocation.md} §4），规则 8 的小数最多九位，不会超出 {@link Instant} 的精度。{@link #dataSchema()} 是 CloudEvents 的 {@code dataschema}
 * （ADR-0305），收到与发布都原样，SDK 不核对、不改写；线上的空串是没有。
 *
 * <p>以文本设置的 {@code data}（{@link Builder#data(String)}）严格编码成 UTF-8（RFC-0001「写出与转交」，ADR-0315）：文本里有孤立的代理项时
 * 信封照样建得出，{@link #hasUnencodableData()} 为真，{@link Host#publish} 不发出这一项、在本地让它失败（{@code invocation.md} §5）。
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
    private final Instant time;
    private final String dataContentType;
    private final String dataSchema;
    private final byte[] data;

    /** 以文本设置的 {@code data} 有孤立的代理项（编不成 UTF-8）。 */
    private final boolean unencodableData;

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
        this.dataSchema = builder.dataSchema;
        this.data = builder.data.clone();
        this.unencodableData = builder.unencodableData;
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
     * 返回 {@code time}。
     *
     * @return 时刻；没有时为空
     */
    public Optional<Instant> time() {
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
     * 返回 {@code dataschema}（ADR-0305），原样。
     *
     * @return 数据的模式；没有时为空
     */
    public Optional<String> dataSchema() {
        return Optional.ofNullable(dataSchema);
    }

    /**
     * 返回 {@code data}。
     *
     * @return 数据的副本；{@link #hasUnencodableData()} 为真时是文本的广义 UTF-8（WTF-8）编码，孤立的代理项各占三个字节，不是合法的 UTF-8
     */
    public byte[] data() {
        return data.clone();
    }

    /**
     * 是否以编不成 UTF-8 的文本设置了 {@code data}（ADR-0315）：{@link Builder#data(String)} 的文本里有孤立的代理项时为真。
     *
     * <p>为真时 {@link Host#publish} 不发出这一项，它的结果是 {@code MESSAGING_VALIDATION_002} + {@code messaging.envelope.invalid}
     * （{@code reason} 为 {@code the data has a lone surrogate and cannot be encoded as UTF-8}），其余各项照常发布
     * （{@code invocation.md} §5）。以字节设置的 {@code data} 总是假：字节原样，由宿主核对。
     *
     * @return 文本编不成 UTF-8 时为真
     */
    public boolean hasUnencodableData() {
        return unencodableData;
    }

    /**
     * 按 UTF-8 返回 {@code data} 的文本。
     *
     * @return 文本；不合法的字节序列换成 U+FFFD，{@link #hasUnencodableData()} 为真时孤立的代理项因此读不回原样
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
     * 按全部属性比较，数据按字节比较，并比较 {@link #hasUnencodableData()}。
     *
     * @param other 另一个对象
     * @return 全部属性相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Envelope that && topic.equals(that.topic) && id.equals(that.id) && source.equals(that.source)
                && type.equals(that.type) && specVersion.equals(that.specVersion) && Objects.equals(subject, that.subject)
                && Objects.equals(time, that.time) && dataContentType.equals(that.dataContentType)
                && Objects.equals(dataSchema, that.dataSchema) && Arrays.equals(data, that.data) && unencodableData == that.unencodableData
                && extensions.equals(that.extensions);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(topic, id, source, type, specVersion, subject, time, dataContentType, dataSchema, Arrays.hashCode(data),
                unencodableData, extensions);
    }

    /**
     * 返回便于日志的文本：主题、ID、类型与有的话的 {@code dataschema}，不含数据。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "Envelope[topic=" + topic + ", id=" + id + ", type=" + type + (dataSchema == null ? "" : ", dataSchema=" + dataSchema) + "]";
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
        private Instant time;
        private String dataContentType = JSON_CONTENT_TYPE;
        private String dataSchema;
        private byte[] data = new byte[0];
        private boolean unencodableData;
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
         * 设置 {@code time}。
         *
         * <p>发布时换算成 UTC 写成 RFC 3339（见 {@link Envelope}）。不在本地核对：{@code 0001}–{@code 9999} 年之外的时刻照写，由宿主拒绝那一项
         * （{@code invocation.md} §4、§5「逐项的核对」）。
         *
         * @param value 时刻
         * @return 本构建器
         */
        public Builder time(Instant value) {
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
         * 设置 {@code dataschema}（ADR-0305）：原样发布，SDK 不核对 URI 语法，也不裁剪（{@code invocation.md} §4）。
         *
         * @param value 数据的模式，通常是一个 URI
         * @return 本构建器
         */
        public Builder dataSchema(String value) {
            this.dataSchema = Objects.requireNonNull(value, "value");
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
            this.unencodableData = false;
            return this;
        }

        /**
         * 以文本设置 {@code data}，例如一段 JSON：文本严格编码成 UTF-8（RFC-0001「写出与转交」，ADR-0315）。
         *
         * <p>文本里有孤立的代理项时编不成 UTF-8，不换成别的字符，也不在这里报错：信封照样建得出，{@link Envelope#hasUnencodableData()} 为真，
         * {@code data} 是文本的广义 UTF-8（WTF-8）编码；{@link Host#publish} 不发出这一项，在本地以 {@code MESSAGING_VALIDATION_002} +
         * {@code messaging.envelope.invalid} 失败（{@code invocation.md} §5）。上一版用 {@link String#getBytes}，孤立的代理项换成 {@code ?}，
         * 宿主收到的是另一份数据。
         *
         * @param value 文本
         * @return 本构建器
         */
        public Builder data(String value) {
            Optional<byte[]> utf8 = JsonText.encode(Objects.requireNonNull(value, "value"));
            this.data = utf8.orElseGet(() -> GeneralizedUtf8.encode(value));
            this.unencodableData = utf8.isEmpty();
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
