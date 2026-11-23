package com.xihanfun.framework.internal;

import java.util.regex.Pattern;

/**
 * 不止一个包要核对的规范文法：各留一处，免得两份正则各改各的。
 *
 * <p>内部实现，不属于公开 API：所在的包不导出（{@code module-info.java}）。操作 ID 的文法由 {@code operation} 的 {@code OperationId}
 * 与 {@code manifest} 的 {@code contributes.operations} 共用，而这两个包互不依赖，所以放在这里。
 */
public final class Grammars {
    /** 操作 ID 文法（RFC-0011、{@code conventions/operation-ids.md}）：资源与动词都是小写字母开头、连字符分隔。 */
    private static final Pattern OPERATION_ID = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private Grammars() {
    }

    /**
     * 判断文本是否合操作 ID 文法 {@code <resource>.<verb>}；整串匹配。
     *
     * @param text 待判断的文本
     * @return 合文法时为真
     */
    public static boolean isOperationId(String text) {
        return OPERATION_ID.matcher(text).matches();
    }
}
