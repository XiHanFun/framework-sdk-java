package com.xihanfun.framework.primitives.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Maybe;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.TypedId;
import com.xihanfun.framework.primitives.Unit;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 线上编码：与 Go SDK 的 primitives_test.go 逐字一致的 JSON。
 */
class PrimitivesModuleTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new PrimitivesModule());

    /** 订单标识的标签。 */
    private static final class OrderTag {
    }

    /** 带 Maybe 与 TypedId 成员的数据类型。 */
    record Order(TypedId<OrderTag, String> id, Maybe<String> note, Stability stability) {
    }

    @Test
    void errorWireEncoding() throws Exception {
        Error inner = Error.literal("KERNEL_UNEXPECTED_001", "boom");
        Error error = Error.unexpected("ORDERS_UNEXPECTED_001", "orders.failed", Map.of("id", "o1"), inner);
        String json = mapper.writeValueAsString(error);
        assertEquals("{\"code\":\"ORDERS_UNEXPECTED_001\",\"message\":\"orders.failed (id=o1)\",\"messageKey\":\"orders.failed\","
                + "\"details\":{\"id\":\"o1\"},\"inner\":{\"code\":\"KERNEL_UNEXPECTED_001\",\"message\":\"boom\"}}", json);
        Error decoded = mapper.readValue(json, Error.class);
        assertEquals("ORDERS_UNEXPECTED_001", decoded.code());
        assertEquals(Optional.of("orders.failed (id=o1)"), decoded.text());
        assertEquals(Optional.of("orders.failed"), decoded.messageKey());
        assertEquals("KERNEL_UNEXPECTED_001", decoded.inner().orElseThrow().code());
        assertEquals(Map.of("id", "o1"), decoded.details());
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("{\"code\":\"bad\",\"message\":\"x\"}", Error.class),
                "an invalid code on the wire must be rejected");
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("{\"code\":\"ORDERS_CONFLICT_001\"}", Error.class),
                "an error needs a message");
        assertThrows(MismatchedInputException.class,
                () -> mapper.readValue("{\"code\":\"ORDERS_CONFLICT_001\",\"message\":\"x\",\"messageKey\":\"Bad Key\"}", Error.class));
    }

    @Test
    void detailsAreEncodedSortedByName() throws Exception {
        Error error = Error.validation("ORDERS_VALIDATION_001", "orders.invalid", Map.of("z", 1, "a", Map.of("y", true, "b", 2.5)));
        assertEquals("{\"code\":\"ORDERS_VALIDATION_001\",\"message\":\"orders.invalid (a={\\\"b\\\":2.5,\\\"y\\\":true}, z=1)\","
                + "\"messageKey\":\"orders.invalid\",\"details\":{\"a\":{\"b\":2.5,\"y\":true},\"z\":1}}", mapper.writeValueAsString(error));
    }

    @Test
    void resultWireEncoding() throws Exception {
        assertEquals("{\"ok\":true,\"value\":{\"id\":\"o1\"}}", mapper.writeValueAsString(Result.ok(Map.of("id", "o1"))));
        Result<Map<String, Object>> failed = Result.fail(Error.literal("ORDERS_CONFLICT_001", "stale"));
        String json = mapper.writeValueAsString(failed);
        assertEquals("{\"ok\":false,\"error\":{\"code\":\"ORDERS_CONFLICT_001\",\"message\":\"stale\"}}", json);
        Result<Map<String, Object>> decoded = mapper.readValue(json, new TypeReference<>() { });
        assertTrue(decoded.isFailed());
        assertEquals("ORDERS_CONFLICT_001", decoded.error().orElseThrow().code());
        assertEquals("{\"ok\":true}", mapper.writeValueAsString(Result.success()), "a success without a value only writes ok");
        Result<Unit> unit = mapper.readValue("{\"ok\":true}", new TypeReference<>() { });
        assertEquals(Result.success(), unit);
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("{\"ok\":true,\"value\":{}}", new TypeReference<Result<Unit>>() { }),
                "a success without a value must not carry one");
        Result<Integer> number = mapper.readValue("{\"ok\":true,\"value\":5}", new TypeReference<>() { });
        assertEquals(Optional.of(5), number.value());
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("{\"ok\":false}", new TypeReference<Result<Integer>>() { }),
                "a failed result needs an error");
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("{\"ok\":true}", new TypeReference<Result<Integer>>() { }),
                "a successful result needs a value");
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("{\"value\":1}", new TypeReference<Result<Integer>>() { }));
    }

    @Test
    void maybeWireEncoding() throws Exception {
        assertEquals("\"x\"", mapper.writeValueAsString(Maybe.some("x")));
        assertEquals("null", mapper.writeValueAsString(Maybe.none()));
        Maybe<Integer> none = mapper.readValue("null", new TypeReference<>() { });
        assertFalse(none.hasValue(), "null decodes to none");
        Maybe<Integer> some = mapper.readValue("5", new TypeReference<>() { });
        assertEquals(5, some.orElse(0), "value decodes to some");
    }

    @Test
    void typedIdEncodesAsItsValue() throws Exception {
        TypedId<OrderTag, String> id = TypedId.of("o1");
        assertEquals("\"o1\"", mapper.writeValueAsString(id));
        TypedId<OrderTag, Long> decoded = mapper.readValue("42", new TypeReference<>() { });
        assertEquals(42L, decoded.value());
    }

    @Test
    void recordMembersUseTheEncodings() throws Exception {
        Order order = new Order(TypedId.of("o1"), Maybe.none(), Stability.EXPERIMENTAL);
        String json = mapper.writeValueAsString(order);
        assertEquals("{\"id\":\"o1\",\"note\":null,\"stability\":\"experimental\"}", json);
        Order missing = mapper.readValue("{\"id\":\"o2\",\"stability\":\"stable\"}", Order.class);
        assertEquals(Maybe.none(), missing.note(), "a missing member decodes to none");
        assertEquals("o2", missing.id().value());
        Order present = mapper.readValue("{\"id\":\"o3\",\"note\":\"n\",\"stability\":\"preview\"}", Order.class);
        assertEquals(Maybe.some("n"), present.note());
    }

    @Test
    void namedEnumsUseTheirWireNames() throws Exception {
        assertEquals("\"experimental\"", mapper.writeValueAsString(Stability.EXPERIMENTAL));
        assertEquals("\"observe\"", mapper.writeValueAsString(PipelineStage.OBSERVE));
        assertEquals("\"Ready\"", mapper.writeValueAsString(LifecycleState.READY));
        assertEquals(LifecycleState.DEGRADED, mapper.readValue("\"Degraded\"", LifecycleState.class));
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("\"solid\"", Stability.class));
        assertThrows(MismatchedInputException.class, () -> mapper.readValue("1", Stability.class));
    }
}
