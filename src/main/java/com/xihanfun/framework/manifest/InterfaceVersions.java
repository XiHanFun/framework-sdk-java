package com.xihanfun.framework.manifest;

import java.util.Optional;

/**
 * 接口版本的数字文本。
 */
final class InterfaceVersions {
    private InterfaceVersions() {
    }

    /**
     * 解析已经由文法核对过的十进制正整数；超出 {@code int} 的范围视为不合法。
     */
    static Optional<Integer> parse(String digits) {
        try {
            int version = Integer.parseInt(digits);
            return version >= 1 ? Optional.of(version) : Optional.empty();
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }
}
