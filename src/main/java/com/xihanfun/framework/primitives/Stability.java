package com.xihanfun.framework.primitives;

import java.util.Optional;

/**
 * 五级稳定性（RFC-0001「稳定性级别」）：对使用者的承诺，以同一大版本内的小版本与补丁版本衡量。
 */
public enum Stability {
    /** 同一大版本内不破坏。 */
    STABLE("stable"),
    /** 小版本可以破坏，补丁版本不破坏。 */
    PREVIEW("preview"),
    /** 小版本可以破坏或移除，补丁版本不破坏。 */
    EXPERIMENTAL("experimental"),
    /** 不承诺，只供声明它的包自己使用。 */
    INTERNAL("internal"),
    /** 已弃用，只在大版本移除。 */
    DEPRECATED("deprecated");

    /** 清单与协议里的小写名。 */
    private final String wireName;

    Stability(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按小写名解析，区分大小写。
     *
     * @param name 小写名，如 {@code experimental}
     * @return 稳定性；名字未知时为空
     */
    public static Optional<Stability> parse(String name) {
        for (Stability stability : values()) {
            if (stability.wireName.equals(name)) {
                return Optional.of(stability);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回清单与协议里的小写名。
     *
     * @return 小写名
     */
    public String wireName() {
        return wireName;
    }
}
