package com.xihanfun.framework.manifest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 贡献点：管道中间件、操作、配置节、权限码与健康项。
 *
 * <p>{@link #permissions()} 是此包定义的权限码（RFC-0008 权限码文法：小写、点分、至少两段，如 {@code orders.read}），不重复；
 * 插件的由宿主在登记它的代理操作时一并登记（{@code plugin/invocation.md} §1）。
 */
public final class Contributions {
    /**
     * 操作 ID 文法（RFC-0011、conventions/operation-ids.md）：资源与动词都是小写字母开头、连字符分隔；
     * 与 operation 模块的 OperationId 相同（manifest 不依赖 operation，所以在这里另写一份）。
     */
    private static final Pattern OPERATION = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    /** 权限码文法（RFC-0008）。 */
    private static final Pattern PERMISSION_CODE = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");

    /** 健康项名文法。 */
    private static final Pattern HEALTH = Pattern.compile("^[a-z][a-z0-9.-]*$");

    /** 什么都没有贡献。 */
    private static final Contributions EMPTY = new Builder().build();

    private final List<PipelineContribution> pipeline;
    private final List<String> operations;
    private final ConfigContribution config;
    private final List<String> permissions;
    private final List<String> health;

    private Contributions(Builder builder) {
        this.pipeline = builder.pipeline;
        this.operations = builder.operations;
        this.config = builder.config;
        this.permissions = builder.permissions;
        this.health = builder.health;
    }

    /**
     * 返回什么都没有贡献的实例；写出时是 {@code {}}。
     *
     * @return 空的贡献点
     */
    public static Contributions empty() {
        return EMPTY;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回内容相同的构建器，便于在已有贡献点上修改。
     *
     * @return 构建器
     */
    public Builder toBuilder() {
        return new Builder().pipeline(pipeline).operations(operations).permissions(permissions).health(health)
                .config(Optional.ofNullable(config));
    }

    /**
     * 返回贡献的管道中间件。
     *
     * @return 只读列表；没有时为空
     */
    public List<PipelineContribution> pipeline() {
        return pipeline;
    }

    /**
     * 返回贡献的操作 ID。
     *
     * @return 只读列表；没有时为空
     */
    public List<String> operations() {
        return operations;
    }

    /**
     * 返回贡献的配置节。
     *
     * @return 配置节；没有时为空
     */
    public Optional<ConfigContribution> config() {
        return Optional.ofNullable(config);
    }

    /**
     * 返回此包定义的权限码。
     *
     * @return 只读列表；没有时为空
     */
    public List<String> permissions() {
        return permissions;
    }

    /**
     * 返回贡献的健康项。
     *
     * @return 只读列表；没有时为空
     */
    public List<String> health() {
        return health;
    }

    /**
     * 按全部字段比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Contributions that && pipeline.equals(that.pipeline) && operations.equals(that.operations)
                && Objects.equals(config, that.config) && permissions.equals(that.permissions) && health.equals(that.health);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(pipeline, operations, config, permissions, health);
    }

    /**
     * 返回便于调试的文本。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "Contributions[pipeline=" + pipeline + ", operations=" + operations + ", config=" + config + ", permissions=" + permissions
                + ", health=" + health + "]";
    }

    /**
     * {@link Contributions} 的构建器；不是线程安全的，构建出的实例不可变。
     */
    public static final class Builder {
        private List<PipelineContribution> pipeline = List.of();
        private List<String> operations = List.of();
        private ConfigContribution config;
        private List<String> permissions = List.of();
        private List<String> health = List.of();

        private Builder() {
        }

        /**
         * 设置贡献的管道中间件。
         *
         * @param value 中间件贡献
         * @return 本构建器
         */
        public Builder pipeline(List<PipelineContribution> value) {
            this.pipeline = List.copyOf(value);
            return this;
        }

        /**
         * 设置贡献的操作 ID。
         *
         * @param value 操作 ID，{@code <resource>.<verb>}
         * @return 本构建器
         */
        public Builder operations(List<String> value) {
            this.operations = List.copyOf(value);
            return this;
        }

        /**
         * 设置贡献的配置节。
         *
         * @param value 配置节
         * @return 本构建器
         */
        public Builder config(ConfigContribution value) {
            this.config = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置此包定义的权限码。
         *
         * @param value 权限码
         * @return 本构建器
         */
        public Builder permissions(List<String> value) {
            this.permissions = List.copyOf(value);
            return this;
        }

        /**
         * 设置贡献的健康项。
         *
         * @param value 健康项名
         * @return 本构建器
         */
        public Builder health(List<String> value) {
            this.health = List.copyOf(value);
            return this;
        }

        /**
         * 校验并构建贡献点。
         *
         * @return 贡献点
         * @throws IllegalArgumentException 操作 ID、权限码或健康项不合文法，或权限码重复
         */
        public Contributions build() {
            for (String operation : operations) {
                if (!OPERATION.matcher(operation).matches()) {
                    throw new IllegalArgumentException("The operation '" + operation + "' must match <resource>.<verb>.");
                }
            }
            // 插件定义的权限码由宿主在登记代理操作时一并登记：不合文法或重复的码宿主在发现时拒绝整个插件，读清单时同样拒绝，不让它等到宿主那里才失败
            Set<String> seen = new HashSet<>();
            for (String code : permissions) {
                if (!PERMISSION_CODE.matcher(code).matches()) {
                    throw new IllegalArgumentException("The permission code '" + code
                            + "' in 'contributes.permissions' must be a lowercase dot-separated code with at least two segments, such as 'orders.read'.");
                }
                if (!seen.add(code)) {
                    throw new IllegalArgumentException("The permission code '" + code + "' appears more than once in 'contributes.permissions'.");
                }
            }
            for (String item : health) {
                if (!HEALTH.matcher(item).matches()) {
                    throw new IllegalArgumentException("The health item '" + item + "' must match ^[a-z][a-z0-9.-]*$.");
                }
            }
            return new Contributions(this);
        }

        private Builder config(Optional<ConfigContribution> value) {
            this.config = value.orElse(null);
            return this;
        }
    }
}
