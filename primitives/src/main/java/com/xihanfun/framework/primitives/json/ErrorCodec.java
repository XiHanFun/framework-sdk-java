package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.primitives.Error;
import java.io.IOException;
import java.io.Serial;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Error} 的线上编码（RFC-0001）。
 */
final class ErrorCodec {
    private ErrorCodec() {
    }

    /**
     * 写出 {@code code}、{@code message}、{@code messageKey}、{@code details}、{@code inner}；没有的省略，字段顺序固定。
     */
    static final class Serializer extends StdSerializer<Error> {
        @Serial
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(Error.class);
        }

        @Override
        public void serialize(Error error, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeStartObject();
            generator.writeStringField("code", error.code());
            generator.writeStringField("message", error.message());
            if (error.messageKey().isPresent()) {
                generator.writeStringField("messageKey", error.messageKey().get());
            }
            if (!error.details().isEmpty()) {
                generator.writeFieldName("details");
                JsonTrees.write(generator, error.details());
            }
            if (error.inner().isPresent()) {
                generator.writeFieldName("inner");
                serialize(error.inner().get(), generator, provider);
            }
            generator.writeEndObject();
        }
    }

    /**
     * 从线上编码还原；码不合法、消息键不合法、消息与消息键都没有时整体失败。未知字段忽略，便于以后加字段。
     */
    static final class Deserializer extends StdDeserializer<Error> {
        @Serial
        private static final long serialVersionUID = 1L;

        Deserializer() {
            super(Error.class);
        }

        @Override
        public Error deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return fromNode(context.readTree(parser), context);
        }

        static Error fromNode(JsonNode node, DeserializationContext context) throws IOException {
            if (node == null || !node.isObject()) {
                return context.reportInputMismatch(Error.class, "%s", "An error on the wire must be a JSON object.");
            }
            JsonNode code = node.get("code");
            if (code == null || !code.isTextual()) {
                return context.reportInputMismatch(Error.class, "%s", "An error on the wire needs a string 'code'.");
            }
            Optional<String> message = optionalText(node, "message", context);
            Optional<String> messageKey = optionalText(node, "messageKey", context);
            JsonNode details = node.get("details");
            if (details != null && !details.isNull() && !details.isObject()) {
                return context.reportInputMismatch(Error.class, "%s", "The 'details' of an error must be a JSON object.");
            }
            JsonNode innerNode = node.get("inner");
            Optional<Error> inner = innerNode == null || innerNode.isNull() ? Optional.empty() : Optional.of(fromNode(innerNode, context));
            try {
                return Error.restore(code.textValue(), messageKey, message,
                        details == null || details.isNull() ? Map.of() : JsonTrees.toMap(details), inner);
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(Error.class, "%s", invalid.getMessage());
            }
        }

        private static Optional<String> optionalText(JsonNode node, String name, DeserializationContext context) throws IOException {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            if (!value.isTextual()) {
                return context.reportInputMismatch(Error.class, "The '%s' of an error must be a string.", name);
            }
            return Optional.of(value.textValue());
        }
    }
}
