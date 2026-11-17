package com.xihanfun.framework.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取 {@code spec/conformance/vectors} 下的一致性向量：路径相对仓库根，不复制向量文件。
 */
final class Vectors {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Vectors() {
    }

    /**
     * 读取一份向量文件。
     */
    static JsonNode read(String relative) throws IOException {
        Path file = root().resolve("spec/conformance/vectors").resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("The conformance vector '" + file + "' is missing; run 'git submodule update --init'.");
        }
        return MAPPER.readTree(file.toFile());
    }

    /**
     * 把字符串数组节点转成列表。
     */
    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.textValue()));
        return values;
    }

    /**
     * 仓库根：Maven 经系统属性传入；在 IDE 里直接运行时从工作目录向上找含 spec/ 的目录。
     */
    private static Path root() {
        String configured = System.getProperty("xihan.repository.root");
        if (configured != null) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("spec/conformance"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("The repository root with 'spec/' could not be found.");
        }
        return current;
    }
}
