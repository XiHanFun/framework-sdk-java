package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.xihanfun.framework.primitives.JsonText;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Optional;

/**
 * Result 与 Error 线上编码的严格写出（RFC-0001「写出与转交」，ADR-0320）：先写进缓冲，整段文本过共同检查再交给外层的写出器。
 *
 * <p>写出的字符串（成员名与值，含细节、内部错误与成功值）有孤立的代理项，从结果或错误的根算起嵌套超过 {@value JsonText#MAX_DEPTH} 层，
 * 或任何一层有重复的成员名（自定义序列化器、{@code @JsonRawValue} 原样嵌进去的文本）时写不出，抛出 {@link JsonMappingException}，
 * 外层的写出器一个字符也不写；不写成 U+FFFD 或 {@code \ud800} 这样的转义。Jackson 写出时把孤立的代理项写成 Unicode 转义、不查重复成员、
 * 嵌套上限也远大于 64，所以不靠它的写出约束。
 */
final class StrictWrites {
    /** 核对用的写出器：按字符写出，孤立的代理项原样留在文本里，由 {@link JsonText#check(String)} 的严格编码拦下。 */
    private static final JsonFactory FACTORY = new JsonFactory();

    private StrictWrites() {
    }

    /** 往写出器里写一段线上编码。 */
    @FunctionalInterface
    interface Contents {
        /**
         * 写出这段编码。
         *
         * @param generator 写出器（缓冲）
         * @throws IOException 写出失败
         */
        void write(JsonGenerator generator) throws IOException;
    }

    /**
     * 写进缓冲、核对整段文本，通过之后再写进 {@code generator}。
     *
     * @param generator 外层的写出器
     * @param provider 序列化上下文
     * @param what 写的是什么，用在异常消息里：{@code result} 或 {@code error}
     * @param contents 这段编码
     * @throws JsonMappingException 文本没过共同检查
     * @throws IOException 写出失败
     */
    static void write(JsonGenerator generator, SerializerProvider provider, String what, Contents contents) throws IOException {
        TokenBuffer buffer = new TokenBuffer(generator.getCodec(), false);
        contents.write(buffer);
        StringWriter text = new StringWriter();
        try (JsonGenerator replay = FACTORY.createGenerator(text)) {
            buffer.serialize(replay);
        }
        Optional<JsonText.Problem> problem = JsonText.check(text.toString());
        if (problem.isPresent()) {
            String reason = problem.get().syntax()
                    ? "it has a lone surrogate, more than " + JsonText.MAX_DEPTH + " levels of nesting or text that is not JSON"
                    : "the member '" + problem.get().path() + "' appears more than once";
            throw JsonMappingException.from(provider, "The " + what + " cannot be written to the wire: " + reason + ".");
        }
        buffer.serialize(generator);
    }
}
