package com.xihanfun.framework.operation;

import com.xihanfun.framework.internal.Grammars;
import java.util.Objects;

/**
 * 操作 ID 的文法：{@code <resource>.<verb>}（{@code spec/conventions/operation-ids.md}）：资源是小写复数名词（连字符分隔），动词小写。
 *
 * <p>文法只有一份，在模块内部与清单的 {@code contributes.operations} 共用。
 */
public final class OperationId {
    private OperationId() {
    }

    /**
     * 判断操作 ID 是否合法。
     *
     * @param id 待判断的文本；{@code null} 视为不合法
     * @return 合法时为真
     */
    public static boolean isValid(String id) {
        return id != null && Grammars.isOperationId(id);
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
