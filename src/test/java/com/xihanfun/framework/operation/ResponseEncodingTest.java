package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.invocation.CancellableContext;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 载荷的文本与响应的编码（RFC-0011 规则 2、规则 10「编码」，ADR-0236），以及取消只经令牌通知（RFC-0003 规则 1）。
 */
class ResponseEncodingTest {
    /** 任意 JSON 的成员原样往返。 */
    record Echo(JsonNode meta, Object any) {
    }

    /** 类型化的浮点成员。 */
    record Reading(double value) {
    }

    /** 带文本与数组的响应。 */
    record Label(String label, List<String> tags) {
    }

    /** 成员名里有孤立代理项的响应。 */
    record Renamed(@JsonProperty("count" + (char) 0xD800) int count) {
    }

    /** 序列化时抛出框架异常的值。 */
    @JsonSerialize(using = Refusing.Serializer.class)
    record Refusing(String text) {
        static final class Serializer extends StdSerializer<Refusing> {
            @Serial
            private static final long serialVersionUID = 1L;

            Serializer() {
                super(Refusing.class);
            }

            @Override
            public void serialize(Refusing value, JsonGenerator generator, SerializerProvider provider) {
                throw new FrameworkException("ORDERS_UNEXPECTED_001", "The response cannot be written.");
            }
        }
    }

    /** 自定义序列化器两次写出同名的成员。 */
    @JsonSerialize(using = Twice.Serializer.class)
    record Twice() {
        static final class Serializer extends StdSerializer<Twice> {
            @Serial
            private static final long serialVersionUID = 1L;

            Serializer() {
                super(Twice.class);
            }

            @Override
            public void serialize(Twice value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeStartObject();
                generator.writeFieldName("a");
                generator.writeNumber(1);
                generator.writeFieldName("a");
                generator.writeNumber(2);
                generator.writeEndObject();
            }
        }
    }

    /** 原样嵌进响应的 JSON 文本。 */
    record Raw(@JsonRawValue String raw) {
    }

    /** 预期照常写出的响应与它的文本。 */
    record Written(Object value, String payload) {
    }

    private static OperationInvoker invoker(OperationRegistry registry, PipelineBuilder pipeline) {
        return new OperationInvoker(registry, pipeline);
    }

    /** {@code depth} 层嵌套的列表，最外层是第 1 层，最里面一层的元素是 {@code innermost}。 */
    private static Object nestedLists(int depth, Object... innermost) {
        Object value = List.of(innermost);
        for (int level = 1; level < depth; level++) {
            value = List.of(value);
        }
        return value;
    }

    /** {@code depth} 层嵌套的映射，最外层是第 1 层，最里面是空映射。 */
    private static Object nestedMaps(int depth) {
        Object value = Map.of();
        for (int level = 1; level < depth; level++) {
            value = Map.of("a", value);
        }
        return value;
    }

    /** {@code depth} 层嵌套的空数组的文本。 */
    private static String arrays(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    @Test
    void arbitraryJsonKeepsTheValuesItRead() {
        // 任意 JSON 的成员原样保留读到的值：1e400 与 1e-400 不变成无穷或 0，超出双精度的整数精确，2.0 与 2、-0.0 与 0 分得开
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("echoes.create"), Echo.class, Echo.class, (context, request) -> Result.ok(request));
        String value = "{\"big\":1e400,\"tiny\":1e-400,\"exact\":9007199254740993,\"huge\":18446744073709551617,\"float\":2.0,\"int\":2,"
                + "\"negative\":-0.0,\"zero\":0,\"tenth\":0.1}";
        String payload = "{\"meta\":" + value + ",\"any\":" + value + "}";
        OperationResponse response = invoker(registry, new PipelineBuilder()).invoke(Context.background(),
                OperationRequest.of("echoes.create", payload));
        assertTrue(response.isOk(), () -> response.outcome().toString());
        JsonNode written = OperationJson.readTree(response.payloadText().orElseThrow().getBytes(StandardCharsets.UTF_8));
        assertEquals(OperationJson.readTree(payload.getBytes(StandardCharsets.UTF_8)), written, "the response is the same JSON value");
        assertEquals("1E+400", written.get("meta").get("big").decimalValue().toString());
        assertTrue(written.get("meta").get("float").isFloatingPointNumber());
        assertTrue(written.get("meta").get("int").isIntegralNumber());
    }

    @Test
    void responsesAreEncodedAfterThePipeline() {
        // Egress 阶段的中间件看到的是编码之前的成功结果；编码失败在管道结束之后，以 unhandled_exception 结束、没有载荷
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("readings.take"), Unit.class, Reading.class, (context, request) -> Result.ok(new Reading(Double.NaN)));
        registry.add(OperationContract.of("readings.peak"), Unit.class, Reading.class,
                (context, request) -> Result.ok(new Reading(Double.POSITIVE_INFINITY)));
        registry.add(OperationContract.of("readings.refuse"), Unit.class, Refusing.class, (context, request) -> Result.ok(new Refusing("x")));
        AtomicReference<Result<Unit>> seen = new AtomicReference<>();
        OperationInvoker invoker = invoker(registry, new PipelineBuilder().useAt(Middleware.of("Egress", (context, next) -> {
            Result<Unit> outcome = next.invoke(context);
            seen.set(outcome);
            return outcome;
        }), PipelineStage.EGRESS));
        for (String id : List.of("readings.take", "readings.peak")) {
            OperationResponse response = invoker.invoke(Context.background(), OperationRequest.of(id));
            Error error = response.outcome().error().orElseThrow();
            assertEquals("KERNEL_UNEXPECTED_001", error.code(), id);
            assertEquals(Optional.of("kernel.invocation.unhandled_exception"), error.messageKey(), id);
            assertEquals(Optional.empty(), error.inner(), id);
            assertEquals(Optional.empty(), response.payloadText(), "a typed NaN is not written as a string or null");
            assertTrue(seen.get().isOk(), "middleware sees the result before encoding");
        }
        Error refused = invoker.invoke(Context.background(), OperationRequest.of("readings.refuse")).outcome().error().orElseThrow();
        assertEquals(Optional.of("kernel.invocation.unhandled_exception"), refused.messageKey());
        assertEquals("ORDERS_UNEXPECTED_001", refused.inner().orElseThrow().code(), "the framework error goes into inner");
        // 类型化入口不编码：NaN 原样交给调用方
        assertTrue(Double.isNaN(invoker.invoke(Context.background(), "readings.take", Unit.VALUE, Reading.class).value().orElseThrow().value()));
    }

    @Test
    void responsesThatAreNotValidJsonTextAreNotWritten() {
        // 响应里的字符串写不成合法的 JSON 文本即写不出（RFC-0011 规则 10「编码」，ADR-0318）：值、成员名、映射的键与数组元素里的
        // 孤立代理项都以 unhandled_exception 结束，不带 inner，没有载荷。Jackson 把值与映射的键里的孤立代理项写成 Unicode
        // 转义，由写出之后的 JSON 文本检查拦下；@JsonProperty 的成员名预先编码，Jackson 先抛出（拆开的代理项），两条路的错误相同
        String high = String.valueOf((char) 0xD800);
        String low = String.valueOf((char) 0xDC00);
        Map<String, Object> unwritable = new LinkedHashMap<>();
        unwritable.put("labels.value", new Label("a" + high, List.of()));
        unwritable.put("labels.reversed", new Label(low + high, List.of()));
        unwritable.put("labels.element", new Label("a", List.of("b", low)));
        unwritable.put("labels.member", new Renamed(1));
        unwritable.put("labels.key", Map.of(high, 1));
        unwritable.put("labels.nested-key", Map.of("a", List.of(Map.of("b" + low, "c"))));
        String text = "中文" + new String(Character.toChars(0x1F600)) + (char) 0xFFFD;
        OperationRegistry registry = new OperationRegistry();
        for (Map.Entry<String, Object> entry : unwritable.entrySet()) {
            Object value = entry.getValue();
            registry.add(OperationContract.of(entry.getKey()), Unit.class, Object.class, (context, request) -> Result.ok(value));
        }
        registry.add(OperationContract.of("labels.text"), Unit.class, Label.class, (context, request) -> Result.ok(new Label(text, List.of("é"))));
        OperationInvoker invoker = invoker(registry, new PipelineBuilder());
        for (String id : unwritable.keySet()) {
            OperationResponse response = invoker.invoke(Context.background(), OperationRequest.of(id));
            Error error = response.outcome().error().orElseThrow(() -> new AssertionError(id));
            assertEquals("KERNEL_UNEXPECTED_001", error.code(), id);
            assertEquals(Optional.of("kernel.invocation.unhandled_exception"), error.messageKey(), id);
            assertEquals(Map.of(), error.details(), id);
            assertEquals(Optional.empty(), error.inner(), id);
            assertEquals(Optional.empty(), response.payloadText(), id);
        }
        // 多字节文本与真正的 U+FFFD 照常写出
        OperationResponse written = invoker.invoke(Context.background(), OperationRequest.of("labels.text"));
        assertTrue(written.isOk(), () -> written.outcome().toString());
        JsonNode label = OperationJson.readTree(written.payloadText().orElseThrow().getBytes(StandardCharsets.UTF_8));
        assertEquals(text, label.get("label").textValue());
        assertEquals("é", label.get("tags").get(0).textValue());
    }

    @Test
    void responsesThatDoNotPassTheJsonTextChecksAreNotWritten() {
        // 写出的整段文本要过 RFC-0001「JSON 文本」的共同检查（RFC-0011 规则 10「编码」，ADR-0319）：嵌套超过 64 层（原样嵌进去的
        // 文本也算，从响应的根算起）或任何一层有重复的成员名同样写不出，以 unhandled_exception 结束，不带 inner，没有载荷。
        // Jackson 写出时不查重复成员，嵌套上限远大于 64，由写出之后的 JSON 文本检查拦下
        Map<String, Object> unwritable = new LinkedHashMap<>();
        unwritable.put("depths.lists", nestedLists(65));
        unwritable.put("depths.scalar", nestedLists(65, 1));
        unwritable.put("depths.maps", nestedMaps(65));
        // 响应对象是第 1 层：嵌进去的 64 层数组在根之下是 65 层
        unwritable.put("depths.raw", new Raw(arrays(64)));
        unwritable.put("members.serializer", new Twice());
        unwritable.put("members.nested", Map.of("outer", List.of(new Twice())));
        unwritable.put("members.raw", new Raw("{\"a\":1,\"a\":2}"));
        unwritable.put("members.raw-apart", new Raw("[{\"b\":{\"a\":1,\"c\":2,\"a\":3}}]"));
        Map<String, Written> writable = new LinkedHashMap<>();
        writable.put("depths.lists-at-limit", new Written(nestedLists(64), arrays(64)));
        writable.put("depths.scalar-at-limit", new Written(nestedLists(64, 1), "[".repeat(64) + "1" + "]".repeat(64)));
        writable.put("depths.maps-at-limit", new Written(nestedMaps(64), "{\"a\":".repeat(63) + "{}" + "}".repeat(63)));
        writable.put("depths.raw-at-limit", new Written(new Raw(arrays(63)), "{\"raw\":" + arrays(63) + "}"));
        // 不同层、不同对象里的同名成员不算重复
        writable.put("members.distinct", new Written(new Raw("{\"a\":{\"a\":1},\"b\":[{\"a\":2},{\"a\":3}]}"),
                "{\"raw\":{\"a\":{\"a\":1},\"b\":[{\"a\":2},{\"a\":3}]}}"));
        OperationRegistry registry = new OperationRegistry();
        for (Map.Entry<String, Object> entry : unwritable.entrySet()) {
            Object value = entry.getValue();
            registry.add(OperationContract.of(entry.getKey()), Unit.class, Object.class, (context, request) -> Result.ok(value));
        }
        for (Map.Entry<String, Written> entry : writable.entrySet()) {
            Object value = entry.getValue().value();
            registry.add(OperationContract.of(entry.getKey()), Unit.class, Object.class, (context, request) -> Result.ok(value));
        }
        OperationInvoker invoker = invoker(registry, new PipelineBuilder());
        for (String id : unwritable.keySet()) {
            OperationResponse response = invoker.invoke(Context.background(), OperationRequest.of(id));
            Error error = response.outcome().error().orElseThrow(() -> new AssertionError(id));
            assertEquals("KERNEL_UNEXPECTED_001", error.code(), id);
            assertEquals(Optional.of("kernel.invocation.unhandled_exception"), error.messageKey(), id);
            assertEquals(Map.of(), error.details(), id);
            assertEquals(Optional.empty(), error.inner(), id);
            assertEquals(Optional.empty(), response.payloadText(), id);
        }
        // 恰好 64 层照常写出：最里面有值也一样，差一层都不行
        for (Map.Entry<String, Written> entry : writable.entrySet()) {
            OperationResponse written = invoker.invoke(Context.background(), OperationRequest.of(entry.getKey()));
            assertTrue(written.isOk(), () -> entry.getKey() + ": " + written.outcome());
            assertEquals(Optional.of(entry.getValue().payload()), written.payloadText(), entry.getKey());
        }
    }

    @Test
    void cancellationOnlySignalsThroughTheTokens() throws InterruptedException {
        // 取消与截止时间只经令牌通知，不强行中止处理器：令牌触发之后处理器照常返回的结果就是调用的结果（RFC-0003 规则 1，ADR-0236）
        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("jobs.finish"), Unit.class, String.class, (context, request) -> {
            try {
                assertTrue(context.awaitCancellation(Duration.ofSeconds(5)), "the token fires");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.ok("interrupted");
            }
            return Result.ok("finished");
        });
        OperationInvoker invoker = invoker(registry, new PipelineBuilder());
        try (CancellableContext timed = Context.background().withTimeout(Duration.ofMillis(50))) {
            OperationResponse response = invoker.invoke(timed.context(), OperationRequest.of("jobs.finish"));
            assertEquals(Optional.of("\"finished\""), response.payloadText(), "the call ends with the handler's own result");
            assertFalse(Thread.currentThread().isInterrupted(), "the thread is not interrupted");
        }
        try (CancellableContext cancelled = Context.background().withCancellation()) {
            Thread canceller = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                cancelled.cancel();
            });
            assertEquals(Optional.of("\"finished\""), invoker.invoke(cancelled.context(), OperationRequest.of("jobs.finish")).payloadText());
            canceller.join();
        }
    }

    @Test
    void readTreeIsExactOnlyWhereDoublesCannot() {
        JsonNode tree = OperationJson.readTree("[1e400,1e-400,0e5,2.0,-0.0,1.5]".getBytes(StandardCharsets.UTF_8));
        assertTrue(tree.get(0).isBigDecimal() && tree.get(1).isBigDecimal(), "values a double cannot hold keep their exact decimal");
        for (int index = 2; index < 6; index++) {
            assertTrue(tree.get(index).isDouble(), tree.get(index).toString());
        }
    }
}
