package com.xihanfun.framework.operation;

import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.xihanfun.framework.primitives.JsonText;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按请求类型以文本顺序核对载荷（RFC-0011 规则 2，ADR-0236），与 .NET 的 {@code JsonRequestShape} 同一套规则：反序列化之前先走一遍，
 * 报错的先后不随 Jackson 的内部路径（构造参数先读完、再填其余成员）或类型的声明方式变化。
 *
 * <ul>
 *   <li>成员名区分大小写，未知成员忽略；数字不从字符串读，字符串也不从数字读</li>
 *   <li>整数（{@code byte}、{@code short}、{@code int}、{@code long} 及其包装类型、{@link BigInteger}）不接受小数点与指数，超出类型的范围即不符；
 *       浮点（{@code float}、{@code double} 及其包装类型、{@link BigDecimal}）接受整数写法，上溢为无穷、下溢为 0</li>
 *   <li>{@code null}：字符串、数组、集合、字典、对象与任意 JSON（{@link JsonNode}、{@link Object}）接受，等于没有值；
 *       整数、浮点与布尔不接受，包装类型与它们的数组项、字典值同样不接受</li>
 *   <li>必填（{@code @JsonProperty(required = true)}）的成员缺少时，在所在对象结束时报该对象的路径；值为 {@code null} 也算出现</li>
 *   <li>按文本顺序报第一处问题：写在前面的类型不符先报，嵌套对象里缺少的成员先于外层后面的类型不符</li>
 *   <li>本类认不出的类型（枚举、日期时间、{@code Optional}、原语的 {@code Maybe} / {@code TypedId} 与其他自定义解码器、多态类型）
 *       在读到它的位置单独交给 Jackson，失败时报 Jackson 给出的路径</li>
 * </ul>
 *
 * <p>线程安全：按类型缓存分类与解码器的查找结果。
 */
final class RequestShape {
    private final ObjectMapper mapper;
    private final DefaultDeserializationContext lookup;
    private final Map<JavaType, Kind> kinds = new ConcurrentHashMap<>();
    private final Map<JavaType, BeanDeserializerBase> beans = new ConcurrentHashMap<>();

    /** 查找解码器的上下文不是线程安全的：查找只在第一次遇到一个类型时发生，加锁。 */
    private final ReentrantLock lock = new ReentrantLock();

    RequestShape(ObjectMapper mapper) {
        this.mapper = mapper;
        DeserializationConfig config = mapper.getDeserializationConfig();
        this.lookup = ((DefaultDeserializationContext) mapper.getDeserializationContext()).createDummyInstance(config);
    }

    /**
     * 找第一处问题。
     *
     * @param root 已过共同检查的载荷，不是 {@code null}
     * @param type 请求类型
     * @return 第一处问题的路径；载荷合请求类型时为空
     */
    Optional<String> findProblem(JsonNode root, JavaType type) {
        return check(root, type, JsonText.ROOT);
    }

    private Optional<String> check(JsonNode value, JavaType type, String path) {
        Kind kind = kindOf(type);
        if (value.isNull()) {
            return switch (kind) {
                case INTEGER, FLOAT, BOOLEAN -> Optional.of(path);
                case OTHER -> delegate(value, type, path);
                default -> Optional.empty();
            };
        }
        return switch (kind) {
            case ANY -> Optional.empty();
            case STRING -> value.isTextual() ? Optional.empty() : Optional.of(path);
            case BOOLEAN -> value.isBoolean() ? Optional.empty() : Optional.of(path);
            case FLOAT -> value.isNumber() ? Optional.empty() : Optional.of(path);
            case INTEGER -> fitsInteger(value, type) ? Optional.empty() : Optional.of(path);
            case ARRAY -> value.isArray() ? items(value, type.getContentType(), path) : Optional.of(path);
            case MAP -> value.isObject() ? entries(value, type.getContentType(), path) : Optional.of(path);
            case OBJECT -> value.isObject() ? members(value, type, path) : Optional.of(path);
            case OTHER -> delegate(value, type, path);
        };
    }

    private Optional<String> items(JsonNode array, JavaType itemType, String path) {
        for (int index = 0; index < array.size(); index++) {
            Optional<String> problem = check(array.get(index), itemType, JsonText.indexPath(path, index));
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private Optional<String> entries(JsonNode object, JavaType valueType, String path) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            Optional<String> problem = check(entry.getValue(), valueType, JsonText.memberPath(path, entry.getKey()));
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private Optional<String> members(JsonNode object, JavaType type, String path) {
        BeanDeserializerBase bean = beans.computeIfAbsent(type, key -> (BeanDeserializerBase) deserializer(key));
        for (Map.Entry<String, JsonNode> member : object.properties()) {
            SettableBeanProperty property = bean.findProperty(member.getKey());
            if (property == null) {
                // 未知成员忽略：它的内部已经过共同检查
                continue;
            }
            Optional<String> problem = check(member.getValue(), property.getType(), JsonText.memberPath(path, member.getKey()));
            if (problem.isPresent()) {
                return problem;
            }
        }
        // 缺少必填成员在对象结束时才发现，报的是这个对象的路径；值为 null 也算出现
        for (SettableBeanProperty property : properties(bean)) {
            if (property.isRequired() && !object.has(property.getName())) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /**
     * 本类认不出的类型交给 Jackson 读这一个值；失败时报 Jackson 给出的、相对于这个值的路径。
     */
    private Optional<String> delegate(JsonNode value, JavaType type, String path) {
        try {
            mapper.readerFor(type).readValue(value);
            return Optional.empty();
        } catch (JsonMappingException invalid) {
            return Optional.of(OperationJson.pathOf(path, invalid));
        } catch (IOException | IllegalArgumentException invalid) {
            return Optional.of(path);
        }
    }

    private static boolean fitsInteger(JsonNode value, JavaType type) {
        if (!value.isIntegralNumber()) {
            return false;
        }
        Class<?> raw = type.getRawClass();
        if (raw == BigInteger.class) {
            return true;
        }
        if (!value.canConvertToLong()) {
            return false;
        }
        long number = value.longValue();
        if (raw == byte.class || raw == Byte.class) {
            return number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE;
        }
        if (raw == short.class || raw == Short.class) {
            return number >= Short.MIN_VALUE && number <= Short.MAX_VALUE;
        }
        if (raw == int.class || raw == Integer.class) {
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
        }
        return true;
    }

    private static List<SettableBeanProperty> properties(BeanDeserializerBase bean) {
        List<SettableBeanProperty> all = new ArrayList<>();
        for (Iterator<SettableBeanProperty> iterator = bean.properties(); iterator.hasNext(); ) {
            all.add(iterator.next());
        }
        for (Iterator<SettableBeanProperty> iterator = bean.creatorProperties(); iterator.hasNext(); ) {
            SettableBeanProperty creator = iterator.next();
            if (all.stream().noneMatch(property -> property.getName().equals(creator.getName()))) {
                all.add(creator);
            }
        }
        return all;
    }

    private Kind kindOf(JavaType type) {
        return kinds.computeIfAbsent(type, this::classify);
    }

    private Kind classify(JavaType type) {
        Class<?> raw = type.getRawClass();
        if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) {
            return Kind.ANY;
        }
        if (raw == String.class || raw == CharSequence.class) {
            return Kind.STRING;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return Kind.BOOLEAN;
        }
        if (raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class || raw == int.class || raw == Integer.class
                || raw == long.class || raw == Long.class || raw == BigInteger.class) {
            return Kind.INTEGER;
        }
        if (raw == float.class || raw == Float.class || raw == double.class || raw == Double.class || raw == BigDecimal.class) {
            return Kind.FLOAT;
        }
        // byte[] 在 Jackson 里是 Base64 字符串
        if (raw == byte[].class) {
            return Kind.OTHER;
        }
        if (type.isArrayType() || type.isCollectionLikeType()) {
            return Kind.ARRAY;
        }
        if (type.isMapLikeType()) {
            return Kind.MAP;
        }
        JsonDeserializer<?> deserializer = deserializer(type);
        return deserializer instanceof BeanDeserializerBase ? Kind.OBJECT : Kind.OTHER;
    }

    private JsonDeserializer<?> deserializer(JavaType type) {
        lock.lock();
        try {
            return lookup.findRootValueDeserializer(type);
        } catch (JsonMappingException unsupported) {
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** 载荷里的值按请求类型分成的几种。 */
    private enum Kind {
        ANY,
        STRING,
        BOOLEAN,
        INTEGER,
        FLOAT,
        ARRAY,
        MAP,
        OBJECT,
        OTHER
    }
}
