package com.xihanfun.framework.manifest;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 依赖的能力：{@code id@[^]version[?]}；{@code ^N} 与 {@code N} 等价，{@code ?} 表示可选依赖。
 *
 * @param id 能力 ID
 * @param version 接口版本，至少为 1
 * @param optional 是否可选依赖
 */
public record CapabilityRequirement(String id, int version, boolean optional) {
    /** 依赖文本的文法。 */
    private static final Pattern PATTERN = Pattern.compile("^([a-z][a-z0-9]*\\.[a-z][a-z0-9-]*)@(\\^?)([1-9][0-9]*)(\\??)$");

    /**
     * 创建依赖。
     *
     * @param id 能力 ID
     * @param version 接口版本，至少为 1
     * @param optional 是否可选依赖
     * @throws IllegalArgumentException ID 不合法或版本小于 1
     */
    public CapabilityRequirement {
        CapabilityId.validate(id);
        if (version < 1) {
            throw new IllegalArgumentException("The interface version of '" + id + "' must be a positive integer, got " + version + ".");
        }
    }

    /**
     * 创建必需的依赖。
     *
     * @param id 能力 ID
     * @param version 接口版本，至少为 1
     * @return 依赖
     * @throws IllegalArgumentException ID 不合法或版本小于 1
     */
    public static CapabilityRequirement of(String id, int version) {
        return new CapabilityRequirement(id, version, false);
    }

    /**
     * 解析 {@code id@[^]version[?]}。
     *
     * @param text 依赖文本；{@code null} 视为不合法
     * @return 依赖；文本不合法时为空
     */
    public static Optional<CapabilityRequirement> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher match = PATTERN.matcher(text);
        if (!match.matches()) {
            return Optional.empty();
        }
        boolean optional = "?".equals(match.group(4));
        return InterfaceVersions.parse(match.group(3)).map(version -> new CapabilityRequirement(match.group(1), version, optional));
    }

    /**
     * 解析 {@code id@[^]version[?]}。
     *
     * @param text 依赖文本
     * @return 依赖
     * @throws IllegalArgumentException 文本不合法
     */
    public static CapabilityRequirement parse(String text) {
        Objects.requireNonNull(text, "text");
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException(
                "'" + text + "' is not a valid capability requirement; expected '<capability-id>@[^]<version>[?]'."));
    }

    /**
     * 返回换了可选标记的依赖。
     *
     * @param value 是否可选依赖
     * @return 新的依赖
     */
    public CapabilityRequirement withOptional(boolean value) {
        return new CapabilityRequirement(id, version, value);
    }

    /**
     * 判断一个声明是否满足本依赖：同一能力且同一接口版本。
     *
     * @param declaration 提供的能力
     * @return 满足时为真
     */
    public boolean isSatisfiedBy(CapabilityDeclaration declaration) {
        Objects.requireNonNull(declaration, "declaration");
        return id.equals(declaration.id()) && version == declaration.version();
    }

    /**
     * 返回 {@code id@version[?]}；{@code ^} 与不带它等价，不写出。
     *
     * @return 依赖文本
     */
    @Override
    public String toString() {
        return id + "@" + version + (optional ? "?" : "");
    }
}
