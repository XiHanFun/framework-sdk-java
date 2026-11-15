package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.primitives.TypedId;
import java.io.IOException;
import java.io.Serial;

/**
 * {@link TypedId} 的线上编码：底层值的编码，标签不上线。
 */
final class TypedIdCodec {
    private TypedIdCodec() {
    }

    /**
     * 写出底层值。
     */
    static final class Serializer extends StdSerializer<TypedId<?, ?>> {
        @Serial
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(TypedId.class, false);
        }

        @Override
        public void serialize(TypedId<?, ?> id, JsonGenerator generator, SerializerProvider provider) throws IOException {
            provider.defaultSerializeValue(id.value(), generator);
        }
    }

    /**
     * 按 {@code TypedId<Tag, V>} 的第二个类型参数解码底层值。
     */
    static final class Deserializer extends StdDeserializer<TypedId<?, ?>> implements ContextualDeserializer {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 底层值的类型；没有上下文时为 {@code null}，按 {@code Object} 解码。 */
        private final JavaType valueType;

        Deserializer(JavaType valueType) {
            super(TypedId.class);
            this.valueType = valueType;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            JavaType type = context.getContextualType();
            if (type == null && property != null) {
                type = property.getType();
            }
            JavaType contained = type != null && type.containedTypeCount() > 1 ? type.containedType(1) : context.constructType(Object.class);
            return new Deserializer(contained);
        }

        @Override
        public TypedId<?, ?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JavaType type = valueType != null ? valueType : context.constructType(Object.class);
            Object value = context.readValue(parser, type);
            if (value == null) {
                return context.reportInputMismatch(TypedId.class, "%s", "A typed ID on the wire needs a value.");
            }
            return TypedId.of(value);
        }
    }
}
