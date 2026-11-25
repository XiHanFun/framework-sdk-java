package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.xihanfun.framework.internal.Whitespace;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.MessageTemplate;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 协议消息与 SDK 类型之间的转换（{@code invocation.md} §2、§3）：错误按 RFC-0001 的线上编码一一对应，上下文按 RFC-0003 平铺。
 */
final class Wire {
    /** 细节按值模型写出：经原语的线上编码，不另写一份数字规则。 */
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new PrimitivesModule());

    /** 时间文本换算成 UTC 之后（不计小数）允许的最早时刻（RFC-0003 规则 8，截止时间与信封的 {@code time} 共用）。 */
    private static final Instant MIN_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");

    /** 时间文本换算成 UTC 之后（不计小数）允许的最晚时刻。 */
    private static final Instant MAX_TIMESTAMP = Instant.parse("9999-12-31T23:59:59Z");

    /** 写出截止时间时能表示的最晚时刻：七位小数的最大值。 */
    private static final Instant MAX_WRITTEN_DEADLINE = MAX_TIMESTAMP.plusNanos(999_999_900);

    /** 写出截止时间的固定偏移（.NET 的 {@code O} 格式在 UTC 时的写法）。 */
    private static final String UTC_OFFSET = "+00:00";

    private final String pluginId;

    Wire(String pluginId) {
        this.pluginId = pluginId;
    }

    String pluginId() {
        return pluginId;
    }

    // ---- 结果与错误 ----

    PluginProto.Status status(Result<Unit> result) {
        if (result.isOk()) {
            return PluginProto.Status.newBuilder().setOk(true).build();
        }
        return PluginProto.Status.newBuilder().setOk(false).setError(error(result.error().orElseThrow())).build();
    }

    /**
     * 写出错误（{@code invocation.md} §3）：{@code code}、{@code message_key} 原样；带消息键的错误 {@code text} 留空，由宿主按目录翻译成调用方的语言
     * （RFC-0010），只有自由文本错误写 {@code text}；{@code details} 是按值模型写出的 JSON 对象文本，没有时为空；{@code inner} 同样写出。
     */
    static PluginProto.Error error(Error error) {
        PluginProto.Error.Builder builder = PluginProto.Error.newBuilder().setCode(error.code());
        if (error.messageKey().isPresent()) {
            builder.setMessageKey(error.messageKey().get());
        } else {
            builder.setText(error.text().orElse(error.code()));
        }
        if (!error.details().isEmpty()) {
            builder.setDetails(details(error));
        }
        error.inner().ifPresent(inner -> builder.setInner(error(inner)));
        return builder.build();
    }

    /**
     * 读入宿主交回的结果（回调用，{@code invocation.md} §3）：{@code ok} 为假却没有 {@code error} 时为 {@code PLUGINS_UNEXPECTED_001} +
     * {@value PluginMessages#FAILED}（{@code reason} 为 {@code the host reported a failure without an error}）。
     *
     * @param phase 失败归属的阶段：操作 ID 或 {@code publish}
     */
    Result<Unit> readStatus(PluginProto.Status status, String phase) {
        if (status != null && status.getOk()) {
            return Result.success();
        }
        if (status == null || !status.hasError()) {
            return Result.fail(failed(phase, "the host reported a failure without an error"));
        }
        return Result.fail(readError(status.getError()));
    }

    /**
     * 读入对方写来的错误（{@code invocation.md} §3）：{@code code} 不合错误码文法时整个错误换成 {@code PLUGINS_UNEXPECTED_001} +
     * {@value PluginMessages#FAILED}（{@code phase} 为 {@code invoke}）；{@code message_key} 合消息键文法的按键处理，否则成为自由文本错误，
     * 文案取 {@code text}，为空或只有空白（{@link Whitespace}）时取 {@code code}；{@code details} 为空、通不过共同 JSON 读取检查或不是对象时当作没有细节；
     * {@code inner} 同此。
     */
    Error readError(PluginProto.Error error) {
        String code = error.getCode();
        if (!ErrorCode.isValid(code)) {
            return failed("invoke", "invalid error code '" + code + "'");
        }
        Map<String, Object> details = readDetails(error.getDetails());
        // 内部错误的嵌套层数受 protobuf 解码的递归上限约束，递归不会无界
        Optional<Error> inner = error.hasInner() ? Optional.of(readError(error.getInner())) : Optional.empty();
        String key = error.getMessageKey();
        if (!key.isEmpty() && MessageTemplate.isValidKey(key)) {
            return inner.map(nested -> Error.of(code, key, details, nested)).orElseGet(() -> Error.of(code, key, details));
        }
        // Error 的文案不能只有空白（RFC-0001「空白字符」，ADR-0245）：只有空白的 text 与空串一样取 code
        String text = Whitespace.isBlank(error.getText()) ? code : error.getText();
        return inner.map(nested -> Error.literal(code, text, details, nested)).orElseGet(() -> Error.literal(code, text, details));
    }

    /**
     * 读细节：先过共同的 JSON 读取检查（RFC-0001，重复成员、超过 64 层、不合法的 Unicode 都不通过），再要求是对象；
     * 数字按值模型读成 64 位整数或双精度浮点。任何一步不合都当作没有细节。
     */
    static Map<String, Object> readDetails(String text) {
        if (text.isEmpty()) {
            return Map.of();
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (JsonText.check(bytes).isPresent()) {
            return Map.of();
        }
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return objectValue(node);
        } catch (IOException | ArithmeticException invalid) {
            return Map.of();
        }
    }

    private static Map<String, Object> objectValue(JsonNode node) {
        TreeMap<String, Object> map = new TreeMap<>();
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            map.put(member.getKey(), value(member.getValue()));
        }
        return map;
    }

    private static Object value(JsonNode node) {
        if (node.isObject()) {
            return objectValue(node);
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(element -> list.add(value(element)));
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            // 值模型：64 位范围内的整数原样，超出时按双精度浮点（RFC-0001「Result」）
            BigInteger integer = node.bigIntegerValue();
            if (integer.bitLength() < Long.SIZE) {
                return integer.longValue();
            }
            return integer.doubleValue();
        }
        if (node.isNumber()) {
            double number = node.doubleValue();
            if (!Double.isFinite(number)) {
                // 超出双精度范围的数读不成值模型里的数，与原语的严格读取一样不收
                throw new ArithmeticException("The number is out of the double range.");
            }
            return number;
        }
        return null;
    }

    private static String details(Error error) {
        try {
            JsonNode encoded = JSON.readTree(JSON.writeValueAsBytes(error));
            return JSON.writeValueAsString(encoded.get("details"));
        } catch (IOException impossible) {
            // 细节在创建错误时已校验为 JSON 值，编码不会失败
            throw new IllegalStateException("The error details cannot be encoded.", impossible);
        }
    }

    /** {@code PLUGINS_UNAVAILABLE_001} + {@value PluginMessages#UNAVAILABLE}：不在可服务状态（{@code lifecycle.md} §7.3）。 */
    Error unavailable(LifecycleState state) {
        return Error.unavailable(PluginErrors.UNAVAILABLE, PluginMessages.UNAVAILABLE, Map.of("id", pluginId, "state", state.wireName()));
    }

    /** {@code PLUGINS_UNEXPECTED_001} + {@value PluginMessages#FAILED}；{@code phase} 一律小写或操作 ID（{@code invocation.md} §8）。 */
    Error failed(String phase, String reason) {
        return Error.unexpected(PluginErrors.UNEXPECTED, PluginMessages.FAILED, Map.of("id", pluginId, "phase", phase, "reason", reason));
    }

    /** {@code PLUGINS_UNAVAILABLE_001} + {@value PluginMessages#HOST_UNAVAILABLE}：回调连不上宿主（{@code invocation.md} §5）。 */
    Error hostUnavailable(String reason) {
        return Error.unavailable(PluginErrors.UNAVAILABLE, PluginMessages.HOST_UNAVAILABLE, Map.of("id", pluginId, "reason", reason));
    }

    /**
     * 回调抛出时的 {@code reason}（{@code lifecycle.md} §7.6）：异常的简单类型名，不带包名，不带异常的文本（文本只进 stderr 日志）。
     * 匿名类与局部类没有简单名，取去掉包名的二进制名（如 {@code EchoPlugin$1}）。
     */
    static String typeName(Throwable thrown) {
        Class<?> type = thrown.getClass();
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    // ---- 上下文 ----

    /**
     * 从协议元数据还原调用上下文（{@code invocation.md} §2）：{@code trace_parent} 按 RFC-0003 的文法取，不合文法即没有父 Span；
     * {@code trace_state} 不进上下文；Baggage 只收已登记的键，不可接受的值由构建器丢弃；截止时间取 {@code deadline}（读不出当作没有）
     * 与 gRPC 调用自身截止时间中较早的一个。
     */
    static Context.Builder context(PluginProto.InvocationMetadata metadata, Optional<Instant> transportDeadline) {
        Context.Builder builder = Context.builder();
        String declared = "";
        if (metadata != null) {
            builder.traceParent(metadata.getTraceParent());
            declared = metadata.getDeadline();
            for (Map.Entry<String, String> entry : metadata.getBaggageMap().entrySet()) {
                if (BaggageKeys.isRegistered(entry.getKey())) {
                    builder.baggage(entry.getKey(), entry.getValue());
                }
            }
        }
        effectiveDeadline(declared, transportDeadline).ifPresent(builder::deadline);
        return builder;
    }

    /**
     * 把调用上下文写成回调的元数据（{@code invocation.md} §5）：当前 Span 的 traceparent、按 §2 写法的截止时间、上下文里已登记的 Baggage，
     * {@code trace_state} 为空。
     */
    static PluginProto.InvocationMetadata metadata(Context context) {
        PluginProto.InvocationMetadata.Builder builder = PluginProto.InvocationMetadata.newBuilder().setTraceParent(context.traceParent());
        context.deadline().ifPresent(deadline -> builder.setDeadline(formatDeadline(deadline)));
        builder.putAllBaggage(context.withoutUnregisteredBaggage().baggage());
        return builder.build();
    }

    /**
     * 生效的截止时间（{@code invocation.md} §2）：{@code metadata} 按 {@link #parseTimestamp(String)} 读、读不出当作没有，与 gRPC 调用自身的截止时间
     * 取较早的一个；两者都没有时没有截止时间。
     */
    static Optional<Instant> effectiveDeadline(String metadata, Optional<Instant> transport) {
        Optional<Instant> declared = parseTimestamp(metadata);
        if (declared.isPresent() && transport.isPresent()) {
            return Optional.of(transport.get().isBefore(declared.get()) ? transport.get() : declared.get());
        }
        return declared.isPresent() ? declared : transport;
    }

    /**
     * 按 RFC-0003 规则 8 严格读时间文本：{@code InvocationMetadata.deadline}（{@code invocation.md} §2）与 {@code Envelope.time}（§4，RFC-0009「信封」
     * 第 3 条）共用这条文法，
     * {@code ^[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})\z}，
     * 年 0001–9999、月日按公历（含闰年）、时 00–23、分 00–59、秒 00–59（不认闰秒）、偏移的时 00–23 与分 00–59，
     * 换算成 UTC 之后（不计小数）在 {@code 0001-01-01T00:00:00} 到 {@code 9999-12-31T23:59:59} 之间。{@link Instant} 精确到纳秒，九位小数不必截断。
     *
     * @return UTC 时刻；空串、{@code null} 或读不出时为空
     */
    static Optional<Instant> parseTimestamp(String text) {
        // 固定部分 yyyy-MM-ddTHH:mm:ss 是 19 个字符，之后至少还有一个字符的偏移
        if (text == null || text.length() < 20 || text.charAt(4) != '-' || text.charAt(7) != '-'
                || (text.charAt(10) != 'T' && text.charAt(10) != 't') || text.charAt(13) != ':' || text.charAt(16) != ':') {
            return Optional.empty();
        }
        int year = digits(text, 0, 4);
        int month = digits(text, 5, 7);
        int day = digits(text, 8, 10);
        int hour = digits(text, 11, 13);
        int minute = digits(text, 14, 16);
        int second = digits(text, 17, 19);
        if (year < 1 || month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month) || hour < 0 || hour > 23
                || minute < 0 || minute > 59 || second < 0 || second > 59) {
            return Optional.empty();
        }
        int position = 19;
        int nanos = 0;
        if (text.charAt(position) == '.') {
            int end = position + 1;
            while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
                end++;
            }
            int count = end - position - 1;
            if (count < 1 || count > 9) {
                return Optional.empty();
            }
            nanos = digits(text, position + 1, end);
            // 不足九位的小数按右补零换算成纳秒
            for (int index = count; index < 9; index++) {
                nanos *= 10;
            }
            position = end;
        }
        String rest = text.substring(position);
        int offset;
        if (rest.equals("Z") || rest.equals("z")) {
            offset = 0;
        } else if (rest.length() == 6 && (rest.charAt(0) == '+' || rest.charAt(0) == '-') && rest.charAt(3) == ':') {
            int offsetHours = digits(rest, 1, 3);
            int offsetMinutes = digits(rest, 4, 6);
            if (offsetHours < 0 || offsetHours > 23 || offsetMinutes < 0 || offsetMinutes > 59) {
                return Optional.empty();
            }
            offset = (offsetHours * 60 + offsetMinutes) * 60 * (rest.charAt(0) == '-' ? -1 : 1);
        } else {
            return Optional.empty();
        }
        // 范围按换算成 UTC 之后、不计小数的时刻判定，所以 9999-12-31T23:59:59.999999999Z 仍然合法
        Instant utc = LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).minusSeconds(offset);
        if (utc.isBefore(MIN_TIMESTAMP) || utc.isAfter(MAX_TIMESTAMP)) {
            return Optional.empty();
        }
        return Optional.of(utc.plusNanos(nanos));
    }

    /**
     * 按 .NET 的 {@code O} 格式写出截止时间（{@code invocation.md} §2）：换算成 UTC、七位小数、{@code +00:00} 偏移；比 100 纳秒更细的部分截断、
     * 不进位，小数不足七位补零。超出 {@code 0001}–{@code 9999} 年的时刻夹到这个范围的两端，写出的文本总能被对方读出。
     */
    static String formatDeadline(Instant deadline) {
        Instant clamped = deadline.isBefore(MIN_TIMESTAMP) ? MIN_TIMESTAMP
                : deadline.isAfter(MAX_WRITTEN_DEADLINE) ? MAX_WRITTEN_DEADLINE : deadline;
        OffsetDateTime utc = clamped.atOffset(ZoneOffset.UTC);
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d.%07d%s", utc.getYear(), utc.getMonthValue(),
                utc.getDayOfMonth(), utc.getHour(), utc.getMinute(), utc.getSecond(), utc.getNano() / 100, UTC_OFFSET);
    }

    /** 读一段全是 ASCII 数字的文本（全角数字等其他数字字符不算）；有别的字符时为 -1。 */
    private static int digits(String text, int from, int to) {
        int value = 0;
        for (int index = from; index < to; index++) {
            char c = text.charAt(index);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /** 公历某年某月的天数：四年一闰、百年不闰、四百年再闰。 */
    private static int daysInMonth(int year, int month) {
        return switch (month) {
            case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    // ---- 信封与健康 ----

    /**
     * 按 RFC 3339 写出信封的 {@code time}（RFC-0009「信封」第 3 条）：换算成 UTC、以 {@code Z} 结尾，秒的小数去掉末尾的 0，全为 0 时不写小数点，
     * 最多九位，如 {@code 2026-11-25T03:25:00.5Z}；六个 SDK 对同一时刻写出同一份文本（精度以内）。年份按四位补零，范围由 {@link Envelope.Builder#time}
     * 核对过。
     */
    static String formatTime(Instant time) {
        OffsetDateTime utc = time.atOffset(ZoneOffset.UTC);
        String text = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth(),
                utc.getHour(), utc.getMinute(), utc.getSecond());
        if (utc.getNano() == 0) {
            return text + "Z";
        }
        String fraction = String.format(Locale.ROOT, "%09d", utc.getNano());
        int end = fraction.length();
        while (fraction.charAt(end - 1) == '0') {
            end--;
        }
        return text + "." + fraction.substring(0, end) + "Z";
    }

    /**
     * 把宿主写来的信封读成 SDK 的类型（{@code invocation.md} §4）：{@code time} 按 {@link #parseTimestamp(String)} 读成 UTC 时刻，{@code dataschema}
     * 原样，两者的空串都是没有。
     *
     * @return 信封；{@code time} 不合 RFC-0003 规则 8 时为空
     */
    static Optional<Envelope> envelope(PluginProto.Envelope envelope) {
        Envelope.Builder builder = Envelope.builder(envelope.getTopic(), envelope.getId(), envelope.getSource(), envelope.getType())
                .data(envelope.getData().toByteArray())
                .extensions(envelope.getExtensionsMap());
        if (!envelope.getSpecVersion().isEmpty()) {
            builder.specVersion(envelope.getSpecVersion());
        }
        if (!envelope.getSubject().isEmpty()) {
            builder.subject(envelope.getSubject());
        }
        if (!envelope.getTime().isEmpty()) {
            Optional<Instant> time = parseTimestamp(envelope.getTime());
            if (time.isEmpty()) {
                return Optional.empty();
            }
            builder.time(time.get());
        }
        if (!envelope.getDataContentType().isEmpty()) {
            builder.dataContentType(envelope.getDataContentType());
        }
        if (!envelope.getDataSchema().isEmpty()) {
            builder.dataSchema(envelope.getDataSchema());
        }
        return Optional.of(builder.build());
    }

    static PluginProto.Envelope envelope(Envelope envelope) {
        PluginProto.Envelope.Builder builder = PluginProto.Envelope.newBuilder()
                .setTopic(envelope.topic())
                .setId(envelope.id())
                .setSource(envelope.source())
                .setType(envelope.type())
                .setSpecVersion(envelope.specVersion())
                .setDataContentType(envelope.dataContentType())
                .setData(ByteString.copyFrom(envelope.data()))
                .putAllExtensions(envelope.extensions());
        envelope.subject().ifPresent(builder::setSubject);
        envelope.time().map(Wire::formatTime).ifPresent(builder::setTime);
        envelope.dataSchema().ifPresent(builder::setDataSchema);
        return builder.build();
    }

    static PluginProto.HealthResponse health(PluginHealth health) {
        PluginProto.HealthResponse.Builder builder = PluginProto.HealthResponse.newBuilder()
                .setState(PluginProto.LifecycleState.forNumber(health.state().number()));
        for (HealthItem item : health.items()) {
            PluginProto.HealthItem.Builder encoded = PluginProto.HealthItem.newBuilder()
                    .setName(item.name())
                    .setStatus(PluginProto.HealthStatus.forNumber(item.status().number()));
            item.description().ifPresent(encoded::setDescription);
            builder.addItems(encoded);
        }
        health.error().ifPresent(error -> builder.setError(error(error)));
        return builder.build();
    }
}
