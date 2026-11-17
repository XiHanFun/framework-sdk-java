package com.xihanfun.framework.plugin;

import java.util.Optional;

/**
 * 本机 gRPC 端点的网络类型（{@code handshake.md} §2、§4）。
 *
 * <p>本 SDK 监听时用 {@link #UNIX}（Linux 上有 epoll 原生库时）或 {@link #TCP}（只绑定回环地址），不监听命名管道；
 * 连接宿主回调端点时支持 {@link #TCP}，以及 Linux 上的 {@link #UNIX}。
 */
public enum Network {
    /** Unix 域套接字。 */
    UNIX("unix"),
    /** TCP，只用回环地址。 */
    TCP("tcp"),
    /** Windows 命名管道。 */
    NPIPE("npipe");

    /** 握手行与环境变量里的名字。 */
    private final String wireName;

    Network(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按名字解析，区分大小写。
     *
     * @param name 名字，如 {@code unix}
     * @return 网络类型；名字未知时为空
     */
    public static Optional<Network> parse(String name) {
        for (Network network : values()) {
            if (network.wireName.equals(name)) {
                return Optional.of(network);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回握手行与环境变量里的名字。
     *
     * @return 名字
     */
    public String wireName() {
        return wireName;
    }
}
