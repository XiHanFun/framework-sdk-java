package com.xihanfun.framework.plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * 一个本机 gRPC 端点：网络类型与地址（套接字路径、{@code host:port} 或管道名）。
 *
 * @param network 网络类型
 * @param address 地址
 */
record Endpoint(Network network, String address) {
    Endpoint {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(address, "address");
    }

    /**
     * 解析 {@code <network> <address>}（环境变量 {@code XIHAN_PLUGIN_HOST_ADDRESS} 的格式，{@code handshake.md} §1）：
     * 以第一个空格分开，地址非空；地址里可以再有空格（Windows 的路径）。
     */
    static Optional<Endpoint> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int space = text.indexOf(' ');
        if (space <= 0 || space == text.length() - 1) {
            return Optional.empty();
        }
        String address = text.substring(space + 1);
        return Network.parse(text.substring(0, space)).map(network -> new Endpoint(network, address));
    }

    /**
     * 返回 {@code <network> <address>}：握手行与环境变量里的写法。
     */
    @Override
    public String toString() {
        return network.wireName() + " " + address;
    }
}
