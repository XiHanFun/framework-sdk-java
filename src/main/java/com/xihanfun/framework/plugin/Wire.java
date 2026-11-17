package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.plugin.v1.PluginProto;
import com.xihanfun.framework.primitives.BaggageKeys;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.MessageCatalog;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import com.xihanfun.framework.primitives.json.PrimitivesJson;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协议消息与 SDK 类型之间的转换（{@code invocation.md} §2、§3）：错误按 RFC-0001 的线上编码一一对应，上下文按 RFC-0003 平铺。
 */
final class Wire {
    /** 细节按值模型写出与读回：经原语的线上编码，不另写一份数字规则。 */
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new PrimitivesModule());

    /** RFC 3339 的时间：{@code T} 与 {@code Z} 不区分大小写。 */
    private static final DateTimeFormatter RFC_3339 = new DateTimeFormatterBuilder().parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME).toFormatter();

    private final String pluginId;

    /** 填写 {@code Error.text} 用的目录：插件自己的在前，运行时内置的在后。 */
    private final List<MessageCatalog> catalogs;

    /** 填写 {@code Error.text} 用的区域：宿主的默认区域。 */
    private final String locale;

    Wire(String pluginId, List<PluginCatalog> authored, String locale) {
        this.pluginId = pluginId;
        List<MessageCatalog> all = new ArrayList<>();
        authored.forEach(catalog -> all.add(catalog.messages()));
        all.add(PluginMessages.catalog().messages());
        this.catalogs = List.copyOf(all);
        this.locale = locale;
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
     * 编码错误：{@code text} 是已解析的文案（RFC-0001 线上编码的必填 {@code message}），{@code details} 是按值模型写出的 JSON 对象文本。
     */
    PluginProto.Error error(Error error) {
        PluginProto.Error.Builder builder = PluginProto.Error.newBuilder().setCode(error.code()).setText(text(error));
        error.messageKey().ifPresent(builder::setMessageKey);
        if (!error.details().isEmpty()) {
            builder.setDetails(details(error));
        }
        error.inner().ifPresent(inner -> builder.setInner(error(inner)));
        return builder.build();
    }

    /**
     * 解析文案：已有文案的原样；否则先按插件自己的目录与内置目录在宿主默认区域解析，再退回进程默认的解析器与「键 (参数)」的描述。
     */
    String text(Error error) {
        if (error.text().isPresent()) {
            return error.text().get();
        }
        if (error.messageKey().isPresent()) {
            for (MessageCatalog catalog : catalogs) {
                Optional<String> resolved = catalog.resolve(error.messageKey().get(), locale, error.details());
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
        }
        return error.message();
    }

    /**
     * 还原宿主交回的结果（回调用）：失败而没有错误、或错误不合 RFC-0001 的编码时为 {@code PLUGINS_UNEXPECTED_001}。
     */
    Result<Unit> fromStatus(PluginProto.Status status, String phase) {
        if (status != null && status.getOk()) {
            return Result.success();
        }
        if (status == null || !status.hasError()) {
            return Result.fail(failed(phase, "the host returned a failed status without an error"));
        }
        try {
            return Result.fail(fromError(status.getError()));
        } catch (IOException | IllegalArgumentException invalid) {
            return Result.fail(failed(phase, "the host returned an error that is not RFC-0001 encoded"));
        }
    }

    /**
     * 按 RFC-0001 的线上编码还原错误：拼成 {@code {"code", "message", "messageKey", "details", "inner"}} 再经原语的解码器读，
     * 细节先过共同的 JSON 文本检查（重复成员在读成树之前就要拒绝）。
     */
    static Error fromError(PluginProto.Error error) throws IOException {
        return PrimitivesJson.read(JSON, JSON.writeValueAsBytes(errorNode(error, 0)), Error.class);
    }

    private static ObjectNode errorNode(PluginProto.Error error, int depth) throws IOException {
        if (depth >= JsonText.MAX_DEPTH) {
            throw new IOException("The error nests more than " + JsonText.MAX_DEPTH + " levels.");
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("code", error.getCode());
        // 线上的 message 必填非空：宿主没给文案时用键或码，接收方仍按键再翻译
        String message = !error.getText().isEmpty() ? error.getText()
                : !error.getMessageKey().isEmpty() ? error.getMessageKey()
                : error.getCode();
        node.put("message", message);
        if (!error.getMessageKey().isEmpty()) {
            node.put("messageKey", error.getMessageKey());
        }
        if (!error.getDetails().isEmpty()) {
            byte[] details = error.getDetails().getBytes(StandardCharsets.UTF_8);
            if (JsonText.check(details).isPresent()) {
                throw new IOException("The error details are not valid JSON text.");
            }
            node.set("details", JSON.readTree(details));
        }
        if (error.hasInner()) {
            node.set("inner", errorNode(error.getInner(), depth + 1));
        }
        return node;
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

    Error unavailable(LifecycleState state) {
        return Error.unavailable(PluginErrors.UNAVAILABLE, PluginMessages.UNAVAILABLE, Map.of("id", pluginId, "state", state.stateName()));
    }

    Error failed(String phase, String reason) {
        return Error.unexpected(PluginErrors.UNEXPECTED, PluginMessages.FAILED, Map.of("id", pluginId, "phase", phase, "reason", reason));
    }

    Error failed(String phase, String reason, Error inner) {
        return Error.unexpected(PluginErrors.UNEXPECTED, PluginMessages.FAILED,
                Map.of("id", pluginId, "phase", phase, "reason", reason), inner);
    }

    // ---- 上下文 ----

    /**
     * 从协议元数据还原调用上下文（{@code invocation.md} §2）：追踪取 {@code trace_parent}，截止时间取 {@code deadline} 与 gRPC 截止时间中
     * 较早的一个（二者本应相同，下游的截止时间不晚于上游，RFC-0003 规则 2）；Baggage 只收已登记的键（RFC-0003 规则 3），
     * 不可接受的值由构建器丢弃。
     */
    static Context.Builder context(PluginProto.InvocationMetadata metadata, Optional<Instant> transportDeadline) {
        Context.Builder builder = Context.builder();
        Optional<Instant> deadline = transportDeadline;
        if (metadata != null) {
            if (!metadata.getTraceParent().isEmpty()) {
                builder.traceParent(metadata.getTraceParent());
            }
            Optional<Instant> declared = parseDeadline(metadata.getDeadline());
            if (declared.isPresent() && (deadline.isEmpty() || declared.get().isBefore(deadline.get()))) {
                deadline = declared;
            }
            for (Map.Entry<String, String> entry : metadata.getBaggageMap().entrySet()) {
                if (BaggageKeys.isRegistered(entry.getKey())) {
                    builder.baggage(entry.getKey(), entry.getValue());
                }
            }
        }
        deadline.ifPresent(builder::deadline);
        return builder;
    }

    /**
     * 把调用上下文写成协议元数据（RFC-0003 规则 3）：追踪、截止时间（UTC 的 RFC 3339）与已登记的 Baggage。
     */
    static PluginProto.InvocationMetadata metadata(Context context) {
        PluginProto.InvocationMetadata.Builder builder = PluginProto.InvocationMetadata.newBuilder().setTraceParent(context.traceParent());
        context.deadline().ifPresent(deadline -> builder.setDeadline(DateTimeFormatter.ISO_INSTANT.format(deadline)));
        builder.putAllBaggage(context.withoutUnregisteredBaggage().baggage());
        return builder.build();
    }

    /** 解析 RFC 3339 的截止时间；空串或写法不合时没有。 */
    static Optional<Instant> parseDeadline(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(text, RFC_3339).toInstant());
        } catch (DateTimeParseException invalid) {
            return Optional.empty();
        }
    }

    // ---- 信封与健康 ----

    static Envelope envelope(PluginProto.Envelope envelope) {
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
            builder.time(envelope.getTime());
        }
        if (!envelope.getDataContentType().isEmpty()) {
            builder.dataContentType(envelope.getDataContentType());
        }
        return builder.build();
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
        envelope.time().ifPresent(builder::setTime);
        return builder.build();
    }

    PluginProto.HealthResponse health(PluginHealth health) {
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
