package com.xihanfun.framework.plugin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.xihanfun.framework.primitives.json.PrimitivesJson;
import com.xihanfun.framework.primitives.json.PrimitivesModule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code Configure} 下发的内容（{@code lifecycle.md} §2、§7.2）。
 *
 * <p>配置节是宿主配置源展开后再序列化的 JSON 对象，宿主没有声明配置节时为 {@code {}}；运行时在调配置回调之前核对它是 JSON 对象，
 * 原样交给作者。宿主写出的叶子一律是 JSON 字符串，所以 {@link #decode(Class)} 允许把字符串读成数字（按 JSON 数字的文法）与布尔
 * （只认 {@code true}、{@code false}，ASCII 不区分大小写）；成员名按 ASCII 不区分大小写对上属性名，未知成员忽略。
 */
public final class PluginConfiguration {
    /**
     * 解码配置节：字符串叶子按 {@link ConfigurationScalars} 读成数字与布尔；成员名在交给 Jackson 之前由 {@link ConfigurationNames} 按 ASCII
     * 不区分大小写改写成属性名，Jackson 自己按原样匹配（它的 {@code ACCEPT_CASE_INSENSITIVE_PROPERTIES} 按 Unicode 换小写，比规范宽）。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new PrimitivesModule())
            .addModule(new ConfigurationScalars())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final String section;
    private final List<String> grantedPermissions;
    private final List<ResolvedBinding> bindings;
    private final Path workDirectory;

    /**
     * 创建配置。
     *
     * @param section 配置节的 JSON 对象文本；空串按 {@code {}}
     * @param grantedPermissions 宿主授予的权限，等于清单 {@code permissions}；回调前的权限核对以它为准
     * @param bindings 已解析的资源绑定，按宿主下发的先后，每个连同它的属性
     * @param workDirectory 宿主分配的可写工作目录；没有时为空
     */
    public PluginConfiguration(String section, List<String> grantedPermissions, List<ResolvedBinding> bindings,
            Optional<Path> workDirectory) {
        String text = Objects.requireNonNull(section, "section");
        this.section = text.isEmpty() ? "{}" : text;
        this.grantedPermissions = List.copyOf(Objects.requireNonNull(grantedPermissions, "grantedPermissions"));
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory").orElse(null);
    }

    /**
     * 返回配置节的 JSON 文本。
     *
     * @return JSON 对象文本
     */
    public String section() {
        return section;
    }

    /**
     * 把配置节解码成给定类型：先过共同的 JSON 文本检查，再按目标类型把成员名改写成属性名（ASCII 不区分大小写），然后交给 Jackson。
     *
     * @param type 目标类型，通常是记录
     * @param <T> 目标类型
     * @return 解码结果
     * @throws IOException 配置节不是合法的 JSON 文本，或不合目标类型
     */
    public <T> T decode(Class<T> type) throws IOException {
        JavaType target = MAPPER.constructType(Objects.requireNonNull(type, "type"));
        // 共同检查在原文上做：改写后同一属性的两种写法会成为重复成员，那是 IConfiguration 也会合并的情形，后写的生效
        PrimitivesJson.read(MAPPER, section, JsonNode.class);
        return MAPPER.readValue(ConfigurationNames.align(MAPPER, section, target), target);
    }

    /**
     * 返回宿主授予的权限。
     *
     * @return 只读列表
     */
    public List<String> grantedPermissions() {
        return grantedPermissions;
    }

    /**
     * 返回已解析的资源绑定：按宿主下发的先后，每个连同它的属性（{@link ResolvedBinding#properties()}）。
     *
     * @return 只读列表
     */
    public List<ResolvedBinding> bindings() {
        return bindings;
    }

    /**
     * 按名字找资源绑定。
     *
     * @param name 绑定名
     * @return 绑定；没有时为空
     */
    public Optional<ResolvedBinding> findBinding(String name) {
        Objects.requireNonNull(name, "name");
        return bindings.stream().filter(binding -> binding.name().equals(name)).findFirst();
    }

    /**
     * 返回宿主分配的可写工作目录（宿主停止时不删除）。
     *
     * @return 目录；宿主没有给出时为空
     */
    public Optional<Path> workDirectory() {
        return Optional.ofNullable(workDirectory);
    }

    /**
     * 返回不含配置值、连接串与绑定属性值的文本：配置节与绑定里常有密钥，不进日志；绑定只带名字、类型与属性名。
     *
     * @return 文本
     */
    @Override
    public String toString() {
        return "PluginConfiguration[permissions=" + grantedPermissions + ", bindings=" + bindings
                + ", workDirectory=" + workDirectory + "]";
    }
}
