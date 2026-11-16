package com.xihanfun.framework.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.primitives.JsonText;
import com.xihanfun.framework.primitives.PipelineStage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 按 Schema 逐项核对清单的形状（RFC-0002 解析规则 2 第 3 步，ADR-0236），与 .NET 的 {@code ManifestShape} 同一套规则、同一个顺序：
 * <ul>
 *   <li>对象的成员按文本里出现的顺序核对，每个成员依次查：Schema 之外的成员 → {@code null} → 类型 → 取值，对象与数组进到里面再查，
 *       数组项按下标顺序</li>
 *   <li>一个对象的成员都查完后，按 Schema 里 {@code required} 的顺序查缺少的成员，路径是缺少的成员本身</li>
 *   <li>文法按整串匹配；枚举只认小写名；接口版本是不带前导零、不超过 2147483647 的正整数，只有依赖的版本可带一个 {@code ^}</li>
 *   <li>{@code activation} 至少一项；{@code contributes.permissions} 不得重复，重复时报第二次出现的项</li>
 * </ul>
 * 第一处问题的路径写法同 RFC-0011 规则 2；根不是对象时为 {@code $}。树来自 Jackson 的 {@code ObjectNode}，它按文档顺序保留成员。
 */
final class ManifestShape {
    private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]*");

    private static final ObjectShape ROOT = root();

    private ManifestShape() {
    }

    /**
     * 找第一处问题。
     *
     * @param root 已过共同检查、不是字面量 {@code null} 的根
     * @return 第一处问题的路径；形状合 Schema 时为空
     */
    static Optional<String> findProblem(JsonNode root) {
        return root.isObject() ? checkObject(root, ROOT, JsonText.ROOT) : Optional.of(JsonText.ROOT);
    }

    private static Optional<String> checkObject(JsonNode object, ObjectShape shape, String path) {
        for (Map.Entry<String, JsonNode> property : object.properties()) {
            String memberPath = JsonText.memberPath(path, property.getKey());
            ValueShape member = shape.members().get(property.getKey());
            if (member == null) {
                return Optional.of(memberPath);
            }
            Optional<String> problem = checkValue(property.getValue(), member, memberPath);
            if (problem.isPresent()) {
                return problem;
            }
        }
        // 缺少的成员在对象的成员都查完之后才查，与反序列化在对象结束时才发现缺少成员一致
        for (String required : shape.required()) {
            if (!object.has(required)) {
                return Optional.of(JsonText.memberPath(path, required));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> checkValue(JsonNode value, ValueShape shape, String path) {
        if (value.isNull()) {
            return Optional.of(path);
        }
        switch (shape.kind()) {
            case STRING -> {
                return value.isTextual() && shape.accepts().test(value.textValue()) ? Optional.empty() : Optional.of(path);
            }
            case BOOLEAN -> {
                return value.isBoolean() ? Optional.empty() : Optional.of(path);
            }
            case OBJECT -> {
                return value.isObject() ? checkObject(value, shape.object(), path) : Optional.of(path);
            }
            default -> {
                if (!value.isArray() || (shape.nonEmpty() && value.isEmpty())) {
                    return Optional.of(path);
                }
                // 不得重复的字符串数组：先核对这一项本身，再看它是否与前面的项重复，重复时报第二次出现的位置
                Set<String> seen = shape.unique() ? new HashSet<>() : null;
                for (int index = 0; index < value.size(); index++) {
                    String itemPath = JsonText.indexPath(path, index);
                    Optional<String> problem = checkValue(value.get(index), shape.item(), itemPath);
                    if (problem.isPresent()) {
                        return problem;
                    }
                    if (seen != null && !seen.add(value.get(index).textValue())) {
                        return Optional.of(itemPath);
                    }
                }
                return Optional.empty();
            }
        }
    }

    private static ObjectShape root() {
        ValueShape any = text(text -> true);
        ValueShape flag = new ValueShape(Kind.BOOLEAN, null, null, null, false, false);
        ObjectShape capability = new ObjectShape(Map.of(
                "id", text(CapabilityId::isValid),
                "version", text(ManifestShape::isPositiveInt32)),
                List.of("id", "version"));
        ObjectShape requirement = new ObjectShape(Map.of(
                "id", text(CapabilityId::isValid),
                "version", text(text -> isPositiveInt32(text.startsWith("^") ? text.substring(1) : text)),
                "optional", flag),
                List.of("id", "version"));
        ObjectShape binding = new ObjectShape(Map.of(
                "name", text(Binding::isValidName),
                "type", text(Binding::isValidType),
                "required", flag),
                List.of("name", "type"));
        ObjectShape pipeline = new ObjectShape(Map.of(
                "middleware", text(text -> !text.isEmpty()),
                "stage", text(text -> PipelineStage.parse(text).isPresent()),
                "before", list(any),
                "after", list(any)),
                List.of("middleware", "stage"));
        ObjectShape config = new ObjectShape(Map.of(
                "section", text(ConfigContribution::isValidSection),
                "schema", any),
                List.of("section"));
        ObjectShape contributions = new ObjectShape(Map.of(
                "pipeline", list(object(pipeline)),
                "operations", list(text(Contributions::isValidOperation)),
                "config", object(config),
                "permissions", list(text(Contributions::isValidPermissionCode)).distinct(),
                "health", list(text(Contributions::isValidHealth))),
                List.of());
        return new ObjectShape(Map.ofEntries(
                Map.entry("$schema", any),
                Map.entry("id", text(ManifestId::isValid)),
                Map.entry("package", any),
                Map.entry("version", text(Manifest::isValidVersion)),
                Map.entry("primitives", text(ManifestShape::isPositiveInt32)),
                Map.entry("stability", text(Set.of("stable", "preview", "experimental", "internal", "deprecated")::contains)),
                Map.entry("kind", text(Set.of("capability", "provider", "plugin", "host", "protocol")::contains)),
                Map.entry("role", text(Set.of("provider", "default")::contains)),
                Map.entry("isolation", text(Set.of("in-process", "wasm", "out-of-process")::contains)),
                Map.entry("provides", list(object(capability))),
                Map.entry("requires", list(object(requirement))),
                Map.entry("bindings", list(object(binding))),
                Map.entry("contributes", object(contributions)),
                Map.entry("activation", list(text(Manifest::isValidActivation)).requireItems()),
                Map.entry("permissions", list(text(Manifest::isValidPermission))),
                Map.entry("entry", any)),
                List.of("id", "version", "primitives", "stability", "kind"));
    }

    /**
     * 接口版本：不带前导零、符号与空白的十进制正整数，在 32 位有符号整数范围内。
     */
    static boolean isPositiveInt32(String text) {
        return POSITIVE.matcher(text).matches() && InterfaceVersions.parse(text).isPresent();
    }

    private static ValueShape text(Predicate<String> accepts) {
        return new ValueShape(Kind.STRING, accepts, null, null, false, false);
    }

    private static ValueShape object(ObjectShape shape) {
        return new ValueShape(Kind.OBJECT, null, shape, null, false, false);
    }

    private static ValueShape list(ValueShape item) {
        return new ValueShape(Kind.ARRAY, null, null, item, false, false);
    }

    private enum Kind {
        STRING,
        BOOLEAN,
        OBJECT,
        ARRAY
    }

    /** 对象的形状：已知成员与必填成员（按 Schema 的顺序）。 */
    private record ObjectShape(Map<String, ValueShape> members, List<String> required) {
    }

    /** 值的形状。 */
    private record ValueShape(Kind kind, Predicate<String> accepts, ObjectShape object, ValueShape item, boolean nonEmpty, boolean unique) {
        ValueShape requireItems() {
            return new ValueShape(kind, accepts, object, item, true, unique);
        }

        ValueShape distinct() {
            return new ValueShape(kind, accepts, object, item, nonEmpty, true);
        }
    }
}
