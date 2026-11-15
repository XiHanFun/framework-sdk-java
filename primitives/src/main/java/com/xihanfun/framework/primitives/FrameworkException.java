package com.xihanfun.framework.primitives;

import java.io.Serial;
import java.util.Map;
import java.util.Objects;

/**
 * 带错误码的配置或编程错误（RFC-0001 FrameworkException）。
 *
 * <p>只用于不可恢复的配置错误与编程错误，例如清单不合法、管道顺序约束成环、操作重复登记；消息是面向开发者的英文、不本地化。
 * 可恢复的业务失败用 {@link Error} 与 {@link Result}，不抛异常。参数格式不合法这类调用方的编程错误抛
 * {@link IllegalArgumentException}，不用本类型。
 */
public class FrameworkException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 错误码。 */
    private final String code;

    /**
     * 创建异常，没有底层原因。
     *
     * @param code 符合 {@link ErrorCode} 格式的错误码
     * @param message 面向开发者的英文说明，不能为空串
     * @throws IllegalArgumentException 错误码不合法或说明为空
     */
    public FrameworkException(String code, String message) {
        this(code, message, null);
    }

    /**
     * 创建异常。
     *
     * @param code 符合 {@link ErrorCode} 格式的错误码
     * @param message 面向开发者的英文说明，不能为空串
     * @param cause 底层原因；可以为 {@code null}，表示没有
     * @throws IllegalArgumentException 错误码不合法或说明为空
     */
    public FrameworkException(String code, String message, Throwable cause) {
        super(requireMessage(message), cause);
        this.code = ErrorCode.validate(code);
    }

    /**
     * 返回错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 转换成可以上线的 {@link Error}：以说明为已解析文案的 Literal 错误。
     *
     * <p>底层原因也是 {@code FrameworkException} 时成为 {@link Error#inner()}，与 .NET 参考实现一致。
     *
     * @return 对应的错误
     */
    public Error toError() {
        if (getCause() instanceof FrameworkException framework) {
            return Error.literal(code, getMessage(), Map.of(), framework.toError());
        }
        return Error.literal(code, getMessage());
    }

    /**
     * 返回 {@code 码: 说明}。
     *
     * @return 便于日志的文本
     */
    @Override
    public String toString() {
        return code + ": " + getMessage();
    }

    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isEmpty()) {
            throw new IllegalArgumentException("A framework exception needs a message.");
        }
        return message;
    }
}
