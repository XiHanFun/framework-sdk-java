package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.xihanfun.framework.internal.JsonValueWriter;
import com.xihanfun.framework.internal.Whitespace;
import com.xihanfun.framework.primitives.Error;
import java.io.IOException;
import java.io.Serial;
import java.util.Map;
import java.util.Optional;

/**
 * {@link Error} 的线上编码（RFC-0001「Result」，ADR-0233）。
 */
final class ErrorCodec {
    private ErrorCodec() {
    }

    /**
     * 写出 {@code code}、{@code message}、{@code messageKey}、{@code details}、{@code inner}：没有键、细节为空、没有内部错误时不写，
     * 不写 {@code null}；成员顺序固定。
     *
     * <p>写出是严格的（RFC-0001「写出与转交」，ADR-0320）：文案、细节或内部错误里有孤立的代理项，或从错误的根算起嵌套超过 64 层时写不出，
     * 抛出 {@link com.fasterxml.jackson.databind.JsonMappingException}，什么也不写。
     */
    static final class Serializer extends StdSerializer<Error> {
        @Serial
        private static final long serialVersionUID = 1L;

        Serializer() {
            super(Error.class);
        }

        @Override
        public void serialize(Error error, JsonGenerator generator, SerializerProvider provider) throws IOException {
            StrictWrites.write(generator, provider, "error", buffer -> write(error, buffer));
        }
    }

    /**
     * 写出一个错误与它的内部错误，不另做核对：由外层的 {@link StrictWrites} 统一核对整段文本。
     *
     * @param error 错误
     * @param generator 写出器
     * @throws IOException 写出失败
     */
    static void write(Error error, JsonGenerator generator) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("code", error.code());
        generator.writeStringField("message", error.message());
        if (error.messageKey().isPresent()) {
            generator.writeStringField("messageKey", error.messageKey().get());
        }
        if (!error.details().isEmpty()) {
            generator.writeFieldName("details");
            JsonValueWriter.write(generator, error.details());
        }
        if (error.inner().isPresent()) {
            generator.writeFieldName("inner");
            write(error.inner().get(), generator);
        }
        generator.writeEndObject();
    }

    /**
     * 从线上编码还原：{@code code} 必填且合文法，{@code message} 必填且非空；{@code messageKey}、{@code details}、{@code inner}
     * 缺失表示没有，写成 {@code null} 或类型不对都不合法；同一层重复的成员名不合法。未知成员忽略：v1 期间编码只增不改，新成员一律可选。
     */
    static final class Deserializer extends StdDeserializer<Error> {
        @Serial
        private static final long serialVersionUID = 1L;

        Deserializer() {
            super(Error.class);
        }

        @Override
        public Error deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // 逐层核对重复的成员名：错误自身、细节与内部错误（ADR-0235）
            return fromNode(StrictTrees.read(parser, context, Error.class), context);
        }

        static Error fromNode(JsonNode node, DeserializationContext context) throws IOException {
            if (node == null || !node.isObject()) {
                return fail(context, "An error on the wire must be a JSON object.");
            }
            JsonNode code = node.get("code");
            if (code == null || !code.isTextual()) {
                return fail(context, "An error on the wire needs a string 'code'.");
            }
            JsonNode message = node.get("message");
            // 空串与只有空白都不合法（RFC-0001「Result」与「空白字符」，ADR-0245）：Error 的文案至少要有一个空白之外的字符
            if (message == null || !message.isTextual() || Whitespace.isBlank(message.textValue())) {
                return fail(context, "An error on the wire needs a string 'message' that is not empty or whitespace.");
            }
            Optional<String> messageKey = Optional.empty();
            JsonNode key = node.get("messageKey");
            if (key != null) {
                if (!key.isTextual()) {
                    return fail(context, "The 'messageKey' of an error must be a string; leave it out when there is none.");
                }
                messageKey = Optional.of(key.textValue());
            }
            JsonNode details = node.get("details");
            if (details != null && !details.isObject()) {
                return fail(context, "The 'details' of an error must be a JSON object; leave it out when there are none.");
            }
            JsonNode innerNode = node.get("inner");
            if (innerNode != null && !innerNode.isObject()) {
                return fail(context, "The 'inner' of an error must be an error object; leave it out when there is none.");
            }
            Optional<Error> inner = innerNode == null ? Optional.empty() : Optional.of(fromNode(innerNode, context));
            try {
                return Error.restore(code.textValue(), messageKey, message.textValue(), details == null ? Map.of() : JsonTrees.toMap(details), inner);
            } catch (IllegalArgumentException invalid) {
                return fail(context, invalid.getMessage());
            }
        }

        private static Error fail(DeserializationContext context, String message) throws IOException {
            return context.reportInputMismatch(Error.class, "%s", message);
        }
    }
}
