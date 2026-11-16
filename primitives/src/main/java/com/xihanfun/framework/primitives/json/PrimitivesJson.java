package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xihanfun.framework.primitives.JsonText;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import java.util.Optional;

/**
 * 从 JSON 文本读出原语的入口：先过 RFC-0001「JSON 文本」的共同检查（{@link JsonText#check(byte[])}，ADR-0236），再交给 Jackson。
 *
 * <p>{@link PrimitivesModule} 的解码器在词法单元上核对重复成员、嵌套层数与孤立的代理项，但 BOM、非法 UTF-8、注释与尾逗号这类文本层面的问题，
 * Jackson 的解析器或者放过、或者随使用者的配置而定，解码器看不见；从线上收到的文本应经这里读。
 */
// 参数与返回值来自可选依赖 Jackson：下游自己读 Jackson
@SuppressWarnings("exports")
public final class PrimitivesJson {
    private PrimitivesJson() {
    }

    /**
     * 文本没有通过共同检查。
     */
    public static final class InvalidJsonTextException extends JsonProcessingException {
        @Serial
        private static final long serialVersionUID = 1L;

        /** 检查结果；异常不跨进程序列化，所以不参与序列化。 */
        private final transient JsonText.Problem problem;

        InvalidJsonTextException(JsonText.Problem problem) {
            super(problem.syntax() ? "The text is not JSON." : "The member '" + problem.path() + "' appears more than once.");
            this.problem = problem;
        }

        /**
         * 返回检查结果：语法错误或第一处重复成员的路径。
         *
         * @return 检查结果
         */
        public JsonText.Problem problem() {
            return problem;
        }
    }

    /**
     * 检查并读出 UTF-8 文本。
     *
     * @param mapper 登记了 {@link PrimitivesModule} 的映射器
     * @param utf8 原样的字节
     * @param type 目标类型，如 {@code Result<Order>}
     * @param <T> 目标类型
     * @return 读出的值
     * @throws InvalidJsonTextException 没有通过共同检查
     * @throws IOException 不合目标类型的线上编码
     */
    public static <T> T read(ObjectMapper mapper, byte[] utf8, JavaType type) throws IOException {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(type, "type");
        check(utf8);
        return mapper.readValue(utf8, type);
    }

    /**
     * 检查并读出 UTF-8 文本。
     *
     * @param mapper 登记了 {@link PrimitivesModule} 的映射器
     * @param utf8 原样的字节
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 读出的值
     * @throws InvalidJsonTextException 没有通过共同检查
     * @throws IOException 不合目标类型的线上编码
     */
    public static <T> T read(ObjectMapper mapper, byte[] utf8, Class<T> type) throws IOException {
        return read(mapper, utf8, Objects.requireNonNull(mapper, "mapper").constructType(type));
    }

    /**
     * 检查并读出 UTF-8 文本。
     *
     * @param mapper 登记了 {@link PrimitivesModule} 的映射器
     * @param utf8 原样的字节
     * @param type 目标类型，如 {@code new TypeReference<Result<Order>>() {}}
     * @param <T> 目标类型
     * @return 读出的值
     * @throws InvalidJsonTextException 没有通过共同检查
     * @throws IOException 不合目标类型的线上编码
     */
    public static <T> T read(ObjectMapper mapper, byte[] utf8, TypeReference<T> type) throws IOException {
        return read(mapper, utf8, Objects.requireNonNull(mapper, "mapper").getTypeFactory().constructType(type));
    }

    /**
     * 检查并读出文本：按 UTF-8 编码后检查，有孤立的代理项（编不成 UTF-8）时是语法错误。
     *
     * @param mapper 登记了 {@link PrimitivesModule} 的映射器
     * @param text 文本
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 读出的值
     * @throws InvalidJsonTextException 没有通过共同检查
     * @throws IOException 不合目标类型的线上编码
     */
    public static <T> T read(ObjectMapper mapper, String text, JavaType type) throws IOException {
        Optional<byte[]> utf8 = JsonText.encode(Objects.requireNonNull(text, "text"));
        if (utf8.isEmpty()) {
            throw new InvalidJsonTextException(new JsonText.Problem(true, JsonText.ROOT));
        }
        return read(mapper, utf8.get(), type);
    }

    /**
     * 检查并读出文本。
     *
     * @param mapper 登记了 {@link PrimitivesModule} 的映射器
     * @param text 文本
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 读出的值
     * @throws InvalidJsonTextException 没有通过共同检查
     * @throws IOException 不合目标类型的线上编码
     */
    public static <T> T read(ObjectMapper mapper, String text, Class<T> type) throws IOException {
        return read(mapper, text, Objects.requireNonNull(mapper, "mapper").constructType(type));
    }

    private static void check(byte[] utf8) throws InvalidJsonTextException {
        Optional<JsonText.Problem> problem = JsonText.check(Objects.requireNonNull(utf8, "utf8"));
        if (problem.isPresent()) {
            throw new InvalidJsonTextException(problem.get());
        }
    }
}
