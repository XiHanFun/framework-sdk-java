package com.xihanfun.framework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试辅助：读取 {@code spec/conformance/vectors} 下的一致性向量，路径相对仓库根，不复制向量文件；各测试包共用这一份。
 */
public final class Vectors {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Vectors() {
    }

    /**
     * 读取一份向量文件。
     *
     * @param relative 相对 {@code spec/conformance/vectors} 的路径，如 {@code primitives/error-codes.json}
     * @return 向量文件的 JSON
     * @throws IOException 文件读不出来
     */
    public static JsonNode read(String relative) throws IOException {
        Path file = root().resolve("spec/conformance/vectors").resolve(relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("The conformance vector '" + file + "' is missing; run 'git submodule update --init'.");
        }
        return MAPPER.readTree(file.toFile());
    }

    /**
     * 把字符串数组节点转成列表。
     *
     * @param array 字符串数组节点
     * @return 各项的文本
     */
    public static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.textValue()));
        return values;
    }

    /**
     * 按 UTF-16 码元组字符串（RFC-0012「一致性」的写法）：JSON 字符串写不出孤立的代理项，这类样例以四位十六进制的码元列出。
     *
     * @param units 码元数组节点，如 {@code ["0061", "D800"]}
     * @return 由这些码元组成的字符串
     */
    public static String utf16(JsonNode units) {
        StringBuilder text = new StringBuilder();
        units.forEach(unit -> text.append((char) Integer.parseInt(unit.textValue(), 16)));
        return text.toString();
    }

    /**
     * 仓库根：Maven 经系统属性 {@code xihan.repository.root} 传入；在 IDE 里直接运行时从工作目录向上找含 spec/ 的目录。
     *
     * @return 仓库根的绝对路径
     */
    public static Path root() {
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
