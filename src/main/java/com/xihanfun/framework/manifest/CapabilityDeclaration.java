package com.xihanfun.framework.manifest;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提供的能力：{@code id@version}，接口版本是正整数。
 *
 * @param id 能力 ID
 * @param version 接口版本，至少为 1
 */
public record CapabilityDeclaration(String id, int version) {
    /** 声明文本的文法。 */
    private static final Pattern PATTERN = Pattern.compile("^([a-z][a-z0-9]*\\.[a-z][a-z0-9-]*)@([1-9][0-9]*)$");

    /**
     * 创建声明。
     *
     * @param id 能力 ID
     * @param version 接口版本，至少为 1
     * @throws IllegalArgumentException ID 不合法或版本小于 1
     */
    public CapabilityDeclaration {
        CapabilityId.validate(id);
        if (version < 1) {
            throw new IllegalArgumentException("The interface version of '" + id + "' must be a positive integer, got " + version + ".");
        }
    }

    /**
     * 创建声明。
     *
     * @param id 能力 ID
     * @param version 接口版本，至少为 1
     * @return 声明
     * @throws IllegalArgumentException ID 不合法或版本小于 1
     */
    public static CapabilityDeclaration of(String id, int version) {
        return new CapabilityDeclaration(id, version);
    }

    /**
     * 解析 {@code id@version}。
     *
     * @param text 声明文本；{@code null} 视为不合法
     * @return 声明；文本不合法时为空
     */
    public static Optional<CapabilityDeclaration> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher match = PATTERN.matcher(text);
        if (!match.matches()) {
            return Optional.empty();
        }
        return InterfaceVersions.parse(match.group(2)).map(version -> new CapabilityDeclaration(match.group(1), version));
    }

    /**
     * 解析 {@code id@version}。
     *
     * @param text 声明文本
     * @return 声明
     * @throws IllegalArgumentException 文本不合法
     */
    public static CapabilityDeclaration parse(String text) {
        Objects.requireNonNull(text, "text");
        return tryParse(text).orElseThrow(
                () -> new IllegalArgumentException("'" + text + "' is not a valid capability declaration; expected '<capability-id>@<version>'."));
    }

    /**
     * 返回 {@code id@version}。
     *
     * @return 声明文本
     */
    @Override
    public String toString() {
        return id + "@" + version;
    }
}
