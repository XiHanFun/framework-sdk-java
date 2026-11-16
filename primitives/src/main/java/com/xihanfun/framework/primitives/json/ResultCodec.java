package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.primitives.Maybe;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.io.Serial;
import java.util.HashSet;
import java.util.Set;

/**
 * {@link Result} 的线上编码（RFC-0001「Result」，ADR-0233）：
 * 有值成功 {@code {"ok":true,"value":…}}，无值成功（{@code Result<Unit>}）只写 {@code {"ok":true}}，失败 {@code {"ok":false,"error":{…}}}。
 */
final class ResultCodec {
    private ResultCodec() {
    }

    /**
     * 写出 {@code ok} 与 {@code value} 或 {@code error}；无值成功不写 {@code value}。
     */
    static final class Serializer extends StdSerializer<Result<?>> {
        @Serial
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(Result.class, false);
        }

        @Override
        public void serialize(Result<?> result, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            if (result.isOk()) {
                generator.writeBooleanField("ok", true);
                Object value = result.value().orElseThrow();
                // 无值成功只写 ok：以前写成 {"ok":true,"value":{}}，与 .NET 的 {"ok":true} 互相读不出（ADR-0233）
                if (value != Unit.VALUE) {
                    generator.writeFieldName("value");
                    provider.defaultSerializeValue(value, generator);
                }
            } else {
                generator.writeBooleanField("ok", false);
                generator.writeFieldName("error");
                provider.defaultSerializeValue(result.error().orElseThrow(), generator);
            }
            generator.writeEndObject();
        }
    }

    /**
     * 按 {@code Result<T>} 的类型参数解码：
     * <ul>
     *   <li>{@code ok} 必填，是布尔；成功不能带 {@code error}，失败不能带 {@code value}，失败的 {@code error} 必填且不是 {@code null}</li>
     *   <li>{@code Result<Unit>}：成功不能带 {@code value}，{@code {}} 与 {@code null} 也不行</li>
     *   <li>{@code Result<Maybe<T>>}：{@code value} 必填，{@code null} 即无值</li>
     *   <li>其余：{@code value} 必填且不是 {@code null}</li>
     * </ul>
     * 未知成员忽略。
     */
    static final class Deserializer extends StdDeserializer<Result<?>> implements ContextualDeserializer {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 成功值的类型；没有上下文时为 {@code null}，按 {@code Object} 解码。 */
        private final JavaType valueType;

        Deserializer(JavaType valueType) {
            super(Result.class);
            this.valueType = valueType;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            JavaType type = context.getContextualType();
            if (type == null && property != null) {
                type = property.getType();
            }
            JavaType contained = type != null && type.containedTypeCount() > 0 ? type.containedType(0) : context.constructType(Object.class);
            return new Deserializer(contained);
        }

        @Override
        public Result<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                context.readTree(parser);
                return fail(context, "A result on the wire must be a JSON object.");
            }
            // 逐个读成员：同一层重复的成员名不合法（ADR-0235）；error 逐层核对，value 的内容交给它自己的类型
            JsonNode ok = null;
            JsonNode value = null;
            JsonNode error = null;
            Set<String> seen = new HashSet<>();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if (!seen.add(name)) {
                    return context.reportInputMismatch(Result.class, "The member '%s' appears more than once in a result.", name);
                }
                switch (name) {
                    case "ok" -> ok = StrictTrees.read(parser, context, Result.class);
                    case "value" -> value = context.readTree(parser);
                    case "error" -> error = StrictTrees.read(parser, context, Result.class);
                    default -> parser.skipChildren();
                }
            }
            if (ok == null || !ok.isBoolean()) {
                return fail(context, "A result on the wire needs a boolean 'ok'.");
            }
            if (!ok.booleanValue()) {
                if (value != null) {
                    return fail(context, "A failed result on the wire must not carry a 'value'.");
                }
                if (error == null || error.isNull()) {
                    return fail(context, "A failed result on the wire needs an error.");
                }
                return Result.fail(ErrorCodec.Deserializer.fromNode(error, context));
            }
            if (error != null) {
                return fail(context, "A successful result on the wire must not carry an 'error'.");
            }
            JavaType type = valueType != null ? valueType : context.constructType(Object.class);
            if (type.hasRawClass(Unit.class)) {
                if (value != null) {
                    return fail(context, "A successful result without a value is written as {\"ok\":true}; it must not carry a 'value'.");
                }
                return Result.success();
            }
            if (value == null) {
                return fail(context, "A successful result on the wire needs a 'value'.");
            }
            if (type.hasRawClass(Maybe.class) && value.isNull()) {
                return Result.ok(Maybe.none());
            }
            if (value.isNull()) {
                return fail(context, "The 'value' of a successful result must not be null.");
            }
            Object decoded = context.readTreeAsValue(value, type);
            if (decoded == null) {
                return fail(context, "The 'value' of a successful result must not be null.");
            }
            return Result.ok(decoded);
        }

        /**
         * JSON 的 {@code null} 不是结果：结果总有 {@code ok}，不以 {@code null} 表示没有。
         */
        @Override
        public Result<?> getNullValue(DeserializationContext context) throws JsonMappingException {
            return context.reportInputMismatch(Result.class, "%s", "A result on the wire must be a JSON object, not null.");
        }

        private static Result<?> fail(DeserializationContext context, String message) throws IOException {
            return context.reportInputMismatch(Result.class, "%s", message);
        }
    }
}
