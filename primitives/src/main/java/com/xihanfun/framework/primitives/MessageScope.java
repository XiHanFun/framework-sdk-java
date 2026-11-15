package com.xihanfun.framework.primitives;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 进程默认的消息解析器与区域（RFC-0001 MessageScope.Default）：{@link Error#message()} 在没有已解析文案时用它。
 *
 * <p>线程安全：解析器与区域作为一份不可变快照整体替换，读取不加锁。
 */
public final class MessageScope {
    /** 默认区域，也是语言文件的基准。 */
    public static final String DEFAULT_LOCALE = "en-US";

    /** 区域名的最大长度（RFC-0010 LocaleName.MaxLength）。 */
    public static final int MAX_LOCALE_LENGTH = 35;

    /** 区域名文法（RFC-0010）。 */
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    /** 当前快照；解析器为 {@code null} 表示没有设置。 */
    private static volatile Snapshot current = new Snapshot(null, DEFAULT_LOCALE);

    private MessageScope() {
    }

    /**
     * 设置进程默认的解析器与区域。
     *
     * @param resolver 解析器
     * @param locale 区域名（BCP 47）
     * @throws IllegalArgumentException 区域名不合法
     */
    public static void setDefault(MessageResolver resolver, String locale) {
        current = new Snapshot(Objects.requireNonNull(resolver, "resolver"), validateLocale(locale));
    }

    /**
     * 撤销进程默认的解析器，区域回到 {@link #DEFAULT_LOCALE}。
     */
    public static void resetDefault() {
        current = new Snapshot(null, DEFAULT_LOCALE);
    }

    /**
     * 返回进程默认区域。
     *
     * @return 区域名
     */
    public static String defaultLocale() {
        return current.locale();
    }

    /**
     * 返回进程默认的解析器。
     *
     * @return 解析器；没有设置时为空
     */
    public static Optional<MessageResolver> defaultResolver() {
        return Optional.ofNullable(current.resolver());
    }

    /**
     * 用进程默认的解析器与区域解析消息键。
     *
     * @param key 消息键
     * @param arguments 模板参数
     * @return 文案；没有解析器或解析失败时为空
     */
    public static Optional<String> resolve(String key, Map<String, ?> arguments) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(arguments, "arguments");
        // 读一次快照：解析器与区域来自同一次设置，不会读到一半新一半旧
        Snapshot snapshot = current;
        if (snapshot.resolver() == null) {
            return Optional.empty();
        }
        return snapshot.resolver().resolve(key, snapshot.locale(), arguments);
    }

    /**
     * 判断区域名是否合法：文法 {@code ^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$}，最长 35 个字符。
     *
     * @param locale 区域名；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValidLocale(String locale) {
        return locale != null && locale.length() <= MAX_LOCALE_LENGTH && LOCALE_PATTERN.matcher(locale).matches();
    }

    /**
     * 校验区域名并原样返回。
     *
     * @param locale 区域名
     * @return 原样返回 {@code locale}
     * @throws IllegalArgumentException 区域名不合法
     */
    public static String validateLocale(String locale) {
        Objects.requireNonNull(locale, "locale");
        if (!isValidLocale(locale)) {
            throw new IllegalArgumentException("The locale '" + locale + "' must be a BCP 47 name such as 'en-US'.");
        }
        return locale;
    }

    /** 解析器与区域的一份快照。 */
    private record Snapshot(MessageResolver resolver, String locale) {
    }
}
