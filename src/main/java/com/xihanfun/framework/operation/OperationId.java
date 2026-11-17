package com.xihanfun.framework.operation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 操作 ID 的文法：{@code <resource>.<verb>}（{@code spec/conventions/operation-ids.md}）。
 */
public final class OperationId {
    /** 资源是小写复数名词（连字符分隔），动词小写。 */
    private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private OperationId() {
    }

    /**
     * 判断操作 ID 是否合法。
     *
     * @param id 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }

    /**
     * 校验操作 ID 并原样返回。
     *
     * @param id 操作 ID
     * @return 原样返回 {@code id}
     * @throws IllegalArgumentException 不合文法
     */
    public static String validate(String id) {
        Objects.requireNonNull(id, "id");
        if (!isValid(id)) {
            throw new IllegalArgumentException("The operation ID '" + id + "' must match <resource>.<verb>.");
        }
        return id;
    }
}
