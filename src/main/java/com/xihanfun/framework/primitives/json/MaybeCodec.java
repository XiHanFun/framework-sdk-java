package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.primitives.Maybe;
import java.io.IOException;
import java.io.Serial;

/**
 * {@link Maybe} 的线上编码：有值为值本身，无值为 {@code null}。
 */
final class MaybeCodec {
    private MaybeCodec() {
    }

    /**
     * 有值写值本身，无值写 {@code null}；以 {@code NON_EMPTY} 包含规则时无值的字段省略。
     */
    static final class Serializer extends StdSerializer<Maybe<?>> {
        @Serial
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(Maybe.class, false);
        }

        @Override
        public boolean isEmpty(SerializerProvider provider, Maybe<?> value) {
            return value == null || !value.hasValue();
        }

        @Override
        public void serialize(Maybe<?> maybe, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (maybe.hasValue()) {
                provider.defaultSerializeValue(maybe.orElseThrow(), generator);
            } else {
                generator.writeNull();
            }
        }
    }

    /**
     * 按 {@code Maybe<T>} 的类型参数解码；{@code null} 与缺失的字段都是无值。
     */
    static final class Deserializer extends StdDeserializer<Maybe<?>> implements ContextualDeserializer {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 值的类型；没有上下文时为 {@code null}，按 {@code Object} 解码。 */
        private final JavaType valueType;

        Deserializer(JavaType valueType) {
            super(Maybe.class);
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
        public Maybe<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_NULL) {
                return Maybe.none();
            }
            JavaType type = valueType != null ? valueType : context.constructType(Object.class);
            return Maybe.ofNullable(context.readValue(parser, type));
        }

        @Override
        public Maybe<?> getNullValue(DeserializationContext context) {
            return Maybe.none();
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return Maybe.none();
        }
    }
}
