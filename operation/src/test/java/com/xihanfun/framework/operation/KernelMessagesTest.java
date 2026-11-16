package com.xihanfun.framework.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.pipeline.Middleware;
import com.xihanfun.framework.pipeline.PipelineBuilder;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.MessageTemplate;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 操作产生的内核错误按 {@code spec/conformance/vectors/primitives/kernel-messages.json} 核对：错误码、消息键、参数名与默认英文文案。
 */
class KernelMessagesTest {
    /** 请求。 */
    record Ping(int n) {
    }

    @Test
    void operationErrorsFollowTheVectors() throws IOException {
        Map<String, Error> produced = new HashMap<>();
        OperationHandler<Ping, Ping> echo = (context, request) -> Result.ok(request);

        OperationRegistry registry = new OperationRegistry();
        registry.add(OperationContract.of("pings.send"), Ping.class, Ping.class, echo, "xihan.samples.ping");
        OperationInvoker invoker = new OperationInvoker(registry);
        produced.put(OperationMessages.UNKNOWN, failure(invoker.invoke(Context.background(), OperationRequest.of("pings.missing"))));
        produced.put(OperationMessages.INVALID_PAYLOAD, failure(invoker.invoke(Context.background(), OperationRequest.of("pings.send", "null"))));
        produced.put(OperationMessages.TYPE_MISMATCH,
                invoker.invoke(Context.background(), "pings.send", new Ping(1), String.class).error().orElseThrow());
        produced.put(OperationMessages.DUPLICATE,
                assertThrows(FrameworkException.class, () -> registry.add(OperationContract.of("pings.send"), Ping.class, Ping.class, echo)).toError());

        OperationInvoker swallowing = new OperationInvoker(registry,
                new PipelineBuilder().useAt(Middleware.of("Swallow", (context, next) -> Result.success()), PipelineStage.OBSERVE));
        produced.put(OperationMessages.NO_RESPONSE, failure(swallowing.invoke(Context.background(), OperationRequest.of("pings.send", "{\"n\":1}"))));

        OperationRegistry full = new OperationRegistry();
        OperationHandler<Unit, Unit> nothing = (context, request) -> Result.success();
        for (int index = 0; index < OperationRegistry.CAPACITY; index++) {
            full.add(OperationContract.of("items-" + index + ".list"), Unit.class, Unit.class, nothing);
        }
        produced.put(OperationMessages.REGISTRY_FULL,
                assertThrows(FrameworkException.class, () -> full.add(OperationContract.of("items.overflow"), Unit.class, Unit.class, nothing)).toError());

        OperationRegistry throwing = new OperationRegistry();
        throwing.add(OperationContract.of("pings.fail"), Ping.class, Ping.class, (context, request) -> {
            throw new IllegalStateException("boom");
        });
        produced.put(com.xihanfun.framework.invocation.InvocationMessages.UNHANDLED_EXCEPTION,
                failure(new OperationInvoker(throwing).invoke(Context.background(), OperationRequest.of("pings.fail", "{\"n\":1}"))));

        int checked = 0;
        for (JsonNode message : Vectors.read("primitives/kernel-messages.json").get("messages")) {
            String key = message.get("key").textValue();
            Error error = produced.get(key);
            if (error == null) {
                continue;
            }
            checked++;
            assertEquals(message.get("code").textValue(), error.code(), key);
            assertEquals(Optional.of(key), error.messageKey());
            assertEquals(new HashSet<>(Vectors.strings(message.get("arguments"))), error.details().keySet(), key + " arguments");
            assertEquals(MessageTemplate.format(message.get("template").textValue(), error.details()), error.message(), key + " message");
        }
        assertEquals(7, checked, "every operation error of the table is produced");
        assertEquals("xihan.samples.ping", produced.get(OperationMessages.DUPLICATE).details().get("owner"), "the owner of the existing registration");
        assertEquals("The payload of operation 'pings.send' is not valid: the payload is null", produced.get(OperationMessages.INVALID_PAYLOAD).message());
    }

    private static Error failure(OperationResponse response) {
        return response.outcome().error().orElseThrow();
    }
}
