package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.Result;
import java.io.IOException;
import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/operations/payload-reasons.json}：载荷不合法时 {@code reason} 的路径写法（RFC-0011 规则 2，ADR-0235）。
 */
class PayloadReasonsVectorsTest {
    /** 向量的 counts：成员值都是整数的对象（整数字典）。 */
    static final class Counts extends LinkedHashMap<String, Integer> {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** 向量的 order：lines 是数组，每项是对象，quantity 是整数。 */
    record Order(List<Line> lines) {
    }

    /** 订单的一行。 */
    record Line(int quantity) {
    }

    private static OperationInvoker invoker() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("counts.set"), Counts.class, Counts.class, (context, request) -> Result.ok(request));
        registry.add(OperationContract.of("orders.create"), Order.class, Order.class, (context, request) -> Result.ok(request));
        return new OperationInvoker(registry);
    }

    private static Optional<Error> failure(OperationInvoker invoker, String request, String payload) {
        String id = "counts".equals(request) ? "counts.set" : "orders.create";
        return invoker.invoke(Context.background(), OperationRequest.of(id, payload)).outcome().error();
    }

    @Test
    void reasonsFollowTheVectors() throws IOException {
        OperationInvoker invoker = invoker();
        JsonNode vectors = Vectors.read("operations/payload-reasons.json");
        for (JsonNode vector : vectors.get("cases")) {
            String name = vector.get("name").textValue();
            Error error = failure(invoker, vector.get("request").textValue(), vector.get("payload").textValue())
                    .orElseThrow(() -> new AssertionError(name + " must be rejected"));
            assertEquals(OperationErrors.INVALID_PAYLOAD, error.code(), name);
            assertEquals(Optional.of(OperationMessages.INVALID_PAYLOAD), error.messageKey(), name);
            assertEquals(vector.get("reason").textValue(), error.details().get("reason"), name);
        }
        for (JsonNode vector : vectors.get("syntaxErrors")) {
            String payload = vector.get("payload").textValue();
            Error error = failure(invoker, vector.get("request").textValue(), payload).orElseThrow(() -> new AssertionError(payload + " must be rejected"));
            assertEquals(OperationErrors.INVALID_PAYLOAD, error.code(), payload);
            assertTrue(((String) error.details().get("reason")).startsWith("$"), payload + ": " + error.details());
        }
    }

    @Test
    void wellFormedPayloadsOfTheVectorTypesDecode() {
        OperationInvoker invoker = invoker();
        assertEquals(Optional.of("{\"a\":1,\"b\":2}"),
                invoker.invoke(Context.background(), OperationRequest.of("counts.set", "{\"a\":1,\"b\":2}")).payloadText());
        assertEquals(Optional.of("{\"lines\":[{\"quantity\":1}]}"),
                invoker.invoke(Context.background(), OperationRequest.of("orders.create", "{\"lines\":[{\"quantity\":1}]}")).payloadText());
        assertEquals(Map.of("id", "counts.set", "reason", "$"), failure(invoker, "counts", "null").orElseThrow().details());
    }
}
