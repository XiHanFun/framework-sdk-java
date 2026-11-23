package com.xihanfun.framework.operation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.xihanfun.framework.invocation.InvocationErrors;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * XGI 载荷的 JSON 编解码：JSON 是 XGI 的规范编码。
 */
public final class OperationJson {
    /** 读载荷的树：文本已过共同检查；ObjectNode 按文档顺序保留成员，整数与浮点的写法分得开（{@code 1} 与 {@code 1.0}）。 */
    private static final ObjectMapper TREES = JsonMapper.builder().build();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** 未处理的异常写到这里，与 Observe 阶段的内置步骤同一个日志。 */
    private static final System.Logger LOGGER = System.getLogger(OperationInvoker.class.getName());

    /** 空载荷等价于 {@code {}}。 */
    private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.US_ASCII);

    private OperationJson() {
    }

    /**
     * 创建登记表缺省使用的 {@link ObjectMapper}：注册原语编码（{@code Maybe}、{@code TypedId}、{@code Result} 等），
     * 忽略请求里的未知字段（RFC-0011 规则 2），类型不符一律失败——不把字符串转成数字、不把小数截成整数、
     * 不把数字或布尔转成字符串。
     *
     * <p>解码时报错的先后与 {@code null} 的规则由 {@link RequestShape} 在反序列化之前按文本顺序核对，不靠这份配置；
     * 要定制编码时以它为起点修改，再传给 {@link OperationRegistry#OperationRegistry(ObjectMapper)}。
     *
     * @return 新的 ObjectMapper
     */
    public static ObjectMapper newMapper() {
        JsonMapper mapper = JsonMapper.builder()
                .addModule(new PrimitivesModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
        for (LogicalType numeric : new LogicalType[] {LogicalType.Integer, LogicalType.Float, LogicalType.Boolean}) {
            mapper.coercionConfigFor(numeric).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        }
        mapper.coercionConfigFor(LogicalType.Boolean).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        for (CoercionInputShape shape : new CoercionInputShape[] {CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean}) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        return mapper;
    }

    /**
     * 解码请求载荷（RFC-0011 规则 2，ADR-0235、ADR-0236），失败为 {@code KERNEL_VALIDATION_001}，{@code reason} 只写路径：
     * <ol>
     *   <li>空载荷等价于 {@code {}}；其余先过 RFC-0001「JSON 文本」的共同检查：语法错误（含只有空白、BOM、嵌套超过 64 层、非法 UTF-8、
     *       孤立的代理项）写 {@code $}，任何一层的重复成员写第一处重复的路径，先于其他问题</li>
     *   <li>载荷是 {@code null} 写 {@code $}</li>
     *   <li>按请求类型以文本顺序核对（{@link RequestShape}），报第一处问题</li>
     *   <li>交给 Jackson 反序列化；它只在本 SDK 认不出的写法上失败，报它给出的路径</li>
     * </ol>
     */
    static <T> Result<T> decode(ObjectMapper mapper, RequestShape shape, String id, byte[] payload, Class<T> type) {
        byte[] text = payload.length == 0 ? EMPTY_OBJECT : payload;
        Optional<JsonText.Problem> problem = JsonText.check(text);
        if (problem.isPresent()) {
            return Result.fail(OperationErrors.invalidPayload(id, problem.get().path()));
        }
        JsonNode tree = readTree(text);
        if (tree.isNull()) {
            return Result.fail(OperationErrors.invalidPayload(id, JsonText.ROOT));
        }
        JavaType javaType = mapper.constructType(type);
        Optional<String> path = shape.findProblem(tree, javaType);
        if (path.isPresent()) {
            return Result.fail(OperationErrors.invalidPayload(id, path.get()));
        }
        T value;
        try {
            value = mapper.treeToValue(tree, type);
        } catch (JsonMappingException invalid) {
            return Result.fail(OperationErrors.invalidPayload(id, pathOf(JsonText.ROOT, invalid)));
        } catch (IOException | IllegalArgumentException invalid) {
            return Result.fail(OperationErrors.invalidPayload(id, JsonText.ROOT));
        }
        if (value == null) {
            return Result.fail(OperationErrors.invalidPayload(id, JsonText.ROOT));
        }
        return Result.ok(value);
    }

    /**
     * 读已过共同检查的载荷：整数按 Jackson 的缺省（64 位以内是 int / long，超出是精确的 BigInteger）；浮点在双精度能表示时是 double，
     * 保留 {@code 2.0} 与 {@code -0.0}，上溢（{@code 1e400}）或把非零的数下溢成 0（{@code 1e-400}）时保留原文的精确十进制，
     * 所以任意 JSON 类型的成员写回时与读到的 JSON 值相等（RFC-0011 规则 2，ADR-0236）。按声明为浮点的成员解码时，精确十进制照常换成
     * 无穷或 0。
     */
    static JsonNode readTree(byte[] text) {
        try (JsonParser parser = TREES.createParser(text)) {
            parser.nextToken();
            return node(parser);
        } catch (IOException impossible) {
            throw new IllegalStateException("The payload passed the JSON text check but Jackson could not read it.", impossible);
        }
    }

    private static JsonNode node(JsonParser parser) throws IOException {
        switch (parser.currentToken()) {
            case START_OBJECT -> {
                ObjectNode object = NODES.objectNode();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName();
                    parser.nextToken();
                    object.set(name, node(parser));
                }
                return object;
            }
            case START_ARRAY -> {
                ArrayNode array = NODES.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    array.add(node(parser));
                }
                return array;
            }
            case VALUE_STRING -> {
                return NODES.textNode(parser.getText());
            }
            case VALUE_NUMBER_INT -> {
                return switch (parser.getNumberType()) {
                    case INT -> NODES.numberNode(parser.getIntValue());
                    case LONG -> NODES.numberNode(parser.getLongValue());
                    default -> NODES.numberNode(parser.getBigIntegerValue());
                };
            }
            case VALUE_NUMBER_FLOAT -> {
                double number = parser.getDoubleValue();
                if (Double.isInfinite(number) || (number == 0 && new BigDecimal(parser.getText()).signum() != 0)) {
                    return DecimalNode.valueOf(new BigDecimal(parser.getText()));
                }
                return NODES.numberNode(number);
            }
            case VALUE_TRUE -> {
                return NODES.booleanNode(true);
            }
            case VALUE_FALSE -> {
                return NODES.booleanNode(false);
            }
            default -> {
                return NODES.nullNode();
            }
        }
    }

    /**
     * 编码响应（RFC-0011 规则 10「编码」，ADR-0236）：在管道结束之后才编码，写不出时（类型化的浮点成员是 NaN 或无穷、序列化不支持的值）
     * 以 {@code KERNEL_UNEXPECTED_001} + {@code kernel.invocation.unhandled_exception} 结束，异常是框架异常时它的错误放进 {@code inner}，
     * 不抛给调用方。NaN 与无穷不写成字符串：编码时关掉 {@code WRITE_NAN_AS_STRINGS}，写出的文本再过共同的 JSON 文本检查，
     * Jackson 写出的 {@code NaN} 这类非 JSON 写法因此失败。
     */
    static Result<byte[]> encode(ObjectMapper mapper, String id, Object value) {
        byte[] payload;
        try {
            payload = mapper.writer().without(JsonWriteFeature.WRITE_NAN_AS_STRINGS).writeValueAsBytes(value);
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.ERROR, "The response of the operation '" + id + "' could not be encoded.", failure);
            Optional<FrameworkException> framework = frameworkCause(failure);
            return Result.fail(framework.map(cause -> InvocationErrors.unhandledException(cause.toError()))
                    .orElseGet(InvocationErrors::unhandledException));
        }
        if (JsonText.check(payload).isPresent()) {
            LOGGER.log(System.Logger.Level.ERROR, "The response of the operation '" + id + "' is not JSON (NaN, an infinity or a lone surrogate).");
            return Result.fail(InvocationErrors.unhandledException());
        }
        return Result.ok(payload);
    }

    /**
     * 沿原因链找框架异常：Jackson 把序列化器抛出的异常包在 JsonMappingException 里。链长有上限，成环的原因链不会死循环。
     */
    private static Optional<FrameworkException> frameworkCause(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof FrameworkException framework) {
                return Optional.of(framework);
            }
        }
        return Optional.empty();
    }

    /**
     * 把 Jackson 报出的出错位置接在 {@code prefix} 之后，如 {@code $.lines[1].quantity}；不带目标类型名，免得把实现细节暴露给调用方。
     */
    static String pathOf(String prefix, JsonMappingException failure) {
        StringBuilder path = new StringBuilder(prefix);
        for (JsonMappingException.Reference reference : failure.getPath()) {
            if (reference.getFieldName() != null) {
                path.replace(0, path.length(), JsonText.memberPath(path.toString(), reference.getFieldName()));
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
