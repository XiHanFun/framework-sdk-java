package com.xihanfun.framework.plugin;

import com.xihanfun.framework.internal.Whitespace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一个本机 gRPC 端点：网络类型与地址（套接字路径、{@code host:port} 或管道名），以及握手行与回调端点列表的文法（{@code handshake.md} §2、§4）。
 *
 * @param network 网络类型
 * @param address 地址
 */
record Endpoint(Network network, String address) {
    /** 握手行格式版本 1 的前缀，含其后的一个空格。 */
    private static final String HANDSHAKE_LINE_PREFIX = PluginRuntime.HANDSHAKE_LINE_VERSION + " ";

    Endpoint {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(address, "address");
    }

    /**
     * 解析 {@code <network> <address>}（{@code handshake.md} §2）：network 是 {@code unix}、{@code tcp} 或 {@code npipe}，与地址之间恰好一个空格；
     * 地址是一个或多个空白（{@link Whitespace}）之外的字符（含非 ASCII 字母与 {@code ;}），{@code tcp} 的地址另须是回环地址加端口
     * （{@link #isLoopbackAddress(String)}）。
     */
    static Optional<Endpoint> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        int space = text.indexOf(' ');
        if (space < 0) {
            return Optional.empty();
        }
        Optional<Network> network = Network.parse(text.substring(0, space));
        String address = text.substring(space + 1);
        if (network.isEmpty() || address.isEmpty() || Whitespace.containsWhitespace(address)) {
            return Optional.empty();
        }
        if (network.get() == Network.TCP && !isLoopbackAddress(address)) {
            return Optional.empty();
        }
        return Optional.of(new Endpoint(network.get(), address));
    }

    /**
     * 解析去掉行尾之后的握手行 {@code xihan-plugin/1 <network> <address>}（{@code handshake.md} §2）：前缀之后的部分同 {@link #parse(String)}。
     */
    static Optional<Endpoint> parseHandshakeLine(String line) {
        if (line == null || !line.startsWith(HANDSHAKE_LINE_PREFIX)) {
            return Optional.empty();
        }
        return parse(line.substring(HANDSHAKE_LINE_PREFIX.length()));
    }

    /**
     * 解析 {@code XIHAN_PLUGIN_HOST_ADDRESS}（{@code handshake.md} §4）：一个或多个 {@code <network> <address>}，按宿主的偏好排序、以 {@code ;} 分隔，
     * 每项同 {@link #parse(String)} 且地址不含 {@code ;}。任何一项不合法整个值就不合法；空串与 {@code null} 也不合法。
     *
     * @return 各项，按原顺序；不合法时为空
     */
    static Optional<List<Endpoint>> parseList(String value) {
        if (value == null) {
            return Optional.empty();
        }
        List<Endpoint> endpoints = new ArrayList<>();
        // 按 ; 切开后地址里自然没有 ;；空项（开头、结尾或连续的 ;）在 parse 里不合法
        for (String part : value.split(";", -1)) {
            Optional<Endpoint> endpoint = parse(part);
            if (endpoint.isEmpty()) {
                return Optional.empty();
            }
            endpoints.add(endpoint.get());
        }
        return Optional.of(List.copyOf(endpoints));
    }

    /**
     * 按 {@code handshake.md} §4 选回调端点：值合法时按顺序取第一个 network 在 {@code supported} 里的项，之后不换。
     *
     * @return 选中的端点；变量缺失、不合法或没有能连的项时为空
     */
    static Optional<Endpoint> select(String value, Set<Network> supported) {
        return parseList(value).flatMap(endpoints -> endpoints.stream().filter(endpoint -> supported.contains(endpoint.network())).findFirst());
    }

    /**
     * 判断 tcp 端点的地址是不是回环地址加端口（{@code handshake.md} §2）：IPv4 是 {@code 127.0.0.0/8} 的点分十进制，四段、每段 0–255、
     * 不带前导零；IPv6 只认 {@code [::1]}；端口是 1–65535 的十进制，不带正负号与前导零。主机名不算。
     */
    static boolean isLoopbackAddress(String address) {
        int separator = address.lastIndexOf(':');
        if (separator < 0) {
            return false;
        }
        int port = decimal(address.substring(separator + 1), 65535);
        if (port <= 0) {
            return false;
        }
        String host = address.substring(0, separator);
        if (host.equals("[::1]")) {
            return true;
        }
        // 只认四段写法：127.1、0177.0.0.1、2130706433、0x7f.0.0.1 这些操作系统也接受的写法一律不合法
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4 || !segments[0].equals("127")) {
            return false;
        }
        for (int index = 1; index < segments.length; index++) {
            if (decimal(segments[index], 255) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读不带正负号与前导零的十进制数（{@code "0"} 本身可以），只认 ASCII 数字。
     *
     * @return 数值；不合写法或超过 {@code limit} 时为 -1
     */
    private static int decimal(String text, int limit) {
        if (text.isEmpty() || (text.length() > 1 && text.charAt(0) == '0')) {
            return -1;
        }
        int value = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
            // 逐位比较上限，过长的数字不会溢出
            if (value > limit) {
                return -1;
            }
        }
        return value;
    }

    /**
     * 返回 {@code <network> <address>}：握手行与环境变量里的写法。
     */
    @Override
    public String toString() {
        return network.wireName() + " " + address;
    }
}
