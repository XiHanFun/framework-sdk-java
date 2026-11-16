package com.xihanfun.framework.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * XGI 载荷的 JSON 编解码：JSON 是 XGI 的规范编码。
 */
public final class OperationJson {
    /** 读载荷的树：文本已过共同检查；ObjectNode 按文档顺序保留成员，整数与浮点的写法分得开（{@code 1} 与 {@code 1.0}）。 */
    private static final ObjectMapper TREES = JsonMapper.builder().build();

    /** 空载荷等价于 {@code {}}。 */
    private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.US_ASCII);

    private OperationJson() {
    }

    /**
     * 创建登记表缺省使用的 {@link ObjectMapper}：注册原语编码（{@code Maybe}、{@code TypedId}、{@code Result} 等），
     * 忽略请求里的未知字段（与 .NET 的缺省一致），类型不符一律失败——不把字符串转成数字、不把小数截成整数、
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
            return Result.fail(invalidPayload(id, problem.get().path()));
        }
        JsonNode tree;
        try {
            tree = TREES.readTree(text);
        } catch (IOException impossible) {
            throw new IllegalStateException("The payload passed the JSON text check but Jackson could not read it.", impossible);
        }
        if (tree.isNull()) {
            return Result.fail(invalidPayload(id, JsonText.ROOT));
        }
        JavaType javaType = mapper.constructType(type);
        Optional<String> path = shape.findProblem(tree, javaType);
        if (path.isPresent()) {
            return Result.fail(invalidPayload(id, path.get()));
        }
        T value;
        try {
            value = mapper.treeToValue(tree, type);
        } catch (JsonMappingException invalid) {
            return Result.fail(invalidPayload(id, pathOf(JsonText.ROOT, invalid)));
        } catch (IOException | IllegalArgumentException invalid) {
            return Result.fail(invalidPayload(id, JsonText.ROOT));
        }
        if (value == null) {
            return Result.fail(invalidPayload(id, JsonText.ROOT));
        }
        return Result.ok(value);
    }

    /**
     * 编码响应；编码失败是操作实现的缺陷，以 {@code KERNEL_UNEXPECTED_001} 结束调用。
     */
    static Result<byte[]> encode(ObjectMapper mapper, String id, Object value) {
        try {
            return Result.ok(mapper.writeValueAsBytes(value));
        } catch (JsonProcessingException failure) {
            return Result.fail(Error.literal(OperationErrors.UNEXPECTED, "The response of '" + id + "' could not be encoded: " + failure.getOriginalMessage()));
        }
    }

    static Error invalidPayload(String id, String reason) {
        return Error.validation(OperationErrors.INVALID_PAYLOAD, OperationMessages.INVALID_PAYLOAD, Map.of("id", id, "reason", reason));
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
