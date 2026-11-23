package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
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
 * 回放 {@code spec/conformance/vectors/operations/payload-reasons.json}（RFC-0011 规则 2，ADR-0235、ADR-0236）：
 * {@code reason} 为 {@code null} 的解码成功，其余的错误与 {@code reason} 逐字相等。三个请求类型按 Java 的写法声明：
 * 必填成员用 {@code @JsonProperty(required = true)}，可选成员不加注解；字符串、列表、对象与 {@link JsonNode} 接受 {@code null}，
 * 整数、浮点与布尔（基本类型与包装类型都一样）不接受。
 */
class PayloadReasonsVectorsTest {
    /** 向量的 counts：成员值都是 32 位整数的对象（整数字典）。 */
    static final class Counts extends LinkedHashMap<String, Integer> {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** 向量的 order：lines 是可选的数组，每项是对象，quantity 是可选的 32 位整数。 */
    record Order(List<Line> lines) {
    }

    /** 订单的一行。 */
    record Line(int quantity) {
    }

    /** 向量的 profile：name 必填；其余可选。 */
    record Profile(@JsonProperty(required = true) String name, String note, long count, double ratio, boolean flag, List<String> tags,
            List<Long> scores, Inner inner, JsonNode meta) {
    }

    /** profile 的 inner：n 是必填的 64 位整数。 */
    record Inner(@JsonProperty(required = true) long n) {
    }

    private static final Map<String, String> OPERATIONS = Map.of("counts", "counts.set", "order", "orders.create", "profile", "profiles.save");

    private static OperationInvoker invoker() {
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("counts.set"), Counts.class, String.class, (context, request) -> Result.ok("ok"));
        registry.add(OperationContract.of("orders.create"), Order.class, String.class, (context, request) -> Result.ok("ok"));
        registry.add(OperationContract.of("profiles.save"), Profile.class, String.class, (context, request) -> Result.ok("ok"));
        return new OperationInvoker(registry);
    }

    private static Optional<Error> failure(OperationInvoker invoker, String request, String payload) {
        return invoker.invoke(Context.background(), OperationRequest.of(OPERATIONS.get(request), payload)).outcome().error();
    }

    @Test
    void reasonsFollowTheVectors() throws IOException {
        OperationInvoker invoker = invoker();
        JsonNode vectors = Vectors.read("operations/payload-reasons.json");
        java.util.Set<String> declared = new java.util.HashSet<>();
        vectors.get("requests").fieldNames().forEachRemaining(declared::add);
        assertEquals(OPERATIONS.keySet(), declared, "the vectors declare these request types");
        for (JsonNode vector : vectors.get("cases")) {
            String name = vector.get("name").textValue();
            String request = vector.get("request").textValue();
            Optional<Error> error = failure(invoker, request, vector.get("payload").textValue());
            JsonNode reason = vector.get("reason");
            if (reason.isNull()) {
                assertEquals(Optional.empty(), error, name + " must decode");
                continue;
            }
            Error rejected = error.orElseThrow(() -> new AssertionError(name + " must be rejected"));
            assertEquals(OperationErrors.INVALID_PAYLOAD, rejected.code(), name);
            assertEquals(Optional.of(OperationMessages.INVALID_PAYLOAD), rejected.messageKey(), name);
            assertEquals(Map.of("id", OPERATIONS.get(request), "reason", reason.textValue()), rejected.details(), name);
        }
    }

    @Test
    void decodedValuesFollowTheRules() {
        // 解码出的值：浮点上溢为无穷、下溢为 0，null 的字符串与缺少的整数取缺省值
        OperationRegistry registry = new OperationRegistry();
        java.util.concurrent.atomic.AtomicReference<Profile> seen = new java.util.concurrent.atomic.AtomicReference<>();
        registry.add(OperationContract.of("profiles.save"), Profile.class, String.class, (context, request) -> {
            seen.set(request);
            return Result.ok("ok");
        });
        OperationInvoker invoker = new OperationInvoker(registry);
        assertTrue(invoker.invoke(Context.background(), OperationRequest.of("profiles.save", "{\"name\":null,\"ratio\":1e400,\"tags\":[\"x\",null]}")).isOk());
        assertEquals(null, seen.get().name());
        assertEquals(Double.POSITIVE_INFINITY, seen.get().ratio());
        assertEquals(0L, seen.get().count());
        assertEquals(java.util.Arrays.asList("x", null), seen.get().tags());
        assertTrue(invoker.invoke(Context.background(), OperationRequest.of("profiles.save", "{\"name\":\"a\",\"ratio\":1e-400,\"count\":-0}")).isOk());
        assertEquals(0.0, seen.get().ratio());
        // 空载荷等价于 {}：缺少必填成员，报根
        assertEquals(Map.of("id", "profiles.save", "reason", "$"), failure(invoker, "profile", "").orElseThrow().details());
    }

    @Test
    void wellFormedPayloadsOfTheVectorTypesDecode() {
        OperationInvoker invoker = invoker();
        assertEquals(Optional.empty(), failure(invoker, "counts", "{\"a\":1,\"b\":2}"));
        assertEquals(Optional.empty(), failure(invoker, "order", "{\"lines\":[{\"quantity\":1}]}"));
        assertEquals(Optional.empty(), failure(invoker, "counts", ""), "an empty payload is {}");
        assertEquals(Map.of("id", "counts.set", "reason", "$"), failure(invoker, "counts", "null").orElseThrow().details());
    }
}
