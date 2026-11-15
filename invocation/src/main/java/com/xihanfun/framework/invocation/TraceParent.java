package com.xihanfun.framework.invocation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 的 {@code traceparent}：{@code version-traceid-spanid-flags}。
 *
 * <p>是否采样只看标志的最低位（W3C 的 {@code sampled}），其余位原样保留、不解释（RFC-0003 规则 7，ADR-0233）。
 *
 * @param traceId 追踪 ID，32 位小写十六进制，不能全为零
 * @param spanId Span ID（parent-id），16 位小写十六进制，不能全为零
 * @param flags trace-flags，0 到 255
 */
public record TraceParent(String traceId, String spanId, int flags) {
    /** 采样位。 */
    public static final int SAMPLED = 0x01;

    /** traceparent 文法：版本、追踪 ID、Span ID 与标志，都是小写十六进制。 */
    private static final Pattern PATTERN = Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 创建 traceparent。
     *
     * @param traceId 追踪 ID
     * @param spanId Span ID
     * @param flags trace-flags
     * @throws IllegalArgumentException ID 不合格式或全为零，或标志不在 0 到 255 之间
     */
    public TraceParent {
        if (!isTraceId(traceId)) {
            throw new IllegalArgumentException("The trace ID '" + traceId + "' must be 32 lowercase hexadecimal digits, not all zero.");
        }
        if (!isSpanId(spanId)) {
            throw new IllegalArgumentException("The span ID '" + spanId + "' must be 16 lowercase hexadecimal digits, not all zero.");
        }
        if (flags < 0 || flags > 0xFF) {
            throw new IllegalArgumentException("The trace flags must be a byte, got " + flags + ".");
        }
    }

    /**
     * 判断是否采样：标志的最低位。
     *
     * @return 采样时为真
     */
    public boolean sampled() {
        return (flags & SAMPLED) == SAMPLED;
    }

    /**
     * 解析 traceparent 文本；不合法时按 W3C 的要求当作没有。
     *
     * @param text traceparent；{@code null} 视为没有
     * @return 解析结果；不合法时为空
     */
    public static Optional<TraceParent> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher match = PATTERN.matcher(text);
        if (!match.matches() || ZERO_TRACE_ID.equals(match.group(2)) || ZERO_SPAN_ID.equals(match.group(3))) {
            return Optional.empty();
        }
        // 标志整字节保留：采样位是最低位，其他位留给以后的标志，原样传下去
        return Optional.of(new TraceParent(match.group(2), match.group(3), Integer.parseInt(match.group(4), 16)));
    }

    /**
     * 判断是否是合法的追踪 ID：32 位小写十六进制且不全为零。
     *
     * @param traceId 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isTraceId(String traceId) {
        return traceId != null && TRACE_ID.matcher(traceId).matches() && !ZERO_TRACE_ID.equals(traceId);
    }

    /**
     * 判断是否是合法的 Span ID：16 位小写十六进制且不全为零。
     *
     * @param spanId 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isSpanId(String spanId) {
        return spanId != null && SPAN_ID.matcher(spanId).matches() && !ZERO_SPAN_ID.equals(spanId);
    }

    /**
     * 生成新的追踪 ID。
     *
     * @return 32 位小写十六进制，不全为零
     */
    public static String newTraceId() {
        return randomHex(16, ZERO_TRACE_ID);
    }

    /**
     * 生成新的 Span ID。
     *
     * @return 16 位小写十六进制，不全为零
     */
    public static String newSpanId() {
        return randomHex(8, ZERO_SPAN_ID);
    }

    /**
     * 返回 {@code 00-<traceid>-<spanid>-<flags>}。
     *
     * @return traceparent 文本
     */
    @Override
    public String toString() {
        return "00-" + traceId + "-" + spanId + "-" + HEX[flags >> 4] + HEX[flags & 0xF];
    }

    /**
     * 生成随机十六进制：追踪标识只要求唯一而不要求不可预测，用线程本地的随机源，避免共享随机源在并发下争用。
     */
    private static String randomHex(int bytes, String zero) {
        Objects.requireNonNull(zero);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (true) {
            char[] digits = new char[bytes * 2];
            for (int index = 0; index < bytes; index++) {
                int value = random.nextInt(256);
                digits[index * 2] = HEX[value >> 4];
                digits[index * 2 + 1] = HEX[value & 0xF];
            }
            String text = new String(digits);
            // 全零的 ID 在 W3C 里不合法，概率极低但要重抽
            if (!zero.equals(text)) {
                return text;
            }
        }
    }
}
