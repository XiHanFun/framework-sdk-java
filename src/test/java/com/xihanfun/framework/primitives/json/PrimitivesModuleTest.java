package com.xihanfun.framework.primitives.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
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
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * 线上编码（RFC-0001「Result」）：写出的 JSON 逐字核对，读取时拒绝不合编码的写法。
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

    /** 嵌套 {@code levels} 层的对象：1 层是 {@code {}}。 */
    private static Map<String, Object> nested(int levels) {
        Map<String, Object> value = Map.of();
        for (int level = 1; level < levels; level++) {
            value = Map.of("a", value);
        }
        return value;
    }

    /** 原样嵌进去的 JSON 文本：Jackson 写出时不核对它。 */
    record Raw(@JsonRawValue String json) {
    }

    @Test
    void resultsAndErrorsThatCannotBeWrittenFailWithoutWritingText() throws Exception {
        // RFC-0001「写出与转交」（ADR-0320）：孤立的代理项、从结果的根算起超过 64 层、任何一层的重复成员都写不出，不写成 U+FFFD 或 \ud800
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);
        List<Object> unwritable = List.of(
                Error.literal("ORDERS_CONFLICT_001", "stale" + high),
                Error.validation("ORDERS_VALIDATION_001", "orders.invalid", Map.of("id", low)),
                Error.validation("ORDERS_VALIDATION_001", "orders.invalid", Map.of(high + "id", 1)),
                Error.unexpected("ORDERS_UNEXPECTED_001", "orders.failed", Map.of(), Error.literal("KERNEL_UNEXPECTED_001", low + high)),
                Error.literal("ORDERS_CONFLICT_001", "deep", nested(64)),
                Result.ok("x" + low),
                Result.ok(Map.of(high, 1)),
                Result.ok(List.of(Map.of("note", "a" + high + "b"))),
                Result.fail(Error.literal("ORDERS_CONFLICT_001", "stale" + low)),
                Result.ok(nested(64)),
                Result.fail(Error.literal("ORDERS_CONFLICT_001", "deep", nested(63))),
                Result.ok(new Raw("{\"a\":1,\"a\":2}")),
                Result.ok(new Raw("{\"a\":{\"b\":[{\"c\":1,\"c\":1}]}}")),
                Result.ok(new Raw("[".repeat(63) + "]".repeat(63))));
        for (Object value : unwritable) {
            // 写出入口抛 IllegalArgumentException（ADR-0340）：消息取编码器的，Jackson 的异常留作原因，它的原因是编码器抛出的那个
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> write(value), value.toString());
            String what = value instanceof Result<?> ? "result" : "error";
            assertTrue(thrown.getMessage().startsWith("The " + what + " cannot be written to the wire: "), thrown.getMessage());
            JsonMappingException wrapped = assertInstanceOf(JsonMappingException.class, thrown.getCause(), value.toString());
            assertInstanceOf(IllegalArgumentException.class, wrapped.getCause(), value.toString());
            // 直接用 ObjectMapper 写出时 Jackson 把编码器抛出的 IllegalArgumentException 包成 JsonMappingException
            List<Executable> direct = List.of(() -> mapper.writeValueAsBytes(value), () -> mapper.writeValueAsString(value),
                    () -> mapper.writeValueAsString(List.of(value)));
            for (Executable writing : direct) {
                JsonMappingException failure = assertThrows(JsonMappingException.class, writing, value.toString());
                assertInstanceOf(IllegalArgumentException.class, failure.getCause(), value.toString());
            }
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            assertThrows(JsonMappingException.class, () -> mapper.writeValue(written, value), value.toString());
            assertEquals(0, written.size(), "nothing is written: " + value);
        }
        // 成功值的序列化器自己失败同样是 IllegalArgumentException，消息是固定的一句，Jackson 的异常留作原因
        IllegalArgumentException unserializable = assertThrows(IllegalArgumentException.class,
                () -> PrimitivesJson.write(mapper, Result.ok(new Object())));
        assertEquals("The result cannot be written to the wire.", unserializable.getMessage());
        assertInstanceOf(JsonMappingException.class, unserializable.getCause());

        // 上限本身照写：结果 1 层、成功值 63 层；错误 1 层、细节 63 层；失败的结果里细节 62 层；成功值的对象里原样嵌进 62 层；成对的代理项是一个字符
        String pair = new String(Character.toChars(0x1F600));
        assertEquals("{\"ok\":true,\"value\":" + "{\"a\":".repeat(62) + "{}" + "}".repeat(62) + "}",
                PrimitivesJson.write(mapper, Result.ok(nested(63))));
        List<Object> writable = List.of(
                Result.ok(nested(63)),
                Error.literal("ORDERS_CONFLICT_001", "deep", nested(63)),
                Result.fail(Error.literal("ORDERS_CONFLICT_001", "deep", nested(62))),
                Result.ok(new Raw("[".repeat(62) + "]".repeat(62))));
        for (Object value : writable) {
            assertEquals(mapper.writeValueAsString(value), write(value), "the entry writes what the mapper writes: " + value);
        }
        assertEquals("{\"ok\":true,\"value\":{\"note\":\"" + pair + "\"}}", PrimitivesJson.write(mapper, Result.ok(Map.of("note", pair))));
        assertEquals("{\"ok\":true,\"value\":{\"json\":{\"a\":1}}}", PrimitivesJson.write(mapper, Result.ok(new Raw("{\"a\":1}"))));
        assertEquals("{\"code\":\"ORDERS_CONFLICT_001\",\"message\":\"stale " + pair + "\"}",
                PrimitivesJson.write(mapper, Error.literal("ORDERS_CONFLICT_001", "stale " + pair)));
        assertEquals("{\"ok\":true}", PrimitivesJson.write(mapper, Result.success()));
    }

    /** 经写出入口写出结果或错误。 */
    private String write(Object value) {
        return value instanceof Result<?> result ? PrimitivesJson.write(mapper, result) : PrimitivesJson.write(mapper, (Error) value);
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
