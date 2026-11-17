package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.MessageCatalog;
import com.xihanfun.framework.primitives.MessageScope;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 插件在握手时交给宿主的一份消息目录：目录名，以及区域 → 消息键 → 模板（RFC-0010，{@code handshake.md} §3 第 6 条）。
 *
 * <p>宿主把它合并进自己的本地化器，按插件返回的错误里的消息键翻译文案，所以插件自己的消息键都要进目录；至少要有 {@code en-US}。
 * 不可变、线程安全。
 */
public final class PluginCatalog {
    /** 内置的 10 种区域（RFC-0010），{@code en-US} 是默认区域，也是语言文件的基准。 */
    public static final List<String> LOCALES = List.of(
            "en-US", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "de-DE", "es-ES", "fr-FR", "pt-BR", "ru-RU");

    /** 只用来读语言文件的树；文本先过共同的 JSON 文本检查。 */
    private static final ObjectMapper READER = new ObjectMapper();

    private final String name;

    /** 区域 → 只读的键 → 模板，区域按名字排序。 */
    private final Map<String, Map<String, String>> locales;

    /** 同一份模板的解析器形式，供插件进程里格式化错误文案。 */
    private final MessageCatalog messages;

    private PluginCatalog(String name, Map<String, Map<String, String>> locales, MessageCatalog messages) {
        this.name = name;
        this.locales = locales;
        this.messages = messages;
    }

    /**
     * 用区域到模板表的映射创建目录。
     *
     * @param name 目录名，通常取清单 ID 的领域段
     * @param locales 区域名（BCP 47）→ 消息键 → 模板
     * @return 目录
     * @throws IllegalArgumentException 目录名为空、没有 {@code en-US}，或区域名、消息键不合法
     */
    public static PluginCatalog of(String name, Map<String, Map<String, String>> locales) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(locales, "locales");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A message catalog needs a name.");
        }
        // MessageCatalog.of 核对区域名与消息键的文法，并拒绝只差大小写的两个区域名
        MessageCatalog messages = MessageCatalog.of(locales);
        if (!messages.locales().contains(MessageScope.DEFAULT_LOCALE)) {
            throw new IllegalArgumentException(
                    "The message catalog '" + name + "' has no '" + MessageScope.DEFAULT_LOCALE + "' templates.");
        }
        TreeMap<String, Map<String, String>> copy = new TreeMap<>();
        locales.forEach((locale, templates) -> copy.put(locale, Map.copyOf(new TreeMap<>(templates))));
        return new PluginCatalog(name, Map.copyOf(copy), messages);
    }

    /**
     * 按 {@link #LOCALES} 逐个读取语言文件 {@code <区域>.json}（RFC-0010：键 → 模板的平面 JSON 对象）；没有文件的区域跳过。
     *
     * <p>文件由调用方打开，资源因此不受模块封装的限制，例如
     * {@code PluginCatalog.load("echo", locale -> Echo.class.getResourceAsStream("Localization/" + locale + ".json"))}。
     *
     * @param name 目录名
     * @param files 区域名 → 语言文件的输入流；没有该区域时返回 {@code null}。流由本方法关闭
     * @return 目录
     * @throws IOException 文件读不出、不是合法的 JSON 文本，或不是值全为字符串的对象
     * @throws IllegalArgumentException 没有 {@code en-US}，或消息键不合法
     */
    public static PluginCatalog load(String name, Function<String, InputStream> files) throws IOException {
        Objects.requireNonNull(files, "files");
        TreeMap<String, Map<String, String>> locales = new TreeMap<>();
        for (String locale : LOCALES) {
            InputStream stream = files.apply(locale);
            if (stream == null) {
                continue;
            }
            byte[] bytes;
            try (stream) {
                bytes = stream.readAllBytes();
            }
            locales.put(locale, parse(locale, bytes));
        }
        return of(name, locales);
    }

    /**
     * 返回目录名。
     *
     * @return 目录名
     */
    public String name() {
        return name;
    }

    /**
     * 返回全部模板。
     *
     * @return 只读的区域 → 消息键 → 模板
     */
    public Map<String, Map<String, String>> locales() {
        return locales;
    }

    /**
     * 返回同一份模板的解析器：找不到请求区域的键时回退到 {@code en-US}。
     *
     * @return 解析器
     */
    public MessageCatalog messages() {
        return messages;
    }

    private static Map<String, String> parse(String locale, byte[] bytes) throws IOException {
        if (JsonText.check(bytes).isPresent()) {
            throw new IOException("The message catalog file for '" + locale + "' is not valid JSON text.");
        }
        JsonNode root = READER.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw new IOException("The message catalog file for '" + locale + "' must be a JSON object of message keys to templates.");
        }
        TreeMap<String, String> templates = new TreeMap<>();
        for (Map.Entry<String, JsonNode> member : root.properties()) {
            if (!member.getValue().isTextual()) {
                throw new IOException("The template of '" + member.getKey() + "' for '" + locale + "' must be a string.");
            }
            templates.put(member.getKey(), member.getValue().textValue());
        }
        return templates;
    }
}
