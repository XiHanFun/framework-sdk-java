package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * 按名字编码的枚举：稳定性、管道阶段与生命周期状态。
 */
final class NameCodec {
    private NameCodec() {
    }

    /** 取枚举的线上名；可序列化，编解码器随 Jackson 模块一起序列化时不丢。 */
    @FunctionalInterface
    interface Namer<E> extends Serializable {
        String name(E value);
    }

    /** 按线上名解析枚举；可序列化。 */
    @FunctionalInterface
    interface Parser<E> extends Serializable {
        Optional<E> parse(String name);
    }

    /**
     * 写出线上名。
     */
    static final class Serializer<E> extends StdSerializer<E> {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Namer<E> namer;

        Serializer(Class<E> type, Namer<E> namer) {
            super(type);
            this.namer = namer;
        }

        @Override
        public void serialize(E value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(namer.name(value));
        }
    }

    /**
     * 按线上名解码，名字未知或不是字符串即拒绝。
     */
    static final class Deserializer<E> extends StdDeserializer<E> {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 出错时说明里的种类名，如 {@code stability}。 */
        private final String description;

        private final Parser<E> parser;

        Deserializer(Class<E> type, String description, Parser<E> parser) {
            super(type);
            this.description = description;
            this.parser = parser;
        }

        @Override
        public E deserialize(JsonParser json, DeserializationContext context) throws IOException {
            if (json.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(handledType(), "A %s on the wire must be a string.", description);
            }
            String name = json.getText();
            Optional<E> value = parser.parse(name);
            if (value.isEmpty()) {
                return context.reportInputMismatch(handledType(), "'%s' is not a %s.", name, description);
            }
            return value.get();
        }
    }
}
