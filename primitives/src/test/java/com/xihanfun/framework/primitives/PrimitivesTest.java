package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 原语的语义：文法按一致性向量核对，其余逐项移植自 Go SDK 的 primitives_test.go。
 */
class PrimitivesTest {
    @AfterEach
    void resetMessages() {
        MessageScope.resetDefault();
    }

    @Test
    void errorCodesFollowTheVectors() throws IOException {
        JsonNode vectors = Vectors.read("primitives/error-codes.json");
        for (String code : Vectors.strings(vectors.get("valid"))) {
            assertTrue(ErrorCode.isValid(code), code + " must be valid");
        }
        for (String code : Vectors.strings(vectors.get("invalid"))) {
            assertFalse(ErrorCode.isValid(code), code + " must be invalid");
            assertThrows(IllegalArgumentException.class, () -> ErrorCode.validate(code));
        }
        assertEquals("CONFLICT", ErrorCode.kindOf("PERSISTENCE_CONFLICT_001"));
        assertEquals("PERSISTENCE", ErrorCode.domainOf("PERSISTENCE_CONFLICT_001"));
        assertFalse(ErrorCode.isValid(null));
    }

    @Test
    void baggageKeysFollowTheVectors() throws IOException {
        JsonNode vectors = Vectors.read("primitives/baggage-keys.json");
        for (String key : Vectors.strings(vectors.get("wellFormed"))) {
            assertTrue(BaggageKeys.isWellFormed(key), key + " must be well-formed");
        }
        for (String key : Vectors.strings(vectors.get("illFormed"))) {
            assertFalse(BaggageKeys.isWellFormed(key), key + " must be ill-formed");
        }
        List<String> registered = Vectors.strings(vectors.get("registered"));
        assertEquals(registered, BaggageKeys.registered());
        registered.forEach(key -> assertTrue(BaggageKeys.isRegistered(key), key));
        assertFalse(BaggageKeys.isRegistered("xihan.feature-flag"));
        // ADR-0204、ADR-0233：值非空、按 UTF-16 码元计不超过 256、不含 U+0000–U+001F 与 U+007F–U+009F
        for (String value : Vectors.strings(vectors.get("values").get("acceptable"))) {
            assertTrue(BaggageKeys.isAcceptableValue(value), value + " must be acceptable");
        }
        for (String value : Vectors.strings(vectors.get("values").get("unacceptable"))) {
            assertFalse(BaggageKeys.isAcceptableValue(value), value + " must be unacceptable");
        }
        assertTrue(BaggageKeys.isAcceptableValue("t1"));
        assertTrue(BaggageKeys.isAcceptableValue("x".repeat(BaggageKeys.MAX_VALUE_LENGTH)));
        assertFalse(BaggageKeys.isAcceptableValue("x".repeat(BaggageKeys.MAX_VALUE_LENGTH + 1)));
        assertFalse(BaggageKeys.isAcceptableValue(""));
        assertFalse(BaggageKeys.isAcceptableValue(null));
        assertFalse(BaggageKeys.isAcceptableValue("line\nbreak"));
        assertFalse(BaggageKeys.isAcceptableValue("c1\u0085"));
    }

    @Test
    void lifecycleTransitionsFollowTheVectors() throws IOException {
        JsonNode vectors = Vectors.read("primitives/lifecycle-transitions.json");
        Set<List<String>> allowed = new HashSet<>();
        for (JsonNode pair : vectors.get("allowed")) {
            List<String> names = Vectors.strings(pair);
            allowed.add(names);
            LifecycleState from = LifecycleState.parse(names.get(0)).orElseThrow();
            LifecycleState to = LifecycleState.parse(names.get(1)).orElseThrow();
            assertTrue(from.canTransitionTo(to), from + " -> " + to + " must be allowed");
        }
        for (LifecycleState from : LifecycleState.values()) {
            for (LifecycleState to : LifecycleState.values()) {
                if (from.canTransitionTo(to)) {
                    assertTrue(allowed.contains(List.of(from.stateName(), to.stateName())),
                            from + " -> " + to + " is allowed by the SDK but absent from the vector");
                }
            }
        }
        assertTrue(LifecycleState.READY.isServing());
        assertTrue(LifecycleState.DEGRADED.isServing());
        assertFalse(LifecycleState.STARTED.isServing());
        assertTrue(LifecycleState.STOPPED.isTerminal());
        assertEquals(0, LifecycleState.DECLARED.number());
        assertEquals(8, LifecycleState.FAILED.number());
        assertEquals(Optional.of(LifecycleState.FAILED), LifecycleState.fromNumber(8));
        assertEquals(Optional.empty(), LifecycleState.fromNumber(9));
        assertEquals(Optional.empty(), LifecycleState.parse("ready"));
    }

    @Test
    void pipelineStagesAreOrdered() {
        List<String> names = List.of("ingress", "observe", "resilience", "authenticate", "tenant", "authorize", "validate",
                "idempotency", "transaction", "handle", "egress");
        List<PipelineStage> stages = PipelineStage.all();
        assertEquals(names.size(), stages.size());
        for (int index = 0; index < names.size(); index++) {
            assertEquals(names.get(index), stages.get(index).manifestName());
            assertEquals(Optional.of(stages.get(index)), PipelineStage.parse(names.get(index)));
        }
        assertEquals(Optional.empty(), PipelineStage.parse("Handle"), "stage names are lowercase");
    }

    @Test
    void errorFactoriesCheckTheKind() {
        Error error = Error.validation("ORDERS_VALIDATION_001", "orders.quantity.invalid", Map.of("min", 1));
        assertEquals("VALIDATION", error.kind());
        assertEquals("ORDERS", error.domain());
        assertEquals("orders.quantity.invalid (min=1)", error.message());
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> Error.notFound("ORDERS_VALIDATION_001", "orders.missing"));
        assertEquals("The error code 'ORDERS_VALIDATION_001' must have the kind NOTFOUND.", mismatch.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Error.of("bad", "orders.missing"));
        assertThrows(IllegalArgumentException.class, () -> Error.of("ORDERS_NOTFOUND_001", "Orders.Missing"));
        assertThrows(IllegalArgumentException.class, () -> Error.literal("ORDERS_NOTFOUND_001", ""));
    }

    @Test
    void errorMessagesResolveThroughTheDefaultCatalog() {
        MessageScope.setDefault(MessageCatalog.of(Map.of(
                "en-US", Map.of("orders.missing", "Order {id} was not found."),
                "zh-CN", Map.of("orders.missing", "订单 {id} 不存在。"))), "zh-CN");
        Error error = Error.notFound("ORDERS_NOTFOUND_001", "orders.missing", Map.of("id", 42));
        assertEquals("订单 42 不存在。", error.message());
        assertEquals("literal", error.withText("literal").message(), "text wins");
        assertEquals("a of 007", MessageTemplate.format("{0} of {count:D3}", Map.of("0", "a", "count", 7)));
        assertEquals("{missing}", MessageTemplate.format("{missing}", Map.of()), "missing placeholders stay");
        // 占位符缺参数时严格解析失败，退回描述
        assertEquals("orders.missing", Error.notFound("ORDERS_NOTFOUND_001", "orders.missing").message());
        MessageScope.setDefault(MessageCatalog.of(Map.of("en-US", Map.of("orders.missing", "Order {id} was not found."))), "ja-JP");
        assertEquals("Order 7 was not found.", Error.notFound("ORDERS_NOTFOUND_001", "orders.missing", Map.of("id", 7)).message(),
                "a missing locale falls back to en-US");
    }

    @Test
    void messageKeysFollowTheSpecGrammar() {
        // RFC-0001 / RFC-0010（2026-10-28 修订）：首段与后段同形，可带下划线
        assertTrue(MessageTemplate.isValidKey("tenant_members.list.description"));
        assertTrue(MessageTemplate.isValidKey("kernel.invocation.deadline_exceeded"));
        for (String key : List.of("", "orders", "Orders.missing", "orders.", ".orders", "orders..missing", "1orders.missing", "orders.1x")) {
            assertFalse(MessageTemplate.isValidKey(key), key);
        }
        assertEquals("tenant_members", MessageTemplate.domainOf("tenant_members.list.description"));
    }

    @Test
    void templatesEscapeBracesAndKeepLiterals() {
        assertEquals("{x} = 1", MessageTemplate.format("{{x}} = {x}", Map.of("x", 1)));
        assertEquals("json {\"a\": 1} 2", MessageTemplate.format("json {\"a\": 1} {0}", MessageArguments.positional(2)));
        assertEquals("open { brace", MessageTemplate.format("open { brace", Map.of()));
        assertEquals(List.of("a", "b"), MessageTemplate.placeholdersOf("{a} {b:%d} {a} {{c}}"));
        assertEquals(Optional.empty(), MessageTemplate.tryFormat("{a} {b}", Map.of("a", 1)));
        assertEquals("1.5 and 2", MessageTemplate.format("{0} and {1}", MessageArguments.positional(1.5, 2.0)));
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("b", null);
        withNull.put("a", "x");
        assertEquals("kernel.feature.start_failed (a=x, b=)", MessageTemplate.describe("kernel.feature.start_failed", withNull));
        // 说明符与值的类型不符、或不是可移植的说明符：按无格式输出，不报错（RFC-0010「模板」）
        assertEquals("text", MessageTemplate.format("{n:D}", Map.of("n", "text")));
        assertEquals("7", MessageTemplate.format("{n:%03d}", Map.of("n", 7)), "printf specifiers are not portable and print the plain value");
    }

    @Test
    void detailsOnlyAcceptJsonValues() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", List.of(1, "two", true));
        nested.put("a", Map.of("k", 1.5));
        Error error = Error.validation("ORDERS_VALIDATION_001", "orders.invalid", nested);
        assertEquals(List.of("a", "z"), new ArrayList<>(error.details().keySet()), "details are sorted by name");
        assertThrows(UnsupportedOperationException.class, () -> error.details().put("x", 1));
        assertThrows(IllegalArgumentException.class,
                () -> Error.validation("ORDERS_VALIDATION_001", "orders.invalid", Map.of("when", java.time.Instant.EPOCH)));
        // 非有限的浮点可以放进细节，写到线上是字符串 NaN、Infinity（ADR-0236）
        assertEquals(Double.NaN, Error.validation("ORDERS_VALIDATION_001", "orders.invalid", Map.of("n", Double.NaN)).details().get("n"));
        assertThrows(IllegalArgumentException.class,
                () -> Error.validation("ORDERS_VALIDATION_001", "orders.invalid", Map.of("m", Map.of(1, "x"))));
    }

    @Test
    void resultsAreExclusiveAndComposable() {
        Result<Integer> doubled = Result.ok(21).map(value -> value * 2);
        assertEquals(Optional.of(42), doubled.value());
        Result<Map<String, Object>> failed = Result.fail(Error.literal("ORDERS_CONFLICT_001", "stale"));
        assertTrue(failed.isFailed());
        assertFalse(failed.flatMap(value -> Result.ok(1)).isOk(), "bind propagates failures");
        assertEquals("ORDERS_CONFLICT_001", failed.toUnit().error().orElseThrow().code());
        assertEquals(7, Result.<Integer>fail(Error.literal("ORDERS_CONFLICT_001", "stale")).orElse(7));
        FrameworkException thrown = assertThrows(FrameworkException.class, failed::orElseThrow);
        assertEquals("ORDERS_CONFLICT_001", thrown.code());
        assertEquals("stale", thrown.getMessage());
        assertThrows(NullPointerException.class, () -> Result.ok(null), "a successful result needs a value");
        assertEquals(Unit.VALUE, Result.success().orElseThrow());
        assertEquals("Ok(1)", Result.ok(1).toString());
        assertEquals("Fail(ORDERS_CONFLICT_001: stale)", failed.toString());
        assertEquals("ok", Result.ok(1).match(value -> "ok", error -> "failed"));
    }

    @Test
    void maybesAreNeverNull() {
        assertFalse(Maybe.none().hasValue());
        assertEquals(5, Maybe.some(5).orElse(0));
        assertEquals(Maybe.none(), Maybe.ofNullable(null));
        assertThrows(NullPointerException.class, () -> Maybe.some(null));
        assertThrows(NoSuchElementException.class, () -> Maybe.none().orElseThrow());
        assertFalse(Maybe.<Integer>none().toResult(Error.literal("ORDERS_NOTFOUND_001", "missing")).isOk(), "none to result is a failure");
        assertEquals(Maybe.some(6), Maybe.some(3).map(value -> value * 2));
        assertEquals(Optional.of("x"), Maybe.fromOptional(Optional.of("x")).toOptional());
    }

    @Test
    void frameworkExceptionsCarryTheirCodeOntoTheWire() {
        FrameworkException inner = new FrameworkException("KERNEL_PIPELINE_001", "The ordering constraints form a cycle.");
        FrameworkException outer = new FrameworkException(ErrorCode.INVALID_ARGUMENT, "The manifest is not valid.", inner);
        Error error = outer.toError();
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.code());
        assertEquals(Optional.of("The manifest is not valid."), error.text());
        assertEquals("KERNEL_PIPELINE_001", error.inner().orElseThrow().code());
        assertThrows(IllegalArgumentException.class, () -> new FrameworkException("bad", "message"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkException(ErrorCode.INVALID_ARGUMENT, ""));
    }

    @Test
    void stabilityNames() {
        for (String name : List.of("stable", "preview", "experimental", "internal", "deprecated")) {
            assertEquals(name, Stability.parse(name).orElseThrow().wireName());
        }
        assertEquals(Optional.empty(), Stability.parse("Stable"));
    }

    @Test
    void localesAreValidatedAndComparedWithoutCase() {
        assertTrue(MessageScope.isValidLocale("zh-CN"));
        assertTrue(MessageScope.isValidLocale("zh-Hant-TW"));
        assertFalse(MessageScope.isValidLocale("zh_CN"));
        assertFalse(MessageScope.isValidLocale("a"));
        assertThrows(IllegalArgumentException.class, () -> MessageScope.setDefault(MessageCatalog.of(Map.of()), "not a locale"));
        MessageCatalog catalog = MessageCatalog.of(Map.of("zh-cn", Map.of("orders.missing", "订单不存在。")));
        assertEquals(Set.of("zh-CN"), catalog.locales());
        assertEquals(Optional.of("订单不存在。"), catalog.resolve("orders.missing", "ZH-cn", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> MessageCatalog.of(Map.of("en-US", Map.of("Bad.Key", "x"))));
    }

    @Test
    void sdkVersionMatchesTheProjectVersion() {
        String projectVersion = System.getProperty("xihan.project.version");
        if (projectVersion != null) {
            assertEquals(projectVersion, FrameworkInfo.SDK_VERSION);
        }
        assertEquals("1", FrameworkInfo.SPEC_VERSION);
    }
}
