package com.xihanfun.framework.operation;

import java.util.Optional;

/**
 * 操作暴露给哪些协议（插件协议 {@code OperationDeclaration.expose} 的取值）。
 */
public enum Expose {
    /** HTTP 路由。 */
    HTTP("http"),
    /** gRPC 方法。 */
    GRPC("grpc"),
    /** MCP tool。 */
    MCP("mcp"),
    /** 命令行。 */
    COMMAND_LINE("commandline"),
    /** AI 工具目录（{@code ai.tools@1}）。 */
    AI("ai");

    /** 协议名。 */
    private final String wireName;

    Expose(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按协议名解析。
     *
     * @param name 协议名，如 {@code http}
     * @return 协议；名字未知时为空
     */
    public static Optional<Expose> parse(String name) {
        for (Expose expose : values()) {
            if (expose.wireName.equals(name)) {
                return Optional.of(expose);
            }
        }
        return Optional.empty();
    }

    /**
     * 返回协议名。
     *
     * @return 协议名
     */
    public String wireName() {
        return wireName;
    }
}
