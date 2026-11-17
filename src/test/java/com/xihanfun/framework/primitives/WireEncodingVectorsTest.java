package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.primitives.json.PrimitivesJson;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/primitives/wire-encoding.json}：合法的必须读得出、再写出时按 JSON 值等于规范形式，不合法的必须拒绝。
 */
class WireEncodingVectorsTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new PrimitivesModule());

    @Test
    void resultsFollowTheVectors() throws IOException {
        JsonNode results = Vectors.read("primitives/wire-encoding.json").get("results");
        for (JsonNode vector : results.get("valid")) {
            JavaType type = resultType(vector.get("kind").textValue());
            Object decoded = PrimitivesJson.read(mapper, mapper.writeValueAsBytes(vector.get("json")), type);
            JsonNode expected = vector.has("canonical") ? vector.get("canonical") : vector.get("json");
            assertEquals(expected, mapper.readTree(mapper.writeValueAsString(decoded)), vector.get("name").textValue());
        }
        for (JsonNode vector : results.get("invalid")) {
            JavaType type = resultType(vector.get("kind").textValue());
            String json = mapper.writeValueAsString(vector.get("json"));
            assertThrows(JsonProcessingException.class, () -> PrimitivesJson.read(mapper, json, type), vector.get("name").textValue() + " must be rejected");
        }
    }

    @Test
    void errorsFollowTheVectors() throws IOException {
        JsonNode errors = Vectors.read("primitives/wire-encoding.json").get("errors");
        for (JsonNode vector : errors.get("valid")) {
            Error decoded = PrimitivesJson.read(mapper, mapper.writeValueAsBytes(vector.get("json")), Error.class);
            JsonNode expected = vector.has("canonical") ? vector.get("canonical") : vector.get("json");
            assertEquals(expected, mapper.readTree(mapper.writeValueAsString(decoded)), vector.get("name").textValue());
        }
        for (JsonNode vector : errors.get("invalid")) {
            String json = mapper.writeValueAsString(vector.get("json"));
            assertThrows(JsonProcessingException.class, () -> PrimitivesJson.read(mapper, json, Error.class), vector.get("name").textValue() + " must be rejected");
        }
    }

    @Test
    void duplicateMembersAreRejected() throws IOException {
        // JSON 值表达不了重复的成员，向量给的是原文；任何一层重复都不合法，含 value、details 与 inner 的内部（ADR-0235、ADR-0236）。
        // 解码器自己核对（不经使用者的 ObjectMapper 配置），经 PrimitivesJson 读文本时共同检查先报
        ObjectMapper lenient = new ObjectMapper().registerModule(new PrimitivesModule());
        for (JsonNode vector : Vectors.read("primitives/wire-encoding.json").get("duplicates")) {
            String text = vector.get("text").textValue();
            String name = vector.get("name").textValue();
            if ("result".equals(vector.get("target").textValue())) {
                JavaType type = resultType(vector.get("kind").textValue());
                assertThrows(JsonProcessingException.class, () -> lenient.readValue(text, type), name + " must be rejected");
                assertThrows(PrimitivesJson.InvalidJsonTextException.class, () -> PrimitivesJson.read(lenient, text, type), name);
            } else {
                assertThrows(JsonProcessingException.class, () -> lenient.readValue(text, Error.class), name + " must be rejected");
                assertThrows(PrimitivesJson.InvalidJsonTextException.class, () -> PrimitivesJson.read(lenient, text, Error.class), name);
            }
        }
    }

    @Test
    void numbersFollowTheValueModel() throws IOException {
        // 整数值的浮点仍写成浮点，非有限的写成字符串；超出 64 位的整数按双精度浮点（RFC-0001「Result」，ADR-0236）
        Error error = Error.validation("ORDERS_VALIDATION_001", "orders.invalid", java.util.Map.of("f", 2.0, "z", -0.0, "g", 0.1f,
                "nan", Double.NaN, "inf", Double.NEGATIVE_INFINITY, "big", new java.math.BigInteger("18446744073709551615"),
                "exact", 9007199254740993L, "decimal", new java.math.BigDecimal("2.50")));
        assertEquals("{\"big\":1.8446744073709552E19,\"decimal\":2.5,\"exact\":9007199254740993,\"f\":2.0,\"g\":0.10000000149011612,"
                + "\"inf\":\"-Infinity\",\"nan\":\"NaN\",\"z\":-0.0}",
                mapper.readTree(mapper.writeValueAsString(error)).get("details").toString());
        Result<Object> big = PrimitivesJson.read(mapper, "{\"ok\":true,\"value\":[18446744073709551616,1e400]}", resultType("value"));
        assertEquals(java.util.List.of(1.8446744073709552E19, Double.POSITIVE_INFINITY), big.value().orElseThrow());
    }

    @Test
    void textLevelProblemsAreRejectedBeforeDecoding() {
        // BOM、注释、孤立的代理项与过深的嵌套：Jackson 自己会放过一部分（ADR-0236）
        String bom = "﻿";
        String escapedSurrogate = "\\" + "ud800";
        for (String text : new String[] {bom + "{\"ok\":true}", "{\"ok\":true /*c*/}", "{\"ok\":true,\"value\":\"" + escapedSurrogate + "\"}",
                "{\"ok\":true,\"value\":" + "[".repeat(64) + "]".repeat(64) + "}"}) {
            PrimitivesJson.InvalidJsonTextException thrown = assertThrows(PrimitivesJson.InvalidJsonTextException.class,
                    () -> PrimitivesJson.read(mapper, text, resultType("value")), text);
            assertEquals(new JsonText.Problem(true, "$"), thrown.problem());
        }
        assertThrows(JsonProcessingException.class, () -> mapper.readValue("{\"ok\":true,\"value\":\"" + escapedSurrogate + "\"}", resultType("value")),
                "the decoder rejects lone surrogates on its own");
    }

    /**
     * 向量的 kind：value 是有值结果（任意非 null 的 JSON），unit 是无值结果，maybe 是值类型为 Maybe 的结果。
     */
    private JavaType resultType(String kind) {
        var types = mapper.getTypeFactory();
        return switch (kind) {
            case "value" -> types.constructParametricType(Result.class, Object.class);
            case "unit" -> types.constructParametricType(Result.class, Unit.class);
            case "maybe" -> types.constructParametricType(Result.class, types.constructParametricType(Maybe.class, Object.class));
            default -> throw new IllegalStateException("Unknown result kind '" + kind + "'.");
        };
    }
}
