package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 载荷的文本与响应的编码（RFC-0011 规则 2、规则 10「编码」，ADR-0236）。
 */
class ResponseEncodingTest {
    /** 任意 JSON 的成员原样往返。 */
    record Echo(JsonNode meta, Object any) {
    }

    /** 类型化的浮点成员。 */
    record Reading(double value) {
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

    private static OperationInvoker invoker(OperationRegistry registry, PipelineBuilder pipeline) {
        return new OperationInvoker(registry, pipeline);
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
    void readTreeIsExactOnlyWhereDoublesCannot() {
        JsonNode tree = OperationJson.readTree("[1e400,1e-400,0e5,2.0,-0.0,1.5]".getBytes(StandardCharsets.UTF_8));
        assertTrue(tree.get(0).isBigDecimal() && tree.get(1).isBigDecimal(), "values a double cannot hold keep their exact decimal");
        for (int index = 2; index < 6; index++) {
            assertTrue(tree.get(index).isDouble(), tree.get(index).toString());
        }
    }
}
