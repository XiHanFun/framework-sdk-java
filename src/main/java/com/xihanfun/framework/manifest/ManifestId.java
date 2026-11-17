package com.xihanfun.framework.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 清单 ID 的文法：{@code xihan.<领域>.<名称>}。
 */
public final class ManifestId {
    /** 清单 ID 文法。 */
    private static final Pattern PATTERN = Pattern.compile("^xihan\\.[a-z][a-z0-9]*\\.[a-z][a-z0-9-]*$");

    private ManifestId() {
    }

    /**
     * 判断清单 ID 是否合法。
     *
     * @param id 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }

    /**
     * 取清单 ID 的领域段，如 {@code xihan.samples.echo} 的 {@code samples}。
     *
     * @param id 清单 ID
     * @return 领域段
     * @throws IllegalArgumentException ID 不合法
     */
    public static String domainOf(String id) {
        Objects.requireNonNull(id, "id");
        if (!isValid(id)) {
            throw new IllegalArgumentException("The manifest ID '" + id + "' must match xihan.<domain>.<name>.");
        }
        return id.split("\\.")[1];
    }
}
