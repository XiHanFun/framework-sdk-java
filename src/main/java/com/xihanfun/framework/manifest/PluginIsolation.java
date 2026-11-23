package com.xihanfun.framework.manifest;

import com.xihanfun.framework.internal.WireNames;
import java.util.Optional;

/**
 * 插件隔离级别。
 */
public enum PluginIsolation {
    /** 进程内。 */
    IN_PROCESS("in-process"),
    /** Wasm 沙箱；插件里只有它能贡献中间件。 */
    WASM("wasm"),
    /** 进程外（插件协议 {@code xihan.plugin.v1}）。 */
    OUT_OF_PROCESS("out-of-process");

    /** 清单里的名字。 */
    private final String wireName;

    PluginIsolation(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按清单里的名字解析。
     *
     * @param name 名字，如 {@code out-of-process}
     * @return 隔离级别；名字未知时为空
     */
    public static Optional<PluginIsolation> parse(String name) {
        return WireNames.find(values(), PluginIsolation::wireName, name);
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
