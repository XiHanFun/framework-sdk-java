package com.xihanfun.framework.primitives;

import com.xihanfun.framework.internal.Whitespace;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;

/**
 * 可恢复失败的描述（RFC-0001 Error）：错误码面向程序，消息键与参数面向人。
 *
 * <p>不可变、线程安全，{@code with…} 方法返回新实例。每个错误要么带消息键（由 {@link #of} 与按 KIND 的工厂创建，文案在语言文件里），
 * 要么带已解析的文案（由 {@link #literal} 创建，用于线上还原与异常转换）；从线上还原的错误可以两者都有。
 *
 * <p>注意本类型与 {@link java.lang.Error} 同名：在其他包里使用时显式导入
 * {@code com.xihanfun.framework.primitives.Error}；它不是 {@link Throwable}，误用会在编译期报错。
 */
public final class Error {
    /** 错误码。 */
    private final String code;

    /** 消息键；Literal 创建的为 {@code null}。 */
    private final String messageKey;

    /** 已解析的文案；未解析为 {@code null}。 */
    private final String text;

    /** 模板参数，也是结构化细节；只读、按键排序。 */
    private final SortedMap<String, Object> details;

    /** 内部错误；没有为 {@code null}。 */
    private final Error inner;

    private Error(String code, String messageKey, String text, SortedMap<String, Object> details, Error inner) {
        this.code = code;
        this.messageKey = messageKey;
        this.text = text;
        this.details = details;
        this.inner = inner;
    }

    /**
     * 用错误码与消息键创建错误，没有细节与内部错误。
     *
     * @param code 错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码或消息键不合法
     */
    public static Error of(String code, String messageKey) {
        return of(code, messageKey, Map.of());
    }

    /**
     * 用错误码、消息键与细节创建错误，没有内部错误。
     *
     * @param code 错误码
     * @param messageKey 消息键
     * @param details 细节，同时是模板参数；只允许 JSON 可表示的值
     * @return 错误
     * @throws IllegalArgumentException 错误码、消息键或细节不合法
     */
    public static Error of(String code, String messageKey, Map<String, ?> details) {
        return create(code, messageKey, details, null);
    }

    /**
     * 用错误码、消息键、细节与内部错误创建错误。
     *
     * @param code 错误码
     * @param messageKey 消息键
     * @param details 细节，同时是模板参数；只允许 JSON 可表示的值
     * @param inner 内部错误
     * @return 错误
     * @throws IllegalArgumentException 错误码、消息键或细节不合法
     */
    public static Error of(String code, String messageKey, Map<String, ?> details, Error inner) {
        return create(code, messageKey, details, Objects.requireNonNull(inner, "inner"));
    }

    /**
     * 用已解析的文案创建错误：线上还原与异常转换用，没有消息键。
     *
     * @param code 错误码
     * @param text 已解析的文案，不能为空，也不能只有空白（RFC-0001「空白字符」）
     * @return 错误
     * @throws IllegalArgumentException 错误码不合法，或文案为空、只有空白
     */
    public static Error literal(String code, String text) {
        return literal(code, text, Map.of());
    }

    /**
     * 用已解析的文案与细节创建错误。
     *
     * @param code 错误码
     * @param text 已解析的文案，不能为空，也不能只有空白（RFC-0001「空白字符」）
     * @param details 细节；只允许 JSON 可表示的值
     * @return 错误
     * @throws IllegalArgumentException 错误码、文案或细节不合法
     */
    public static Error literal(String code, String text, Map<String, ?> details) {
        return new Error(ErrorCode.validate(code), null, requireText(text), JsonValues.copyObject(details), null);
    }

    /**
     * 用已解析的文案、细节与内部错误创建错误。
     *
     * @param code 错误码
     * @param text 已解析的文案，不能为空，也不能只有空白（RFC-0001「空白字符」）
     * @param details 细节；只允许 JSON 可表示的值
     * @param inner 内部错误
     * @return 错误
     * @throws IllegalArgumentException 错误码、文案或细节不合法
     */
    public static Error literal(String code, String text, Map<String, ?> details, Error inner) {
        return new Error(ErrorCode.validate(code), null, requireText(text), JsonValues.copyObject(details),
                Objects.requireNonNull(inner, "inner"));
    }

    /**
     * 创建 VALIDATION 类错误。
     *
     * @param code KIND 段为 {@code VALIDATION} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error validation(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_VALIDATION, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 VALIDATION 类错误。
     *
     * @param code KIND 段为 {@code VALIDATION} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error validation(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_VALIDATION, code, messageKey, details, null);
    }

    /**
     * 创建 NOTFOUND 类错误。
     *
     * @param code KIND 段为 {@code NOTFOUND} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error notFound(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_NOT_FOUND, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 NOTFOUND 类错误。
     *
     * @param code KIND 段为 {@code NOTFOUND} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error notFound(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_NOT_FOUND, code, messageKey, details, null);
    }

    /**
     * 创建 CONFLICT 类错误。
     *
     * @param code KIND 段为 {@code CONFLICT} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error conflict(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_CONFLICT, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 CONFLICT 类错误。
     *
     * @param code KIND 段为 {@code CONFLICT} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error conflict(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_CONFLICT, code, messageKey, details, null);
    }

    /**
     * 创建 DENIED 类错误。
     *
     * @param code KIND 段为 {@code DENIED} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error denied(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_DENIED, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 DENIED 类错误。
     *
     * @param code KIND 段为 {@code DENIED} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error denied(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_DENIED, code, messageKey, details, null);
    }

    /**
     * 创建 UNAVAILABLE 类错误。
     *
     * @param code KIND 段为 {@code UNAVAILABLE} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error unavailable(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_UNAVAILABLE, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 UNAVAILABLE 类错误。
     *
     * @param code KIND 段为 {@code UNAVAILABLE} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error unavailable(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_UNAVAILABLE, code, messageKey, details, null);
    }

    /**
     * 创建 TIMEOUT 类错误。
     *
     * @param code KIND 段为 {@code TIMEOUT} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error timeout(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_TIMEOUT, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 TIMEOUT 类错误。
     *
     * @param code KIND 段为 {@code TIMEOUT} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error timeout(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_TIMEOUT, code, messageKey, details, null);
    }

    /**
     * 创建 CANCELLED 类错误。
     *
     * @param code KIND 段为 {@code CANCELLED} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error cancelled(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_CANCELLED, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 CANCELLED 类错误。
     *
     * @param code KIND 段为 {@code CANCELLED} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error cancelled(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_CANCELLED, code, messageKey, details, null);
    }

    /**
     * 创建 UNEXPECTED 类错误。
     *
     * @param code KIND 段为 {@code UNEXPECTED} 的错误码
     * @param messageKey 消息键
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段或消息键不合法
     */
    public static Error unexpected(String code, String messageKey) {
        return ofKind(ErrorCode.KIND_UNEXPECTED, code, messageKey, Map.of(), null);
    }

    /**
     * 创建带细节的 UNEXPECTED 类错误。
     *
     * @param code KIND 段为 {@code UNEXPECTED} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error unexpected(String code, String messageKey, Map<String, ?> details) {
        return ofKind(ErrorCode.KIND_UNEXPECTED, code, messageKey, details, null);
    }

    /**
     * 创建带细节与内部错误的 UNEXPECTED 类错误；参数顺序与 {@link #of(String, String, Map, Error)} 一致，先细节后内部错误。
     *
     * @param code KIND 段为 {@code UNEXPECTED} 的错误码
     * @param messageKey 消息键
     * @param details 细节
     * @param inner 内部错误
     * @return 错误
     * @throws IllegalArgumentException 错误码、KIND 段、消息键或细节不合法
     */
    public static Error unexpected(String code, String messageKey, Map<String, ?> details, Error inner) {
        return ofKind(ErrorCode.KIND_UNEXPECTED, code, messageKey, details, Objects.requireNonNull(inner, "inner"));
    }

    /**
     * 从线上编码还原错误（RFC-0001「Result」）：线上的 {@code message} 必填，是发送端已解析的文案，还原为 {@link #text()}；
     * 有消息键时接收端可以按自己的目录再翻译。
     *
     * <p>供 JSON 编码与协议适配器使用；业务代码用 {@link #of} 或 {@link #literal}。
     *
     * @param code 错误码
     * @param messageKey 消息键；空表示没有（Literal）
     * @param message 发送端已解析的文案，不能为空，也不能只有空白（RFC-0001「空白字符」）
     * @param details 细节
     * @param inner 内部错误；空表示没有
     * @return 错误
     * @throws IllegalArgumentException 错误码、消息键或细节不合法，或文案为空、只有空白
     */
    public static Error restore(String code, Optional<String> messageKey, String message, Map<String, ?> details, Optional<Error> inner) {
        ErrorCode.validate(code);
        messageKey.ifPresent(MessageTemplate::validateKey);
        return new Error(code, messageKey.orElse(null), requireText(message), JsonValues.copyObject(details), inner.orElse(null));
    }

    /**
     * 返回错误码。
     *
     * @return 错误码 {@code <领域大写>_<KIND>_<NNN>}
     */
    public String code() {
        return code;
    }

    /**
     * 返回错误码的 KIND 段。
     *
     * @return KIND 段
     */
    public String kind() {
        return ErrorCode.kindOf(code);
    }

    /**
     * 返回错误码的领域段。
     *
     * @return 领域段
     */
    public String domain() {
        return ErrorCode.domainOf(code);
    }

    /**
     * 返回消息键。
     *
     * @return 消息键；Literal 创建的没有
     */
    public Optional<String> messageKey() {
        return Optional.ofNullable(messageKey);
    }

    /**
     * 返回已解析的文案。
     *
     * @return 文案；未解析时没有
     */
    public Optional<String> text() {
        return Optional.ofNullable(text);
    }

    /**
     * 返回细节，也是消息模板的参数。
     *
     * @return 只读、按键排序的细节；没有时为空表
     */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * 返回内部错误。
     *
     * @return 内部错误；没有时为空
     */
    public Optional<Error> inner() {
        return Optional.ofNullable(inner);
    }

    /**
     * 返回面向人的消息：已解析的文案；否则按进程默认区域解析消息键；再否则是 {@code 键 (参数)} 的描述。
     *
     * <p>消息不参与程序判断，程序只看 {@link #code()}。
     *
     * @return 消息
     */
    public String message() {
        if (text != null) {
            return text;
        }
        if (messageKey == null) {
            return code;
        }
        return MessageScope.resolve(messageKey, details).orElseGet(() -> MessageTemplate.describe(messageKey, details));
    }

    /**
     * 换错误码，保留消息键、文案、细节与内部错误。
     *
     * @param newCode 新的错误码
     * @return 新的错误
     * @throws IllegalArgumentException 错误码不合法
     */
    public Error withCode(String newCode) {
        return new Error(ErrorCode.validate(newCode), messageKey, text, details, inner);
    }

    /**
     * 填入已解析的文案。
     *
     * @param newText 文案，不能为空，也不能只有空白（RFC-0001「空白字符」）
     * @return 新的错误
     * @throws IllegalArgumentException 文案为空或只有空白
     */
    public Error withText(String newText) {
        return new Error(code, messageKey, requireText(newText), details, inner);
    }

    /**
     * 按全部字段比较：错误码、消息键、文案、细节与内部错误。
     *
     * @param other 另一个对象
     * @return 全部字段相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Error that && code.equals(that.code) && Objects.equals(messageKey, that.messageKey)
                && Objects.equals(text, that.text) && details.equals(that.details) && Objects.equals(inner, that.inner);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(code, messageKey, text, details, inner);
    }

    /**
     * 返回 {@code 码: 消息}，便于日志。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return code + ": " + message();
    }

    private static Error create(String code, String messageKey, Map<String, ?> details, Error inner) {
        return new Error(ErrorCode.validate(code), MessageTemplate.validateKey(messageKey), null, JsonValues.copyObject(details), inner);
    }

    private static Error ofKind(String kind, String code, String messageKey, Map<String, ?> details, Error inner) {
        // 工厂方法的名字就是 KIND：码的 KIND 段与它不符是调用方写错了码
        if (!kind.equals(ErrorCode.kindOf(code))) {
            throw new IllegalArgumentException("The error code '" + code + "' must have the kind " + kind + ".");
        }
        return create(code, messageKey, details, inner);
    }

    private static String requireText(String text) {
        Objects.requireNonNull(text, "text");
        // 文案至少要有一个空白之外的字符（RFC-0001「空白字符」，ADR-0245）：只有空白与空串一样不是文案
        if (Whitespace.isBlank(text)) {
            throw new IllegalArgumentException("A literal error needs a text that is not empty or whitespace.");
        }
        return text;
    }
}
