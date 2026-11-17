package com.xihanfun.framework.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

/**
 * 请求与响应的 JSON Schema（draft 2020-12）文本（RFC-0011 OperationSchema）：两段都必须是 JSON 对象。
 */
public final class OperationSchema {
    /** 只用来核对文本是 JSON 对象。 */
    private static final ObjectMapper PARSER = new ObjectMapper();

    private final String request;
    private final String response;

    private OperationSchema(String request, String response) {
        this.request = request;
        this.response = response;
    }

    /**
     * 创建 Schema。
     *
     * @param request 请求的 JSON Schema 文本
     * @param response 响应的 JSON Schema 文本
     * @return Schema
     * @throws IllegalArgumentException 任一段不是 JSON 对象
     */
    public static OperationSchema of(String request, String response) {
        return new OperationSchema(requireObject(request, "request"), requireObject(response, "response"));
    }

    /**
     * 返回请求的 JSON Schema 文本。
     *
     * @return 文本
     */
    public String request() {
        return request;
    }

    /**
     * 返回响应的 JSON Schema 文本。
     *
     * @return 文本
     */
    public String response() {
        return response;
    }

    /**
     * 按两段文本比较。
     *
     * @param other 另一个对象
     * @return 相等时为真
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof OperationSchema that && request.equals(that.request) && response.equals(that.response);
    }

    /**
     * 返回与 {@link #equals(Object)} 一致的散列码。
     *
     * @return 散列码
     */
    @Override
    public int hashCode() {
        return Objects.hash(request, response);
    }

    private static String requireObject(String text, String what) {
        Objects.requireNonNull(text, what);
        JsonNode node;
        try {
            node = PARSER.readTree(text);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("The " + what + " schema must be a JSON object.", invalid);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("The " + what + " schema must be a JSON object.");
        }
        return text;
    }
}
