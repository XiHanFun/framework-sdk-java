package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 配置节类型化读取的成员名匹配（{@code lifecycle.md} §7.2）：成员名按 ASCII 不区分大小写对上目标类型的属性名，与宿主的 {@code IConfiguration} 一致。
 *
 * <p>Jackson 的 {@code ACCEPT_CASE_INSENSITIVE_PROPERTIES} 按 Unicode 换小写再比较，会把 U+212A（开尔文符号）当作 {@code k}、把非 ASCII 字母的大小写
 * 当作相同，比规范宽；所以在交给 Jackson 之前，按目标类型的属性逐层改写成员名：原样相同的优先，其次只把 {@code A}–{@code Z} 当作与小写相同；
 * 对不上的成员名原样留下，随后作为未知成员忽略。改写只换名字，值逐个记号原样复制，数字保留原文。
 */
final class ConfigurationNames {
    private ConfigurationNames() {
    }

    /**
     * 按目标类型改写配置节的成员名，返回新的 JSON 文本。
     *
     * @param text 已通过共同 JSON 读取检查的配置节（嵌套不超过 64 层，递归有界）
     * @param type 目标类型
     */
    static byte[] align(ObjectMapper mapper, String text, JavaType type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 16);
        try (JsonParser parser = mapper.getFactory().createParser(text); JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            parser.nextToken();
            copy(parser, generator, type, mapper.getDeserializationConfig());
        }
        return out.toByteArray();
    }

    /** 复制当前记号开始的一个值；{@code type} 为 {@code null} 表示不知道目标类型，成员名原样。 */
    private static void copy(JsonParser parser, JsonGenerator generator, JavaType type, DeserializationConfig config) throws IOException {
        JavaType target = type;
        // Optional<T> 这类引用类型按它包着的类型改写
        while (target != null && target.isReferenceType()) {
            target = target.getReferencedType();
        }
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT -> {
                List<BeanPropertyDefinition> properties = isBean(target) ? config.introspect(target).findProperties() : List.of();
                // 字典的键不是属性名，原样保留，值按字典的值类型继续
                JavaType values = target != null && target.isMapLikeType() ? target.getContentType() : null;
                generator.writeStartObject();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    BeanPropertyDefinition property = match(properties, name);
                    generator.writeFieldName(property == null ? name : property.getName());
                    parser.nextToken();
                    copy(parser, generator, property == null ? values : property.getPrimaryType(), config);
                }
                generator.writeEndObject();
            }
            case START_ARRAY -> {
                JavaType elements = target != null && (target.isArrayType() || target.isCollectionLikeType()) ? target.getContentType() : null;
                generator.writeStartArray();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    copy(parser, generator, elements, config);
                }
                generator.writeEndArray();
            }
            // 数字写原文：整数与小数、超出范围的读法都留给目标类型，与直接读配置节相同
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> generator.writeNumber(parser.getText());
            case VALUE_STRING -> generator.writeString(parser.getText());
            case VALUE_TRUE -> generator.writeBoolean(true);
            case VALUE_FALSE -> generator.writeBoolean(false);
            case VALUE_NULL -> generator.writeNull();
            default -> throw new IOException("Unexpected JSON token " + token + " in the configuration section.");
        }
    }

    /** 判断类型有没有要对上的属性：作者的类型（记录、普通类），不含基本类型、枚举、容器、JSON 树与 JDK 自己的类型。 */
    private static boolean isBean(JavaType type) {
        if (type == null || type.isPrimitive() || type.isEnumType() || type.isContainerType() || type.isReferenceType()) {
            return false;
        }
        Class<?> raw = type.getRawClass();
        return raw != Object.class && !JsonNode.class.isAssignableFrom(raw) && !raw.getName().startsWith("java.");
    }

    /** 找成员名对应的属性：原样相同的优先，其次 ASCII 不区分大小写相同的第一个；都没有时为 {@code null}。 */
    private static BeanPropertyDefinition match(List<BeanPropertyDefinition> properties, String name) {
        for (BeanPropertyDefinition property : properties) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        for (BeanPropertyDefinition property : properties) {
            if (asciiEqualsIgnoreCase(property.getName(), name)) {
                return property;
            }
        }
        return null;
    }

    /**
     * ASCII 不区分大小写地比较：只把 {@code A}–{@code Z} 与 {@code a}–{@code z} 当作相同，其余字符逐个原样比较；
     * {@code K}（U+212A）、{@code ſ}、{@code ı}、非 ASCII 字母的大小写都不算相同。
     */
    static boolean asciiEqualsIgnoreCase(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (int index = 0; index < left.length(); index++) {
            if (fold(left.charAt(index)) != fold(right.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static char fold(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }
}
