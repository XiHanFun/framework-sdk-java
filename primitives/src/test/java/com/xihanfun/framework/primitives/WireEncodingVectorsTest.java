package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            Object decoded = mapper.treeToValue(vector.get("json"), type);
            JsonNode expected = vector.has("canonical") ? vector.get("canonical") : vector.get("json");
            assertEquals(expected, mapper.readTree(mapper.writeValueAsString(decoded)), vector.get("name").textValue());
        }
        for (JsonNode vector : results.get("invalid")) {
            JavaType type = resultType(vector.get("kind").textValue());
            String json = mapper.writeValueAsString(vector.get("json"));
            assertThrows(JsonProcessingException.class, () -> mapper.readValue(json, type), vector.get("name").textValue() + " must be rejected");
        }
    }

    @Test
    void errorsFollowTheVectors() throws IOException {
        JsonNode errors = Vectors.read("primitives/wire-encoding.json").get("errors");
        for (JsonNode vector : errors.get("valid")) {
            Error decoded = mapper.treeToValue(vector.get("json"), Error.class);
            JsonNode expected = vector.has("canonical") ? vector.get("canonical") : vector.get("json");
            assertEquals(expected, mapper.readTree(mapper.writeValueAsString(decoded)), vector.get("name").textValue());
        }
        for (JsonNode vector : errors.get("invalid")) {
            String json = mapper.writeValueAsString(vector.get("json"));
            assertThrows(JsonProcessingException.class, () -> mapper.readValue(json, Error.class), vector.get("name").textValue() + " must be rejected");
        }
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
