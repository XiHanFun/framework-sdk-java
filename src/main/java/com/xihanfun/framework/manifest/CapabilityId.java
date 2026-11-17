package com.xihanfun.framework.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 能力 ID 的文法：{@code <领域>.<能力>}（{@code spec/conventions/capability-ids.md}）。
 */
public final class CapabilityId {
    /** 能力 ID 文法。 */
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9]*\\.[a-z][a-z0-9-]*$");

    private CapabilityId() {
    }

    /**
     * 判断能力 ID 是否合法。
     *
     * @param id 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }

    /**
     * 取能力 ID 的领域段，如 {@code caching.distributed} 的 {@code caching}。
     *
     * @param id 能力 ID
     * @return 领域段
     * @throws IllegalArgumentException ID 不合法
     */
    public static String domainOf(String id) {
        validate(id);
        return id.substring(0, id.indexOf('.'));
    }

    /**
     * 校验能力 ID 并原样返回。
     *
     * @param id 能力 ID
     * @return 原样返回 {@code id}
     * @throws IllegalArgumentException ID 不合法
     */
    static String validate(String id) {
        Objects.requireNonNull(id, "id");
        if (!isValid(id)) {
            throw new IllegalArgumentException("The capability ID '" + id + "' must match <domain>.<capability>.");
        }
        return id;
    }
}
