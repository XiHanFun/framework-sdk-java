package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.Serial;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置节类型化读取里字符串叶子的转换（{@code lifecycle.md} §7.2）：宿主写出的配置节叶子一律是 JSON 字符串，读成数字时按 JSON 数字的文法，
 * 读成布尔时只认 {@code true}、{@code false}（ASCII 不区分大小写），其余照目标类型的常规读法。
 *
 * <p>Jackson 自己的字符串转换更宽（数字容许首尾空白、{@code +}、前导零与 {@code NaN}，布尔只认几种固定的大小写写法），所以在数字与布尔的
 * 标准反序列化器外面包一层：字符串先按规范核对，数字再当作同一段文本的 JSON 数字交给标准反序列化器，与配置节里直接写数字的读法相同。
 */
final class ConfigurationScalars extends SimpleModule {
    @Serial
    private static final long serialVersionUID = 1L;

    /** JSON 数字的文法（RFC 8259），整串匹配，只认 ASCII 数字。 */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    /** 读成数字的目标类型：基本类型、它们的包装类型与 {@link Number} 的标准实现；作者自己的数字类型照它自己的读法。 */
    private static final Set<Class<?>> NUMBERS = Set.of(byte.class, short.class, int.class, long.class, float.class, double.class, Byte.class,
            Short.class, Integer.class, Long.class, Float.class, Double.class, BigInteger.class, BigDecimal.class, Number.class);

    /** 把通过文法核对的文本重新读成 JSON 数字。 */
    private static final JsonFactory FACTORY = new JsonFactory();

    ConfigurationScalars() {
        super("xihan-plugin-configuration");
        setDeserializerModifier(new Modifier());
    }

    /** ASCII 不区分大小写地比较：只把 {@code A}–{@code Z} 换成小写，{@code ſ}、{@code ı} 这类大小写映射到 ASCII 的字符不算。 */
    static boolean asciiEqualsIgnoreCase(String text, String lower) {
        if (text.length() != lower.length()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            char folded = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (folded != lower.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /** 判断文本是不是 JSON 数字。 */
    static boolean isJsonNumber(String text) {
        return JSON_NUMBER.matcher(text).matches();
    }

    /** 给数字与布尔的标准反序列化器包上字符串的转换。 */
    private static final class Modifier extends BeanDeserializerModifier {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription description, JsonDeserializer<?> deserializer) {
            Class<?> type = description.getBeanClass();
            if (type == boolean.class || type == Boolean.class) {
                return new FromString(deserializer, true);
            }
            if (NUMBERS.contains(type)) {
                return new FromString(deserializer, false);
            }
            return deserializer;
        }
    }

    /** 字符串叶子按规范转换，其余记号交给原来的反序列化器。 */
    private static final class FromString extends DelegatingDeserializer {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 为真时目标是布尔，否则是数字。 */
        private final boolean bool;

        FromString(JsonDeserializer<?> delegate, boolean bool) {
            super(delegate);
            this.bool = bool;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new FromString(delegate, bool);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return _delegatee.deserialize(parser, context);
            }
            String text = parser.getText();
            if (bool) {
                if (asciiEqualsIgnoreCase(text, "true")) {
                    return Boolean.TRUE;
                }
                if (asciiEqualsIgnoreCase(text, "false")) {
                    return Boolean.FALSE;
                }
                return context.handleWeirdStringValue(handledType(), text, "only 'true' and 'false' read as a boolean");
            }
            if (!isJsonNumber(text)) {
                return context.handleWeirdStringValue(handledType(), text, "the text is not a JSON number");
            }
            // 同一段文本当作 JSON 数字读：整数与小数、超出范围的处理都与配置节里直接写数字时相同
            try (JsonParser number = FACTORY.createParser(text)) {
                number.nextToken();
                return _delegatee.deserialize(number, context);
            }
        }
    }
}
