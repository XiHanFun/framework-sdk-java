package com.xihanfun.framework.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.FrameworkException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/manifest/manifests.json}（RFC-0002 解析规则 2，ADR-0233、ADR-0235）：合法的读得出、写出后再读得到同一份，
 * 不合法的以 {@code PRIMITIVES_VALIDATION_001} 拒绝。
 */
class ManifestVectorsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validManifestsReadAndRoundTrip() throws IOException {
        for (JsonNode vector : Vectors.read("manifest/manifests.json").get("valid")) {
            String name = vector.get("name").textValue();
            Manifest manifest = ManifestJson.read(text(vector));
            assertEquals(manifest, ManifestJson.read(ManifestJson.write(manifest)), name + ": the written manifest reads back the same");
        }
    }

    @Test
    void invalidManifestsAreRejected() throws IOException {
        for (JsonNode vector : Vectors.read("manifest/manifests.json").get("invalid")) {
            String name = vector.get("name").textValue();
            FrameworkException rejected = assertThrows(FrameworkException.class, () -> ManifestJson.read(text(vector)), name + " must be rejected");
            assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code(), name);
        }
    }

    /**
     * 清单原文：text 给出 JSON 值表达不了的写法（重复成员），否则把 manifest 的 JSON 值写成文本。
     */
    private static String text(JsonNode vector) throws IOException {
        return vector.has("text") ? vector.get("text").textValue() : MAPPER.writeValueAsString(vector.get("manifest"));
    }
}
