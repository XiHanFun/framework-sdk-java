package com.xihanfun.framework.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Stability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 清单的 JSON 读取与写出（RFC-0002 解析规则 2 与「写出」，ADR-0236），与 .NET 的 {@code ManifestReader} 报同样的错误、写出同样的文本。
 *
 * <p>读取按这个顺序，每一步只报第一处问题；错误码都是 {@value #INVALID_MANIFEST}：
 * <ol>
 *   <li>JSON 文本：过 RFC-0001「JSON 文本」的共同检查。不是 JSON 为 {@code kernel.manifest.invalid_json}（{@code reason} 为 {@code $}），
 *       任何一层有重复成员时同一个键、{@code reason} 为第一处重复的路径。{@link #read(byte[])} 按原样检查，开头的 BOM 是语法错误；
 *       {@link #readFile(Path)} 跳过开头的 UTF-8 BOM，文件不存在为 {@code kernel.manifest.file_missing}（参数 {@code path}）</li>
 *   <li>根是字面量 {@code null}：{@code kernel.manifest.empty}，没有参数</li>
 *   <li>形状：按 Schema 逐项核对（{@link ManifestShape}），第一处问题为 {@code kernel.manifest.invalid}，{@code reason} 是该处的路径</li>
 *   <li>跨字段约束（{@link Manifest.Builder#build()}）：保留 {@code primitives.manifest.*} 的消息键与参数 {@code id}，错误码换成 {@value #INVALID_MANIFEST}</li>
 * </ol>
 *
 * <p>写出的排版是规范的一部分：两个空格缩进、只用 LF、末尾不换行、成员名与值之间是 {@code ": "}；成员按 Schema 的顺序，没有值的可选成员不写，
 * 列表总写（空列表写 {@code []}），{@code role} 缺省写 {@code provider}、{@code activation} 缺省写 {@code ["onStartup"]}；
 * 字符串只转义必须转义的字符（{@link JsonText#quote(String)}）。
 */
public final class ManifestJson {
    /** 清单读取失败的错误码（.NET 的 {@code KernelErrors.InvalidManifest}）。 */
    public static final String INVALID_MANIFEST = "KERNEL_CAPABILITY_006";

    /** 不是 JSON 或有重复成员，参数 {@code reason}。 */
    public static final String INVALID_JSON = "kernel.manifest.invalid_json";

    /** 根是字面量 {@code null}，没有参数。 */
    public static final String EMPTY = "kernel.manifest.empty";

    /** 不合 Schema，参数 {@code reason}。 */
    public static final String INVALID = "kernel.manifest.invalid";

    /** 清单文件不存在，参数 {@code path}。 */
    public static final String FILE_MISSING = "kernel.manifest.file_missing";

    /** 写出时固定的 {@code $schema}。 */
    public static final String SCHEMA_URI = "https://spec.xihanfun.com/manifest/v1.json";

    /** 只用树模型，不做反射绑定；文本已过共同检查，Jackson 的 ObjectNode 按文档顺序保留成员。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ManifestJson() {
    }

    /**
     * 读取 UTF-8 编码的清单。
     *
     * @param utf8 原样的字节；开头的 BOM 是语法错误
     * @return 清单
     * @throws FrameworkException 读取失败（{@value #INVALID_MANIFEST}，消息键与参数见类说明）
     */
    public static Manifest read(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        Optional<JsonText.Problem> problem = JsonText.check(utf8);
        if (problem.isPresent()) {
            throw failure(INVALID_JSON, Map.of("reason", problem.get().path()));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(utf8);
        } catch (IOException impossible) {
            throw new IllegalStateException("The manifest passed the JSON text check but Jackson could not read it.", impossible);
        }
        if (root.isNull()) {
            throw failure(EMPTY, Map.of());
        }
        Optional<String> path = ManifestShape.findProblem(root);
        if (path.isPresent()) {
            throw failure(INVALID, Map.of("reason", path.get()));
        }
        Manifest.Builder builder = convert(root);
        try {
            return builder.build();
        } catch (FrameworkException crossField) {
            // 形状已核对，到这里只剩跨字段约束：保留它的消息键与参数，错误码换成清单读取失败
            Error error = crossField.toError();
            if (error.messageKey().filter(key -> key.startsWith("primitives.manifest.")).isEmpty()) {
                throw new IllegalStateException("The manifest passed the schema check but was rejected: " + error.message(), crossField);
            }
            throw new FrameworkException(Error.of(INVALID_MANIFEST, error.messageKey().get(), error.details()));
        }
    }

    /**
     * 读取清单文本：按 UTF-8 编码后读，有孤立的代理项（编不成 UTF-8）时是 {@code kernel.manifest.invalid_json}。
     *
     * @param json 清单文本
     * @return 清单
     * @throws FrameworkException 读取失败
     */
    public static Manifest read(String json) {
        Optional<byte[]> utf8 = JsonText.encode(Objects.requireNonNull(json, "json"));
        if (utf8.isEmpty()) {
            throw failure(INVALID_JSON, Map.of("reason", JsonText.ROOT));
        }
        return read(utf8.get());
    }

    /**
     * 读取清单文件：按 UTF-8 读，跳过开头的 UTF-8 BOM；不是合法 UTF-8 时同样是 {@code kernel.manifest.invalid_json}。
     *
     * @param path 清单文件，通常名为 {@value Manifest#FILE_NAME}
     * @return 清单
     * @throws IOException 文件存在但读不出来
     * @throws FrameworkException 文件不存在（{@code kernel.manifest.file_missing}，参数 {@code path} 是给出的路径），或读取失败
     */
    public static Manifest readFile(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            throw failure(FILE_MISSING, Map.of("path", path.toString()));
        }
        byte[] bytes = Files.readAllBytes(path);
        boolean bom = bytes.length >= UTF8_BOM.length && Arrays.equals(bytes, 0, UTF8_BOM.length, UTF8_BOM, 0, UTF8_BOM.length);
        return read(bom ? Arrays.copyOfRange(bytes, UTF8_BOM.length, bytes.length) : bytes);
    }

    /**
     * 写出清单，排版见类说明。
     *
     * @param manifest 清单；实例总是合法的，所以写出不会失败
     * @return JSON 文本
     */
    public static String write(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        IndentedWriter writer = new IndentedWriter();
        writer.startObject();
        writer.member("$schema").string(SCHEMA_URI);
        writer.member("id").string(manifest.id());
        manifest.packageId().ifPresent(value -> writer.member("package").string(value));
        writer.member("version").string(manifest.version());
        writer.member("primitives").string(Integer.toString(manifest.primitives()));
        writer.member("stability").string(manifest.stability().wireName());
        writer.member("kind").string(manifest.kind().wireName());
        writer.member("role").string(manifest.effectiveRole().wireName());
        manifest.isolation().ifPresent(value -> writer.member("isolation").string(value.wireName()));
        writer.member("provides").objects(manifest.provides(), (w, declaration) -> {
            w.member("id").string(declaration.id());
            w.member("version").string(Integer.toString(declaration.version()));
        });
        writer.member("requires").objects(manifest.requires(), (w, requirement) -> {
            w.member("id").string(requirement.id());
            w.member("version").string(Integer.toString(requirement.version()));
            w.member("optional").bool(requirement.optional());
        });
        writer.member("bindings").objects(manifest.bindings(), (w, binding) -> {
            w.member("name").string(binding.name());
            w.member("type").string(binding.type());
            w.member("required").bool(binding.isRequired());
        });
        Contributions contributions = manifest.effectiveContributions();
        writer.member("contributes").startObject();
        writer.member("pipeline").objects(contributions.pipeline(), (w, contribution) -> {
            w.member("middleware").string(contribution.middleware());
            w.member("stage").string(contribution.stage().manifestName());
            w.member("before").strings(contribution.before());
            w.member("after").strings(contribution.after());
        });
        writer.member("operations").strings(contributions.operations());
        contributions.config().ifPresent(config -> {
            writer.member("config").startObject();
            writer.member("section").string(config.section());
            config.schema().ifPresent(value -> writer.member("schema").string(value));
            writer.endObject();
        });
        writer.member("permissions").strings(contributions.permissions());
        writer.member("health").strings(contributions.health());
        writer.endObject();
        writer.member("activation").strings(manifest.effectiveActivation());
        writer.member("permissions").strings(manifest.permissions());
        manifest.entry().ifPresent(value -> writer.member("entry").string(value));
        writer.endObject();
        return writer.toString();
    }

    /**
     * 把已核对形状的树转成构建器：取值都已合文法，组成类型的构造不会再失败。
     */
    private static Manifest.Builder convert(JsonNode object) {
        Manifest.Builder builder = Manifest.builder();
        text(object, "$schema").ifPresent(builder::schema);
        builder.id(object.get("id").textValue());
        text(object, "package").ifPresent(builder::packageId);
        builder.version(object.get("version").textValue());
        builder.primitives(Integer.parseInt(object.get("primitives").textValue()));
        builder.stability(Stability.parse(object.get("stability").textValue()).orElseThrow());
        builder.kind(ManifestKind.parse(object.get("kind").textValue()).orElseThrow());
        text(object, "role").ifPresent(value -> builder.role(ManifestRole.parse(value).orElseThrow()));
        text(object, "isolation").ifPresent(value -> builder.isolation(PluginIsolation.parse(value).orElseThrow()));
        builder.provides(objects(object, "provides", item -> CapabilityDeclaration.of(item.get("id").textValue(),
                Integer.parseInt(item.get("version").textValue()))));
        builder.requires(objects(object, "requires", item -> {
            String version = item.get("version").textValue();
            boolean optional = item.has("optional") && item.get("optional").booleanValue();
            return new CapabilityRequirement(item.get("id").textValue(), Integer.parseInt(version.startsWith("^") ? version.substring(1) : version),
                    optional);
        }));
        builder.bindings(objects(object, "bindings", item -> {
            Binding binding = Binding.of(item.get("name").textValue(), item.get("type").textValue());
            return item.has("required") ? binding.withRequired(item.get("required").booleanValue()) : binding;
        }));
        JsonNode contributes = object.get("contributes");
        if (contributes != null) {
            Contributions.Builder contributions = Contributions.builder();
            contributions.pipeline(objects(contributes, "pipeline", item -> PipelineContribution.of(item.get("middleware").textValue(),
                    PipelineStage.parse(item.get("stage").textValue()).orElseThrow())
                    .withBefore(strings(item, "before")).withAfter(strings(item, "after"))));
            contributions.operations(strings(contributes, "operations"));
            JsonNode config = contributes.get("config");
            if (config != null) {
                ConfigContribution contribution = ConfigContribution.of(config.get("section").textValue());
                contributions.config(text(config, "schema").map(contribution::withSchema).orElse(contribution));
            }
            contributions.permissions(strings(contributes, "permissions"));
            contributions.health(strings(contributes, "health"));
            builder.contributes(contributions.build());
        }
        if (object.has("activation")) {
            builder.activation(strings(object, "activation"));
        }
        builder.permissions(strings(object, "permissions"));
        text(object, "entry").ifPresent(builder::entry);
        return builder;
    }

    private static Optional<String> text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value == null ? Optional.empty() : Optional.of(value.textValue());
    }

    private static List<String> strings(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(value.size());
        value.forEach(element -> values.add(element.textValue()));
        return values;
    }

    private static <T> List<T> objects(JsonNode object, String name, Function<JsonNode, T> mapper) {
        JsonNode value = object.get(name);
        if (value == null) {
            return List.of();
        }
        List<T> values = new ArrayList<>(value.size());
        value.forEach(element -> values.add(mapper.apply(element)));
        return values;
    }

    private static FrameworkException failure(String key, Map<String, ?> arguments) {
        return new FrameworkException(Error.of(INVALID_MANIFEST, key, arguments));
    }

    /**
     * 按规范排版写 JSON：每个成员与数组项另起一行，缩进两个空格乘以层数；空数组写 {@code []}。
     */
    private static final class IndentedWriter {
        private final StringBuilder builder = new StringBuilder();

        /** 每层是否已经写过成员或数组项。 */
        private final Deque<Boolean> written = new ArrayDeque<>();

        IndentedWriter startObject() {
            builder.append('{');
            written.push(false);
            return this;
        }

        void endObject() {
            close('}');
        }

        IndentedWriter member(String name) {
            beforeItem();
            JsonText.appendQuoted(builder, name);
            builder.append(": ");
            return this;
        }

        void string(String value) {
            JsonText.appendQuoted(builder, value);
        }

        void bool(boolean value) {
            builder.append(value);
        }

        void strings(List<String> values) {
            if (values.isEmpty()) {
                builder.append("[]");
                return;
            }
            builder.append('[');
            written.push(false);
            for (String value : values) {
                beforeItem();
                JsonText.appendQuoted(builder, value);
            }
            close(']');
        }

        <T> void objects(List<T> values, BiConsumer<IndentedWriter, T> members) {
            if (values.isEmpty()) {
                builder.append("[]");
                return;
            }
            builder.append('[');
            written.push(false);
            for (T value : values) {
                beforeItem();
                startObject();
                members.accept(this, value);
                endObject();
            }
            close(']');
        }

        private void beforeItem() {
            if (written.pop()) {
                builder.append(',');
            }
            written.push(true);
            builder.append('\n').append(" ".repeat(written.size() * 2));
        }

        private void close(char bracket) {
            if (written.pop()) {
                builder.append('\n').append(" ".repeat(written.size() * 2));
            }
            builder.append(bracket);
        }

        @Override
        public String toString() {
            return builder.toString();
        }
    }
}
