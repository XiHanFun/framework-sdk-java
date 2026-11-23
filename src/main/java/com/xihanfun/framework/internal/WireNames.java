package com.xihanfun.framework.internal;

import java.util.Optional;
import java.util.function.Function;

/**
 * 按线上的名字找枚举常量：清单、握手行与协议里的名字都区分大小写、整串相等，各枚举的 {@code parse} 共用这一个查找。
 *
 * <p>内部实现，不属于公开 API：所在的包不导出（{@code module-info.java}），为了让模块内各包的枚举共用才是 {@code public}。
 * 以取名函数传入而不让枚举实现本包的接口：公开枚举的类型层次里不能出现不导出的类型。
 */
public final class WireNames {
    private WireNames() {
    }

    /**
     * 在候选常量里找线上名字等于 {@code name} 的那个。
     *
     * @param constants 候选常量，通常是枚举的 {@code values()}
     * @param wireName 取常量的线上名字
     * @param name 待解析的名字；{@code null} 视为未知
     * @param <E> 常量的类型
     * @return 常量；名字未知时为空
     */
    public static <E> Optional<E> find(E[] constants, Function<? super E, String> wireName, String name) {
        for (E constant : constants) {
            if (wireName.apply(constant).equals(name)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }
}
