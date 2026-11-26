package com.xihanfun.framework.internal;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * 按值模型写出 JSON 值（RFC-0001「Result」，ADR-0236）：错误的细节表只含 JSON 值，原语的线上编码与插件协议的 {@code Error.details}
 * 共用这一份写法，不受 {@code ObjectMapper} 的配置影响。
 */
public final class JsonValueWriter {
    private JsonValueWriter() {
    }

    /**
     * 写出一个 JSON 值：细节表的成员已经由 {@code Error} 校验为 JSON 值。数字按值模型：
     * <ul>
     *   <li>64 位范围内的整数写十进制整数</li>
     *   <li>超出 64 位的整数、单精度与十进制小数先换成双精度浮点</li>
     *   <li>浮点总写成带小数点或指数的写法（{@code 2.0}、{@code -0.0}、{@code 1.0E17}），对方才会读回浮点；
     *       非有限的写成字符串 {@code NaN}、{@code Infinity}、{@code -Infinity}</li>
     * </ul>
     *
     * @param generator 写出器
     * @param value JSON 值：{@code null}、字符串、布尔、数字、列表或键是字符串的映射
     * @throws IOException 写出器失败
     */
    public static void write(JsonGenerator generator, Object value) throws IOException {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof String text) {
            generator.writeString(text);
        } else if (value instanceof Boolean bool) {
            generator.writeBoolean(bool);
        } else if (value instanceof BigInteger integer) {
            if (integer.bitLength() < Long.SIZE) {
                generator.writeNumber(integer.longValue());
            } else {
                writeDouble(generator, integer.doubleValue());
            }
        } else if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) {
            writeDouble(generator, ((Number) value).doubleValue());
        } else if (value instanceof Number number) {
            generator.writeNumber(number.longValue());
        } else if (value instanceof List<?> list) {
            generator.writeStartArray();
            for (Object element : list) {
                write(generator, element);
            }
            generator.writeEndArray();
        } else if (value instanceof Map<?, ?> map) {
            generator.writeStartObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                generator.writeFieldName((String) entry.getKey());
                write(generator, entry.getValue());
            }
            generator.writeEndObject();
        } else {
            throw new IllegalStateException("The value of type '" + value.getClass().getName() + "' is not a JSON value.");
        }
    }

    private static void writeDouble(JsonGenerator generator, double number) throws IOException {
        if (Double.isNaN(number)) {
            generator.writeString("NaN");
        } else if (Double.isInfinite(number)) {
            generator.writeString(number > 0 ? "Infinity" : "-Infinity");
        } else {
            // Double.toString 总带小数点或指数：2.0、-0.0、1.0E17、4.9E-324，都是 JSON 的数
            generator.writeNumber(Double.toString(number));
        }
    }
}
