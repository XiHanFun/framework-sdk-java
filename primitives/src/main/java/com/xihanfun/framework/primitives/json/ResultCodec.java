package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.io.Serial;

/**
 * {@link Result} 的线上编码：{@code {"ok":true,"value":…}} 或 {@code {"ok":false,"error":{…}}}。
 */
final class ResultCodec {
    private ResultCodec() {
    }

    /**
     * 写出 {@code ok} 与 {@code value} 或 {@code error}。
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
                generator.writeFieldName("value");
                provider.defaultSerializeValue(result.value().orElseThrow(), generator);
            } else {
                generator.writeBooleanField("ok", false);
                generator.writeFieldName("error");
                provider.defaultSerializeValue(result.error().orElseThrow(), generator);
            }
            generator.writeEndObject();
        }
    }

    /**
     * 按 {@code Result<T>} 的类型参数解码成功值；{@code Result<Unit>} 不要求有值，其他成功结果必须带非 {@code null} 的值。
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
            JsonNode node = context.readTree(parser);
            if (!node.isObject()) {
                return context.reportInputMismatch(Result.class, "%s", "A result on the wire must be a JSON object.");
            }
            JsonNode ok = node.get("ok");
            if (ok == null || !ok.isBoolean()) {
                return context.reportInputMismatch(Result.class, "%s", "A result on the wire needs a boolean 'ok'.");
            }
            if (ok.booleanValue()) {
                JavaType type = valueType != null ? valueType : context.constructType(Object.class);
                if (type.hasRawClass(Unit.class)) {
                    return Result.success();
                }
                JsonNode value = node.get("value");
                if (value == null || value.isNull()) {
                    return context.reportInputMismatch(Result.class, "%s", "A successful result on the wire needs a value.");
                }
                Object decoded = context.readTreeAsValue(value, type);
                if (decoded == null) {
                    return context.reportInputMismatch(Result.class, "%s", "A successful result on the wire needs a value.");
                }
                return Result.ok(decoded);
            }
            JsonNode error = node.get("error");
            if (error == null || error.isNull()) {
                return context.reportInputMismatch(Result.class, "%s", "A failed result on the wire needs an error.");
            }
            return Result.fail(ErrorCodec.Deserializer.fromNode(error, context));
        }
    }
}
