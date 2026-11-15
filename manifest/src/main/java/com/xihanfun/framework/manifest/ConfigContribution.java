package com.xihanfun.framework.manifest;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 贡献的配置节（RFC-0002「配置节」）：领域节 {@code XiHan:<领域>}，可再带一层或多层 PascalCase 子节（如 {@code XiHan:Caching:Redis}）。
 */
public final class ConfigContribution {
    /** 配置节文法。 */
    private static final Pattern SECTION = Pattern.compile("^XiHan:[A-Z][A-Za-z0-9]*(:[A-Z][A-Za-z0-9]*)*$");

    private final String section;

    /** 配置的 JSON Schema 文本；没有为 {@code null}。 */
    private final String schema;

    private ConfigContribution(String section, String schema) {
        this.section = section;
        this.schema = schema;
    }

    /**
     * 创建配置节贡献，没有 Schema。
     *
     * @param section 配置节
     * @return 贡献
     * @throws IllegalArgumentException 配置节不合文法
     */
    public static ConfigContribution of(String section) {
        Objects.requireNonNull(section, "section");
        if (!SECTION.matcher(section).matches()) {
            throw new IllegalArgumentException("The configuration section '" + section
                    + "' must match XiHan:<Domain> or XiHan:<Domain>:<Section>..., with PascalCase segments.");
        }
        return new ConfigContribution(section, null);
    }

    /**
     * 返回带配置 Schema 的贡献。
     *
     * @param value JSON Schema 文本
     * @return 新的贡献
     */
    public ConfigContribution withSchema(String value) {
        return new ConfigContribution(section, Objects.requireNonNull(value, "value"));
    }

    /**
     * 返回配置节。
     *
     * @return 配置节
     */
    public String section() {
        return section;
    }

    /**
     * 返回配置的 JSON Schema 文本。
     *
     * @return Schema；没有时为空
     */
    public Optional<String> schema() {
        return Optional.ofNullable(schema);
    }

    /**
     * 按配置节与 Schema 比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigContribution that && section.equals(that.section) && Objects.equals(schema, that.schema);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(section, schema);
    }

    /**
     * 返回配置节。
     *
     * @return 配置节
     */
    @Override
    public String toString() {
        return section;
    }
}
