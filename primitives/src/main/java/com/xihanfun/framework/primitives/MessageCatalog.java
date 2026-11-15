package com.xihanfun.framework.primitives;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 内存里的消息目录：区域 → 消息键 → 模板（RFC-0010）。
 *
 * <p>不可变、线程安全。解析顺序：请求的区域，没有该键时回退到 {@code en-US}；区域名比较不区分大小写。
 * 模板严格渲染：有占位符缺参数即视为解析失败，由调用方退回描述。
 */
public final class MessageCatalog implements MessageResolver {
    /** 规范形式的区域名 → 只读的键 → 模板。 */
    private final Map<String, Map<String, String>> locales;

    private MessageCatalog(Map<String, Map<String, String>> locales) {
        this.locales = locales;
    }

    /**
     * 用区域到模板表的映射创建目录。
     *
     * @param locales 区域名（BCP 47）→ 消息键 → 模板
     * @return 目录
     * @throws IllegalArgumentException 区域名或消息键不合法，或两个区域名只差大小写
     */
    public static MessageCatalog of(Map<String, Map<String, String>> locales) {
        Objects.requireNonNull(locales, "locales");
        TreeMap<String, Map<String, String>> copy = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> entry : locales.entrySet()) {
            String locale = canonical(MessageScope.validateLocale(entry.getKey()));
            TreeMap<String, String> templates = new TreeMap<>();
            for (Map.Entry<String, String> template : entry.getValue().entrySet()) {
                templates.put(MessageTemplate.validateKey(template.getKey()), Objects.requireNonNull(template.getValue(), "template"));
            }
            if (copy.put(locale, Map.copyOf(templates)) != null) {
                throw new IllegalArgumentException("The catalog declares the locale '" + locale + "' more than once.");
            }
        }
        return new MessageCatalog(Map.copyOf(copy));
    }

    /**
     * 列出目录里的区域名（规范形式）。
     *
     * @return 区域名
     */
    public Set<String> locales() {
        return locales.keySet();
    }

    /**
     * 查找模板：先按请求的区域，没有该键时回退到 {@code en-US}。
     *
     * @param key 消息键
     * @param locale 区域名
     * @return 模板；都没有时为空
     */
    public Optional<String> findTemplate(String key, String locale) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(locale, "locale");
        Map<String, String> templates = locales.get(canonical(locale));
        if (templates != null && templates.containsKey(key)) {
            return Optional.of(templates.get(key));
        }
        Map<String, String> fallback = locales.get(MessageScope.DEFAULT_LOCALE);
        return fallback == null ? Optional.empty() : Optional.ofNullable(fallback.get(key));
    }

    /**
     * 解析消息键：找到模板后按区域严格渲染。
     *
     * @param key 消息键
     * @param locale 区域名
     * @param arguments 模板参数
     * @return 文案；没有模板或占位符缺参数时为空
     */
    @Override
    public Optional<String> resolve(String key, String locale, Map<String, ?> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return findTemplate(key, locale)
                .flatMap(template -> MessageTemplate.tryFormat(template, arguments, Locale.forLanguageTag(locale)));
    }

    /**
     * 区域名的规范形式：语言小写、地区大写、文字首字母大写，其余小写。
     */
    private static String canonical(String locale) {
        String[] parts = locale.split("-");
        StringBuilder builder = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            builder.append('-');
            if (part.length() == 2) {
                builder.append(part.toUpperCase(Locale.ROOT));
            } else if (part.length() == 4) {
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1).toLowerCase(Locale.ROOT));
            } else {
                builder.append(part.toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }
}
