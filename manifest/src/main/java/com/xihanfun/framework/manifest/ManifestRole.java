package com.xihanfun.framework.manifest;

import java.util.Optional;

/**
 * Provider 角色：{@link #DEFAULT} 在没有显式 Provider 时被选中。
 */
public enum ManifestRole {
    /** 普通 Provider，清单没写角色时的缺省。 */
    PROVIDER("provider"),
    /** 默认实现：没有显式 Provider 时被选中。 */
    DEFAULT("default");

    /** 清单里的名字。 */
    private final String wireName;

    ManifestRole(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按清单里的名字解析。
     *
     * @param name 名字，如 {@code default}
     * @return 角色；名字未知时为空
     */
    public static Optional<ManifestRole> parse(String name) {
        for (ManifestRole role : values()) {
            if (role.wireName.equals(name)) {
                return Optional.of(role);
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
