package com.xihanfun.framework.primitives;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 错误码的文法与原语层登记的错误码。
 *
 * <p>错误码格式是 {@code <领域大写>_<KIND>_<NNN>}，例如 {@code PERSISTENCE_CONFLICT_001}；格式与登记表在
 * {@code spec/conventions/error-codes.md}。
 */
public final class ErrorCode {
    /** 原语参数非法：空 Code、空消息、清单的字段与跨字段约束等。 */
    public static final String INVALID_ARGUMENT = "PRIMITIVES_VALIDATION_001";

    /**
     * 使用了未初始化的 Result。
     *
     * <p>Java 没有结构体零值，本 SDK 不会产生这个错误码；保留常量是为了与登记表和其他语言一致。
     */
    public static final String UNINITIALIZED_RESULT = "PRIMITIVES_VALIDATION_002";

    /** KIND 段：参数或状态不合法。 */
    public static final String KIND_VALIDATION = "VALIDATION";

    /** KIND 段：对象不存在。 */
    public static final String KIND_NOT_FOUND = "NOTFOUND";

    /** KIND 段：并发或状态冲突。 */
    public static final String KIND_CONFLICT = "CONFLICT";

    /** KIND 段：没有权限。 */
    public static final String KIND_DENIED = "DENIED";

    /** KIND 段：依赖暂不可用。 */
    public static final String KIND_UNAVAILABLE = "UNAVAILABLE";

    /** KIND 段：超时。 */
    public static final String KIND_TIMEOUT = "TIMEOUT";

    /** KIND 段：被取消。 */
    public static final String KIND_CANCELLED = "CANCELLED";

    /** KIND 段：未预期的失败。 */
    public static final String KIND_UNEXPECTED = "UNEXPECTED";

    /** 错误码文法：三段大写标识，末段为三位数字。 */
    private static final Pattern PATTERN = Pattern.compile("^[A-Z][A-Z0-9]*_[A-Z][A-Z0-9]*_[0-9]{3}$");

    private ErrorCode() {
    }

    /**
     * 判断错误码是否符合 {@code <领域大写>_<KIND>_<NNN>}。
     *
     * @param code 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValid(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }

    /**
     * 校验错误码并原样返回，便于在初始化表达式中使用。
     *
     * @param code 错误码
     * @return 原样返回 {@code code}
     * @throws IllegalArgumentException 格式不合法
     */
    public static String validate(String code) {
        Objects.requireNonNull(code, "code");
        if (!isValid(code)) {
            throw new IllegalArgumentException("The error code '" + code + "' must match <DOMAIN>_<KIND>_<NNN>.");
        }
        return code;
    }

    /**
     * 取错误码的 KIND 段，例如 {@code PERSISTENCE_CONFLICT_001} 的 {@code CONFLICT}。
     *
     * @param code 错误码
     * @return KIND 段
     * @throws IllegalArgumentException 格式不合法
     */
    public static String kindOf(String code) {
        validate(code);
        // 格式已保证恰好三段，KIND 位于首个与末个下划线之间
        return code.substring(code.indexOf('_') + 1, code.lastIndexOf('_'));
    }

    /**
     * 取错误码的领域段，例如 {@code PERSISTENCE_CONFLICT_001} 的 {@code PERSISTENCE}。
     *
     * @param code 错误码
     * @return 领域段
     * @throws IllegalArgumentException 格式不合法
     */
    public static String domainOf(String code) {
        validate(code);
        return code.substring(0, code.indexOf('_'));
    }
}
