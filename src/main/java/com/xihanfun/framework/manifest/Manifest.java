package com.xihanfun.framework.manifest;

import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.KernelMessages;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.Unit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一份清单 {@code xihan.manifest.json}（RFC-0002）：身份、提供与依赖的能力、资源绑定、贡献点、激活事件与权限。
 *
 * <p>不可变、线程安全，只能经 {@link Builder#build()} 或 {@link ManifestJson#read(String)} 得到，二者都校验全部字段与跨字段约束，
 * 所以每个实例都是合法的清单：插件必须声明隔离，非插件不得声明隔离或权限，提供、依赖与绑定不得重复。
 *
 * <p>只有 Wasm 插件能贡献中间件（RFC-0017），但这由宿主在发现插件时以 {@code PLUGINS_VALIDATION_001} 拒绝，读取与构建清单不核对，与 .NET 的
 * {@code ManifestReader} 一致（ADR-0236）。
 */
public final class Manifest {
    /** 清单文件名。 */
    public static final String FILE_NAME = "xihan.manifest.json";

    /** 缺省的激活事件。 */
    public static final String ON_STARTUP = "onStartup";

    /** SemVer 文法。 */
    private static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    /**
     * 激活事件文法（RFC-0002 解析规则 2 第 3 步，{@code plugin/lifecycle.md} §3）：{@code onOperation:} 的模式是操作 ID 的字符加 {@code *}，
     * {@code onEvent:} 的模式是主题的字符加 {@code *}，所以只有它可以带下划线（ADR-0276）。
     */
    private static final Pattern ACTIVATION = Pattern.compile("^(onStartup|onOperation:[a-z0-9.*-]+|onEvent:[a-z0-9._*-]+)$");

    /** 插件权限文法。 */
    private static final Pattern PERMISSION = Pattern.compile("^(net\\.outbound|fs\\.read|fs\\.write|capability):.+$");

    /** 版本是否是语义化版本。 */
    static boolean isValidVersion(String version) {
        return SEMVER.matcher(version).matches();
    }

    /** 激活事件是否合文法。 */
    static boolean isValidActivation(String event) {
        return ACTIVATION.matcher(event).matches();
    }

    /** 插件权限是否合文法：{@code .} 不匹配换行，整串匹配。 */
    static boolean isValidPermission(String permission) {
        return PERMISSION.matcher(permission).matches();
    }

    private final String schema;
    private final String id;
    private final String packageId;
    private final String version;
    private final int primitives;
    private final Stability stability;
    private final ManifestKind kind;
    private final ManifestRole role;
    private final PluginIsolation isolation;
    private final List<CapabilityDeclaration> provides;
    private final List<CapabilityRequirement> requires;
    private final List<Binding> bindings;
    private final Contributions contributes;
    private final List<String> activation;
    private final List<String> permissions;
    private final String entry;

    private Manifest(Builder builder) {
        this.schema = builder.schema;
        this.id = builder.id;
        this.packageId = builder.packageId;
        this.version = builder.version;
        this.primitives = builder.primitives;
        this.stability = builder.stability;
        this.kind = builder.kind;
        this.role = builder.role;
        this.isolation = builder.isolation;
        this.provides = builder.provides;
        this.requires = builder.requires;
        this.bindings = builder.bindings;
        this.contributes = builder.contributes;
        this.activation = builder.activation;
        this.permissions = builder.permissions;
        this.entry = builder.entry;
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
     * 返回内容相同的构建器，便于在已有清单上修改。
     *
     * @return 构建器
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.schema = schema;
        builder.id = id;
        builder.packageId = packageId;
        builder.version = version;
        builder.primitives = primitives;
        builder.stability = stability;
        builder.kind = kind;
        builder.role = role;
        builder.isolation = isolation;
        builder.provides = provides;
        builder.requires = requires;
        builder.bindings = bindings;
        builder.contributes = contributes;
        builder.activation = activation;
        builder.activationDeclared = !activation.isEmpty();
        builder.permissions = permissions;
        builder.entry = entry;
        return builder;
    }

    /**
     * 返回 {@code $schema}。
     *
     * @return Schema 地址；没写时为空
     */
    public Optional<String> schema() {
        return Optional.ofNullable(schema);
    }

    /**
     * 返回清单 ID。
     *
     * @return {@code xihan.<领域>.<名称>}
     */
    public String id() {
        return id;
    }

    /**
     * 返回所属包 ID（清单的 {@code package}）。
     *
     * @return 包 ID；没写时为空
     */
    public Optional<String> packageId() {
        return Optional.ofNullable(packageId);
    }

    /**
     * 返回 SemVer 版本。
     *
     * @return 版本
     */
    public String version() {
        return version;
    }

    /**
     * 返回依赖的原语层大版本。
     *
     * @return 正整数
     */
    public int primitives() {
        return primitives;
    }

    /**
     * 返回稳定性。
     *
     * @return 稳定性
     */
    public Stability stability() {
        return stability;
    }

    /**
     * 返回清单种类。
     *
     * @return 种类
     */
    public ManifestKind kind() {
        return kind;
    }

    /**
     * 返回清单里写明的角色。
     *
     * @return 角色；没写时为空
     */
    public Optional<ManifestRole> role() {
        return Optional.ofNullable(role);
    }

    /**
     * 返回生效的角色：没写时为 {@link ManifestRole#PROVIDER}。
     *
     * @return 角色
     */
    public ManifestRole effectiveRole() {
        return role != null ? role : ManifestRole.PROVIDER;
    }

    /**
     * 返回插件隔离级别。
     *
     * @return 隔离级别；非插件为空
     */
    public Optional<PluginIsolation> isolation() {
        return Optional.ofNullable(isolation);
    }

    /**
     * 返回提供的能力。
     *
     * @return 只读列表；没有时为空
     */
    public List<CapabilityDeclaration> provides() {
        return provides;
    }

    /**
     * 返回依赖的能力。
     *
     * @return 只读列表；没有时为空
     */
    public List<CapabilityRequirement> requires() {
        return requires;
    }

    /**
     * 返回资源绑定。
     *
     * @return 只读列表；没有时为空
     */
    public List<Binding> bindings() {
        return bindings;
    }

    /**
     * 返回贡献点。
     *
     * @return 贡献点；清单没写 {@code contributes} 时为空
     */
    public Optional<Contributions> contributes() {
        return Optional.ofNullable(contributes);
    }

    /**
     * 返回清单里写明的激活事件。
     *
     * @return 只读列表；没写时为空
     */
    public List<String> activation() {
        return activation;
    }

    /**
     * 返回生效的激活事件：没写时为 {@code [onStartup]}。
     *
     * @return 只读列表
     */
    public List<String> effectiveActivation() {
        return activation.isEmpty() ? List.of(ON_STARTUP) : activation;
    }

    /**
     * 判断是否在宿主启动时激活。
     *
     * @return 激活事件含 {@code onStartup} 时为真
     */
    public boolean activatesOnStartup() {
        return effectiveActivation().contains(ON_STARTUP);
    }

    /**
     * 返回插件所需的权限。
     *
     * @return 只读列表；没有时为空
     */
    public List<String> permissions() {
        return permissions;
    }

    /**
     * 返回入口。
     *
     * @return 入口类型或可执行文件名；没写时为空
     */
    public Optional<String> entry() {
        return Optional.ofNullable(entry);
    }

    /**
     * 返回贡献的操作 ID。
     *
     * @return 只读列表；没有贡献时为空
     */
    public List<String> operations() {
        return contributes != null ? contributes.operations() : List.of();
    }

    /**
     * 返回贡献的健康项。
     *
     * @return 只读列表；没有贡献时为空
     */
    public List<String> health() {
        return contributes != null ? contributes.health() : List.of();
    }

    /**
     * 返回贡献的配置节。
     *
     * @return 配置节；没有时为空
     */
    public Optional<String> configSection() {
        return contributes().flatMap(Contributions::config).map(ConfigContribution::section);
    }

    /**
     * 按生效的值比较，与宿主核对插件握手里的清单一样按字段、不按文本（RFC-0002）：不看 {@code $schema}；没写的角色、激活事件与贡献点
     * 按缺省（{@code provider}、{@code [onStartup]}、空贡献点）比较，所以写出的清单再读一遍与原来的相等。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Manifest that && id.equals(that.id)
                && Objects.equals(packageId, that.packageId) && version.equals(that.version) && primitives == that.primitives
                && stability == that.stability && kind == that.kind && effectiveRole() == that.effectiveRole() && isolation == that.isolation
                && provides.equals(that.provides) && requires.equals(that.requires) && bindings.equals(that.bindings)
                && effectiveContributions().equals(that.effectiveContributions()) && effectiveActivation().equals(that.effectiveActivation())
                && permissions.equals(that.permissions) && Objects.equals(entry, that.entry);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, packageId, version, primitives, stability, kind, effectiveRole(), isolation, provides, requires, bindings,
                effectiveContributions(), effectiveActivation(), permissions, entry);
    }

    /**
     * 返回生效的贡献点：没写时为空的贡献点。
     *
     * @return 贡献点
     */
    public Contributions effectiveContributions() {
        return contributes != null ? contributes : Contributions.empty();
    }

    /**
     * 返回 {@code id@version}。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return id + "@" + version;
    }

    /**
     * {@link Manifest} 的构建器；不是线程安全的，构建出的实例不可变。
     */
    public static final class Builder {
        private String schema;
        private String id;
        private String packageId;
        private String version;
        private int primitives;
        private Stability stability;
        private ManifestKind kind;
        private ManifestRole role;
        private PluginIsolation isolation;
        private List<CapabilityDeclaration> provides = List.of();
        private List<CapabilityRequirement> requires = List.of();
        private List<Binding> bindings = List.of();
        private Contributions contributes;
        private List<String> activation = List.of();

        /** 写过激活事件：区分「没写」（缺省 onStartup）与「写了空列表」（不合法）。 */
        private boolean activationDeclared;
        private List<String> permissions = List.of();
        private String entry;

        private Builder() {
        }

        /**
         * 设置 {@code $schema}。
         *
         * @param value Schema 地址
         * @return 本构建器
         */
        public Builder schema(String value) {
            this.schema = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置清单 ID（必需）。
         *
         * @param value {@code xihan.<领域>.<名称>}
         * @return 本构建器
         */
        public Builder id(String value) {
            this.id = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置所属包 ID。
         *
         * @param value 包 ID
         * @return 本构建器
         */
        public Builder packageId(String value) {
            this.packageId = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置 SemVer 版本（必需）。
         *
         * @param value 版本
         * @return 本构建器
         */
        public Builder version(String value) {
            this.version = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置依赖的原语层大版本（必需）。
         *
         * @param value 正整数
         * @return 本构建器
         */
        public Builder primitives(int value) {
            this.primitives = value;
            return this;
        }

        /**
         * 设置稳定性（必需）。
         *
         * @param value 稳定性
         * @return 本构建器
         */
        public Builder stability(Stability value) {
            this.stability = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置清单种类（必需）。
         *
         * @param value 种类
         * @return 本构建器
         */
        public Builder kind(ManifestKind value) {
            this.kind = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置角色。
         *
         * @param value 角色
         * @return 本构建器
         */
        public Builder role(ManifestRole value) {
            this.role = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置插件隔离级别（插件必需，非插件不得设置）。
         *
         * @param value 隔离级别
         * @return 本构建器
         */
        public Builder isolation(PluginIsolation value) {
            this.isolation = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置提供的能力。
         *
         * @param value 能力声明
         * @return 本构建器
         */
        public Builder provides(List<CapabilityDeclaration> value) {
            this.provides = List.copyOf(value);
            return this;
        }

        /**
         * 设置依赖的能力。
         *
         * @param value 能力依赖
         * @return 本构建器
         */
        public Builder requires(List<CapabilityRequirement> value) {
            this.requires = List.copyOf(value);
            return this;
        }

        /**
         * 设置资源绑定。
         *
         * @param value 绑定
         * @return 本构建器
         */
        public Builder bindings(List<Binding> value) {
            this.bindings = List.copyOf(value);
            return this;
        }

        /**
         * 设置贡献点。
         *
         * @param value 贡献点
         * @return 本构建器
         */
        public Builder contributes(Contributions value) {
            this.contributes = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置激活事件；写了就至少一项，空列表在 {@link #build()} 时拒绝（缺省 {@code ["onStartup"]} 只在不写时生效，ADR-0235）。
         *
         * @param value 激活事件，如 {@code onStartup}、{@code onOperation:orders.*}
         * @return 本构建器
         */
        public Builder activation(List<String> value) {
            this.activation = List.copyOf(value);
            this.activationDeclared = true;
            return this;
        }

        /**
         * 设置插件所需的权限（只有插件可以设置）。
         *
         * @param value 权限，如 {@code capability:kernel.pipeline}
         * @return 本构建器
         */
        public Builder permissions(List<String> value) {
            this.permissions = List.copyOf(value);
            return this;
        }

        /**
         * 设置入口。
         *
         * @param value 入口类型或可执行文件名
         * @return 本构建器
         */
        public Builder entry(String value) {
            this.entry = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 核对字段之间的约束（RFC-0002 解析规则 2 第 4 步，.NET 的 {@code Manifest.Validate}），失败以结果返回，按这个顺序报第一处：
         * 插件没有隔离级别为 {@code primitives.manifest.isolation_required}；非插件写了隔离级别或权限为 {@code isolation_not_allowed}；
         * 提供的能力 ID 重复为 {@code duplicate_provides}；依赖的能力 ID 重复为 {@code duplicate_requires}；绑定的名字重复为
         * {@code duplicate_bindings}。
         *
         * <p>单个字段自己的格式（ID、版本、原语层大版本、稳定性与种类必填、激活事件与权限的文法）不是可恢复的失败：与 .NET 在赋值时
         * 拒绝一样是配置或编程错误，在这里照常抛出，不进结果。
         *
         * @return 成功；违反约束时为 {@code PRIMITIVES_VALIDATION_001}，带上面的消息键与参数 {@code id}（清单 ID）
         * @throws FrameworkException 字段缺失或不合法（{@code PRIMITIVES_VALIDATION_001}）
         */
        public Result<Unit> validate() {
            requireFields();
            return crossFieldProblem().<Result<Unit>>map(Result::fail).orElseGet(Result::success);
        }

        /**
         * 校验格式与跨字段约束并构建清单；跨字段约束即 {@link #validate()}，失败时把它的错误抛出。
         *
         * @return 清单
         * @throws FrameworkException 字段缺失或不合法（{@code PRIMITIVES_VALIDATION_001}）；违反跨字段约束时同一个错误码，
         *     带 .NET 的消息键与参数 {@code id}：按 {@code primitives.manifest.isolation_required} → {@code isolation_not_allowed} →
         *     {@code duplicate_provides} → {@code duplicate_requires} → {@code duplicate_bindings} 的顺序报第一个
         */
        public Manifest build() {
            Result<Manifest> built = tryBuild();
            if (built.isFailed()) {
                // 以带消息键与参数的错误抛出：码、键与参数原样留在 toError() 里
                throw new FrameworkException(built.error().orElseThrow());
            }
            return built.value().orElseThrow();
        }

        /**
         * 校验并构建清单，跨字段约束的失败以结果返回；{@link ManifestJson} 的读取经它把错误码换成清单读取失败。
         */
        Result<Manifest> tryBuild() {
            return validate().map(valid -> new Manifest(this));
        }

        /**
         * 单个字段的格式：不合法即抛出，消息是面向开发者的英文，没有消息键。
         */
        private void requireFields() {
            if (!ManifestId.isValid(id)) {
                throw invalid("The manifest ID '" + id + "' must match xihan.<domain>.<name>.");
            }
            if (version == null || !SEMVER.matcher(version).matches()) {
                throw invalid("The version '" + version + "' of '" + id + "' must be a semantic version.");
            }
            if (primitives < 1) {
                throw invalid("The primitives version of '" + id + "' must be a positive integer.");
            }
            if (stability == null) {
                throw invalid("The manifest '" + id + "' must declare its stability.");
            }
            if (kind == null) {
                throw invalid("The manifest '" + id + "' must declare its kind.");
            }
            if (activationDeclared && activation.isEmpty()) {
                throw invalid("The activation of '" + id + "' must list at least one event; leave it out to activate on startup.");
            }
            for (String event : activation) {
                if (!ACTIVATION.matcher(event).matches()) {
                    throw invalid("The activation event '" + event + "' of '" + id + "' is not valid.");
                }
            }
            for (String permission : permissions) {
                if (!PERMISSION.matcher(permission).matches()) {
                    throw invalid("The permission '" + permission + "' of '" + id + "' is not valid.");
                }
            }
        }

        /**
         * 跨字段约束的第一处问题：按 .NET 的 Manifest.Validate 的顺序，消息键与参数相同（RFC-0002 解析规则 2 第 4 步，ADR-0236）；
         * 字段的格式已经由 requireFields 核对过。
         */
        private Optional<Error> crossFieldProblem() {
            if (kind == ManifestKind.PLUGIN && isolation == null) {
                return Optional.of(crossField(KernelMessages.MANIFEST_ISOLATION_REQUIRED));
            }
            if (kind != ManifestKind.PLUGIN && (isolation != null || !permissions.isEmpty())) {
                return Optional.of(crossField(KernelMessages.MANIFEST_ISOLATION_NOT_ALLOWED));
            }
            if (hasDuplicates(provides.stream().map(CapabilityDeclaration::id).toList())) {
                return Optional.of(crossField(KernelMessages.MANIFEST_DUPLICATE_PROVIDES));
            }
            if (hasDuplicates(requires.stream().map(CapabilityRequirement::id).toList())) {
                return Optional.of(crossField(KernelMessages.MANIFEST_DUPLICATE_REQUIRES));
            }
            if (hasDuplicates(bindings.stream().map(Binding::name).toList())) {
                return Optional.of(crossField(KernelMessages.MANIFEST_DUPLICATE_BINDINGS));
            }
            return Optional.empty();
        }

        private static boolean hasDuplicates(List<String> values) {
            Set<String> seen = new HashSet<>();
            return !values.stream().allMatch(seen::add);
        }

        private static FrameworkException invalid(String message) {
            return new FrameworkException(ErrorCode.INVALID_ARGUMENT, message);
        }

        private Error crossField(String key) {
            return Error.validation(ErrorCode.INVALID_ARGUMENT, key, Map.of("id", id));
        }
    }
}
