package com.xihanfun.framework.manifest;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.xihanfun.framework.primitives.ErrorCode;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Stability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 清单的 JSON 读取与规范化输出。
 *
 * <p>读取严格按 Schema：未知字段、字段类型不符（如数字写成 JSON 数字而不是字符串）、重复的键、JSON 之后多余的内容都算失败，
 * 再经 {@link Manifest.Builder#build()} 校验格式与跨字段约束。输出两空格缩进、字段按 Schema 顺序、缺省值省略，与 Go SDK 逐字节一致。
 */
public final class ManifestJson {
    /** 接口版本：只许正整数。 */
    private static final Pattern VERSION = Pattern.compile("^[1-9][0-9]*$");

    /** 版本范围：{@code ^N} 与 {@code N} 等价。 */
    private static final Pattern RANGE = Pattern.compile("^\\^?([1-9][0-9]*)$");

    /** 只用树模型，不做反射绑定；重复的键与尾随内容都拒绝。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Set<String> MANIFEST_FIELDS = Set.of("$schema", "id", "package", "version", "primitives", "stability", "kind",
            "role", "isolation", "provides", "requires", "bindings", "contributes", "activation", "permissions", "entry");

    private ManifestJson() {
    }

    /**
     * 解析并校验清单 JSON。
     *
     * @param json UTF-8 编码的清单
     * @return 清单
     * @throws FrameworkException 不是合 Schema 的 JSON，或违反格式与跨字段约束（{@code PRIMITIVES_VALIDATION_001}）
     */
    public static Manifest read(byte[] json) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException invalid) {
            throw new FrameworkException(ErrorCode.INVALID_ARGUMENT, "The manifest is not valid JSON for the schema.", invalid);
        }
        return fromTree(root);
    }

    /**
     * 解析并校验清单 JSON 文本。
     *
     * @param json 清单文本
     * @return 清单
     * @throws FrameworkException 不是合 Schema 的 JSON，或违反格式与跨字段约束（{@code PRIMITIVES_VALIDATION_001}）
     */
    public static Manifest read(String json) {
        return read(Objects.requireNonNull(json, "json").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取并校验清单文件。
     *
     * @param path 清单文件，通常名为 {@value Manifest#FILE_NAME}
     * @return 清单
     * @throws IOException 文件读不出来
     * @throws FrameworkException 内容不合法（{@code PRIMITIVES_VALIDATION_001}）
     */
    public static Manifest readFile(Path path) throws IOException {
        return read(Files.readAllBytes(Objects.requireNonNull(path, "path")));
    }

    /**
     * 输出规范化的清单 JSON：两空格缩进，字段按 Schema 顺序，没写的可选字段与空列表省略，没有末尾换行。
     *
     * @param manifest 清单；实例总是合法的，所以输出不会失败
     * @return JSON 文本
     */
    public static String write(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        Map<String, Object> object = new LinkedHashMap<>();
        manifest.schema().ifPresent(value -> object.put("$schema", value));
        object.put("id", manifest.id());
        manifest.packageId().ifPresent(value -> object.put("package", value));
        object.put("version", manifest.version());
        object.put("primitives", Integer.toString(manifest.primitives()));
        object.put("stability", manifest.stability().wireName());
        object.put("kind", manifest.kind().wireName());
        manifest.role().ifPresent(value -> object.put("role", value.wireName()));
        manifest.isolation().ifPresent(value -> object.put("isolation", value.wireName()));
        putList(object, "provides", manifest.provides(), declaration -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", declaration.id());
            item.put("version", Integer.toString(declaration.version()));
            return item;
        });
        putList(object, "requires", manifest.requires(), requirement -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", requirement.id());
            item.put("version", Integer.toString(requirement.version()));
            if (requirement.optional()) {
                item.put("optional", true);
            }
            return item;
        });
        putList(object, "bindings", manifest.bindings(), binding -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", binding.name());
            item.put("type", binding.type());
            binding.required().ifPresent(value -> item.put("required", value));
            return item;
        });
        manifest.contributes().ifPresent(contributions -> object.put("contributes", contributions(contributions)));
        putList(object, "activation", manifest.activation(), Function.identity());
        putList(object, "permissions", manifest.permissions(), Function.identity());
        manifest.entry().ifPresent(value -> object.put("entry", value));
        return CanonicalJson.write(object);
    }

    private static Map<String, Object> contributions(Contributions contributions) {
        Map<String, Object> object = new LinkedHashMap<>();
        putList(object, "pipeline", contributions.pipeline(), contribution -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("middleware", contribution.middleware());
            item.put("stage", contribution.stage().manifestName());
            putList(item, "before", contribution.before(), Function.identity());
            putList(item, "after", contribution.after(), Function.identity());
            return item;
        });
        putList(object, "operations", contributions.operations(), Function.identity());
        contributions.config().ifPresent(config -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("section", config.section());
            config.schema().ifPresent(value -> item.put("schema", value));
            object.put("config", item);
        });
        putList(object, "permissions", contributions.permissions(), Function.identity());
        putList(object, "health", contributions.health(), Function.identity());
        return object;
    }

    private static <T> void putList(Map<String, Object> object, String name, List<T> values, Function<? super T, ?> mapper) {
        // 空列表与没写等价，按缺省省略
        if (!values.isEmpty()) {
            List<Object> items = new ArrayList<>(values.size());
            values.forEach(value -> items.add(mapper.apply(value)));
            object.put(name, items);
        }
    }

    private static Manifest fromTree(JsonNode root) {
        try {
            JsonNode object = requireObject(root, "the manifest");
            requireKnownFields(object, "the manifest", MANIFEST_FIELDS);
            Manifest.Builder builder = Manifest.builder();
            optionalString(object, "$schema").ifPresent(builder::schema);
            builder.id(requiredString(object, "id"));
            optionalString(object, "package").ifPresent(builder::packageId);
            builder.version(requiredString(object, "version"));
            builder.primitives(version(requiredString(object, "primitives"), "primitives", VERSION));
            builder.stability(named(requiredString(object, "stability"), "stability", Stability::parse));
            builder.kind(named(requiredString(object, "kind"), "kind", ManifestKind::parse));
            optionalString(object, "role").ifPresent(value -> builder.role(named(value, "role", ManifestRole::parse)));
            optionalString(object, "isolation").ifPresent(value -> builder.isolation(named(value, "isolation", PluginIsolation::parse)));
            builder.provides(objects(object, "provides", Set.of("id", "version"),
                    item -> CapabilityDeclaration.of(requiredString(item, "id"), version(requiredString(item, "version"), "provides[].version", VERSION))));
            builder.requires(objects(object, "requires", Set.of("id", "version", "optional"), item -> new CapabilityRequirement(
                    requiredString(item, "id"), version(requiredString(item, "version"), "requires[].version", RANGE),
                    optionalBoolean(item, "optional").orElse(false))));
            builder.bindings(objects(object, "bindings", Set.of("name", "type", "required"), item -> {
                Binding binding = Binding.of(requiredString(item, "name"), requiredString(item, "type"));
                return optionalBoolean(item, "required").map(binding::withRequired).orElse(binding);
            }));
            JsonNode contributes = object.get("contributes");
            if (contributes != null) {
                builder.contributes(contributions(contributes));
            }
            builder.activation(strings(object, "activation"));
            builder.permissions(strings(object, "permissions"));
            optionalString(object, "entry").ifPresent(builder::entry);
            return builder.build();
        } catch (IllegalArgumentException invalid) {
            // 组成类型在构造时校验格式：读清单时它们的失败同样是清单不合法
            throw new FrameworkException(ErrorCode.INVALID_ARGUMENT, invalid.getMessage(), invalid);
        }
    }

    private static Contributions contributions(JsonNode node) {
        JsonNode object = requireObject(node, "'contributes'");
        requireKnownFields(object, "'contributes'", Set.of("pipeline", "operations", "config", "permissions", "health"));
        Contributions.Builder builder = Contributions.builder();
        builder.pipeline(objects(object, "pipeline", Set.of("middleware", "stage", "before", "after"), item -> {
            PipelineContribution contribution = PipelineContribution.of(requiredString(item, "middleware"),
                    named(requiredString(item, "stage"), "stage", PipelineStage::parse));
            return contribution.withBefore(strings(item, "before")).withAfter(strings(item, "after"));
        }));
        builder.operations(strings(object, "operations"));
        JsonNode config = object.get("config");
        if (config != null) {
            JsonNode configObject = requireObject(config, "'contributes.config'");
            requireKnownFields(configObject, "'contributes.config'", Set.of("section", "schema"));
            ConfigContribution contribution = ConfigContribution.of(requiredString(configObject, "section"));
            builder.config(optionalString(configObject, "schema").map(contribution::withSchema).orElse(contribution));
        }
        builder.permissions(strings(object, "permissions"));
        builder.health(strings(object, "health"));
        return builder.build();
    }

    private static JsonNode requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw schema(what + " must be a JSON object");
        }
        return node;
    }

    private static void requireKnownFields(JsonNode object, String what, Set<String> known) {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (!known.contains(field.getKey())) {
                throw schema("the field '" + field.getKey() + "' of " + what + " is not defined by the schema");
            }
        }
    }

    private static String requiredString(JsonNode object, String name) {
        return optionalString(object, name).orElseThrow(() -> schema("the field '" + name + "' is required"));
    }

    private static Optional<String> optionalString(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw schema("the field '" + name + "' must be a string");
        }
        return Optional.of(value.textValue());
    }

    private static Optional<Boolean> optionalBoolean(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isBoolean()) {
            throw schema("the field '" + name + "' must be a boolean");
        }
        return Optional.of(value.booleanValue());
    }

    private static List<String> strings(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw schema("the field '" + name + "' must be an array of strings");
        }
        List<String> values = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw schema("the field '" + name + "' must be an array of strings");
            }
            values.add(element.textValue());
        }
        return values;
    }

    private static <T> List<T> objects(JsonNode object, String name, Set<String> known, Function<JsonNode, T> mapper) {
        JsonNode value = object.get(name);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw schema("the field '" + name + "' must be an array");
        }
        List<T> values = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            JsonNode item = requireObject(element, "an item of '" + name + "'");
            requireKnownFields(item, "an item of '" + name + "'", known);
            values.add(mapper.apply(item));
        }
        return values;
    }

    private static int version(String text, String name, Pattern grammar) {
        var match = grammar.matcher(text);
        if (!match.matches()) {
            throw schema("the field '" + name + "' must be a positive integer string such as \"1\", got '" + text + "'");
        }
        String digits = match.groupCount() > 0 ? match.group(1) : text;
        return InterfaceVersions.parse(digits).orElseThrow(() -> schema("the field '" + name + "' is out of range: '" + text + "'"));
    }

    private static <T> T named(String text, String name, Function<String, Optional<T>> parser) {
        return parser.apply(text).orElseThrow(() -> schema("the " + name + " '" + text + "' is not valid"));
    }

    private static FrameworkException schema(String detail) {
        return new FrameworkException(ErrorCode.INVALID_ARGUMENT, "The manifest is not valid JSON for the schema: " + detail + ".");
    }
}
