package com.xihanfun.framework.operation;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import java.util.Map;

/**
 * XGI 载荷的 JSON 编解码：JSON 是 XGI 的规范编码。
 */
public final class OperationJson {
    private OperationJson() {
    }

    /**
     * 创建登记表缺省使用的 {@link ObjectMapper}：注册原语编码（{@code Maybe}、{@code TypedId}、{@code Result} 等），
     * 忽略请求里的未知字段（与 Go 和 .NET 的缺省一致），类型不符一律失败——不把字符串转成数字、不把小数截成整数、
     * 不把数字或布尔转成字符串，JSON 之后多余的内容也算失败。
     *
     * <p>要定制编码时以它为起点修改，再传给 {@link OperationRegistry#OperationRegistry(ObjectMapper)}。
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
     * 解码请求载荷：空或只有空白等价于 {@code {}}；非 JSON、{@code null} 或类型不匹配为 {@code KERNEL_VALIDATION_001}，
     * {@code reason} 只写 JSON 路径（RFC-0011 规则 2）。
     */
    static <T> Result<T> decode(ObjectMapper mapper, String id, byte[] payload, Class<T> type) {
        JsonNode tree;
        try {
            tree = isBlank(payload) ? mapper.createObjectNode() : mapper.readTree(payload);
        } catch (IOException invalid) {
            return Result.fail(invalidPayload(id, pathOf(invalid)));
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            return Result.fail(invalidPayload(id, "the payload is null"));
        }
        T value;
        try {
            value = mapper.treeToValue(tree, type);
        } catch (IOException | IllegalArgumentException invalid) {
            return Result.fail(invalidPayload(id, pathOf(invalid)));
        }
        if (value == null) {
            return Result.fail(invalidPayload(id, "the payload is null"));
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

    private static boolean isBlank(byte[] payload) {
        for (byte value : payload) {
            if (value != ' ' && value != '\t' && value != '\n' && value != '\r') {
                return false;
            }
        }
        return true;
    }

    /**
     * 取出错位置的 JSON 路径，如 {@code $.items[0].quantity}；不带目标类型名，免得把实现细节暴露给调用方。
     */
    private static String pathOf(Throwable failure) {
        if (failure instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            StringBuilder path = new StringBuilder("$");
            for (JsonMappingException.Reference reference : mapping.getPath()) {
                if (reference.getFieldName() != null) {
                    path.append('.').append(reference.getFieldName());
                } else if (reference.getIndex() >= 0) {
                    path.append('[').append(reference.getIndex()).append(']');
                }
            }
            return path.toString();
        }
        if (failure instanceof JsonParseException parse && parse.getProcessor() instanceof JsonParser parser) {
            return pointerPath(parser.getParsingContext().pathAsPointer());
        }
        return "$";
    }

    private static String pointerPath(JsonPointer pointer) {
        StringBuilder path = new StringBuilder("$");
        for (JsonPointer current = pointer; current != null && !current.matches(); current = current.tail()) {
            if (current.mayMatchElement()) {
                path.append('[').append(current.getMatchingIndex()).append(']');
            } else {
                path.append('.').append(current.getMatchingProperty());
            }
        }
        return path.toString();
    }
}
