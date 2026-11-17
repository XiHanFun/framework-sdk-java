package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import java.io.Serial;

/**
 * {@link Unit} 的线上编码：空对象 {@code {}}。
 */
final class UnitCodec {
    private UnitCodec() {
    }

    /**
     * 写出 {@code {}}。
     */
    static final class Serializer extends StdSerializer<Unit> {
        @Serial
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(Unit.class);
        }

        @Override
        public void serialize(Unit unit, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeEndObject();
        }
    }

    /**
     * 接受任意 JSON 对象（成员忽略，与 Go 解码 {@code struct{}} 一致），其他值拒绝。
     */
    static final class Deserializer extends StdDeserializer<Unit> {
        @Serial
        private static final long serialVersionUID = 1L;

        Deserializer() {
            super(Unit.class);
        }

        @Override
        public Unit deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                return context.reportInputMismatch(Unit.class, "%s", "A unit value on the wire must be a JSON object.");
            }
            parser.skipChildren();
            return Unit.VALUE;
        }
    }
}
