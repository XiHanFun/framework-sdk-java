package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
import com.xihanfun.framework.manifest.Contributions;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/primitives/operation-ids.json}（{@code conventions/operation-ids.md}、RFC-0011「操作 ID」，ADR-0276）：
 * {@code valid} 的每一项都是操作 ID，{@code invalid} 的每一项都不是；整个 ID 不超过 {@code maxLength} 个字符，正好到上限的合法、
 * 多一个字符的不合法。操作契约与清单的 {@code contributes.operations} 用的是同一条规则，两处都回放。
 */
class OperationIdVectorsTest {
    @Test
    void validIdsAreAccepted() throws IOException {
        JsonNode vectors = Vectors.read("primitives/operation-ids.json");
        List<String> valid = Vectors.strings(vectors.get("valid"));
        assertFalse(valid.isEmpty(), "the vectors list valid IDs");
        for (String id : valid) {
            assertTrue(OperationId.isValid(id), id + " must be valid");
            assertEquals(id, OperationId.validate(id), id);
            assertEquals(id, OperationContract.of(id).id(), id + " names a contract");
            assertEquals(List.of(id), Contributions.builder().operations(List.of(id)).build().operations(),
                    id + " is contributed by a manifest");
        }
    }

    @Test
    void invalidIdsAreRejected() throws IOException {
        JsonNode vectors = Vectors.read("primitives/operation-ids.json");
        List<String> invalid = Vectors.strings(vectors.get("invalid"));
        assertFalse(invalid.isEmpty(), "the vectors list invalid IDs");
        for (String id : invalid) {
            assertFalse(OperationId.isValid(id), id + " must be invalid");
            assertThrows(IllegalArgumentException.class, () -> OperationId.validate(id), id);
            assertThrows(IllegalArgumentException.class, () -> OperationContract.of(id), id + " must not name a contract");
            assertThrows(IllegalArgumentException.class, () -> Contributions.builder().operations(List.of(id)).build(),
                    id + " must not be contributed by a manifest");
        }
        assertFalse(OperationId.isValid(null), "null is not an operation ID");
    }

    @Test
    void theLimitIsTheOneInTheVectors() throws IOException {
        JsonNode vectors = Vectors.read("primitives/operation-ids.json");
        int maxLength = vectors.get("maxLength").intValue();
        assertEquals(maxLength, OperationId.MAX_LENGTH, "the maximum length");
        // 向量两侧都要到：正好 maxLength 个字符的合法，别处都合文法、只多一个字符的不合法，只有上限这一条拦得住它
        assertTrue(Vectors.strings(vectors.get("valid")).stream().anyMatch(id -> id.length() == maxLength),
                "the vectors accept an ID of exactly the maximum length");
        assertTrue(Vectors.strings(vectors.get("invalid")).stream().anyMatch(id -> id.length() == maxLength + 1),
                "the vectors reject an ID one character over the maximum length");
    }
}
