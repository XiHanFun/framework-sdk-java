package com.xihanfun.framework.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.MessageTemplate;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/manifest/manifests.json}（RFC-0002 解析规则 2 与「写出」，ADR-0233、ADR-0235、ADR-0236）：
 * 合法的读得出，写出的全文与 {@code written} 逐字相等，再读得到同一份；不合法的错误码、消息键与参数逐字相等。
 */
class ManifestVectorsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validManifestsReadAndWriteTheExpectedText() throws IOException {
        for (JsonNode vector : Vectors.read("manifest/manifests.json").get("valid")) {
            String name = vector.get("name").textValue();
            Manifest manifest = ManifestJson.read(text(vector));
            String written = ManifestJson.write(manifest);
            assertEquals(vector.get("written").textValue(), written, name + ": written text");
            assertEquals(manifest, ManifestJson.read(written), name + ": the written manifest reads back the same");
        }
    }

    @Test
    void invalidManifestsReportTheExpectedError() throws IOException {
        Map<String, String> templates = new java.util.HashMap<>();
        for (JsonNode message : Vectors.read("primitives/kernel-messages.json").get("messages")) {
            templates.put(message.get("key").textValue(), message.get("template").textValue());
        }
        for (JsonNode vector : Vectors.read("manifest/manifests.json").get("invalid")) {
            String name = vector.get("name").textValue();
            JsonNode expected = vector.get("error");
            String text = text(vector);
            Error error = assertThrows(FrameworkException.class, () -> ManifestJson.read(text), name + " must be rejected").toError();
            assertEquals(expected.get("code").textValue(), error.code(), name);
            assertEquals(Optional.of(expected.get("messageKey").textValue()), error.messageKey(), name);
            Map<String, Object> arguments = MAPPER.convertValue(expected.get("arguments"), new TypeReference<>() { });
            assertEquals(arguments, error.details(), name);
            // message 是 kernel-messages 里的英文模板格式化后的句子，与 .NET 相同（ADR-0236）
            assertEquals(MessageTemplate.format(templates.get(error.messageKey().get()), arguments), error.message(), name + " message");
        }
    }

    /**
     * 清单原文：有 text 时用它（可能是空串，与没有 text 不同），否则把 manifest 的 JSON 值写成紧凑文本，成员顺序照写。
     */
    private static String text(JsonNode vector) throws IOException {
        return vector.has("text") ? vector.get("text").textValue() : MAPPER.writeValueAsString(vector.get("manifest"));
    }
}
