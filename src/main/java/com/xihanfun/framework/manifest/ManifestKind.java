package com.xihanfun.framework.manifest;

import java.util.Optional;

/**
 * 清单种类。
 */
public enum ManifestKind {
    /** 能力包。 */
    CAPABILITY("capability"),
    /** Provider。 */
    PROVIDER("provider"),
    /** 插件。 */
    PLUGIN("plugin"),
    /** 宿主。 */
    HOST("host"),
    /** 协议适配器。 */
    PROTOCOL("protocol");

    /** 清单里的名字。 */
    private final String wireName;

    ManifestKind(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按清单里的名字解析。
     *
     * @param name 名字，如 {@code plugin}
     * @return 种类；名字未知时为空
     */
    public static Optional<ManifestKind> parse(String name) {
        for (ManifestKind kind : values()) {
            if (kind.wireName.equals(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回清单里的名字。
     *
     * @return 名字
     */
    public String wireName() {
        return wireName;
    }
}
