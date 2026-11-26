package com.xihanfun.framework.primitives;

import com.xihanfun.framework.internal.UnicodeText;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 已登记的 Baggage 键与键格式（{@code spec/conventions/baggage-keys.md}）。
 *
 * <p>键格式是 {@code xihan.<name>}，小写，连字符分隔；跨进程只传已登记的键（RFC-0003 规则 3）。
 */
public final class BaggageKeys {
    /** 当前租户 ID。 */
    public static final String TENANT = "xihan.tenant";

    /** 当前主体 ID。 */
    public static final String PRINCIPAL = "xihan.principal";

    /** 业务关联 ID。 */
    public static final String CORRELATION = "xihan.correlation";

    /** 幂等键。 */
    public static final String IDEMPOTENCY_KEY = "xihan.idempotency-key";

    /** 请求文化（BCP 47）。 */
    public static final String LOCALE = "xihan.locale";

    /** 已登记键的值的最大长度（UTF-16 码元），与 .NET 的 {@code BaggageKeys.MaxValueLength} 一致（ADR-0204）。 */
    public static final int MAX_VALUE_LENGTH = 256;

    /** 已登记的键，按登记顺序。 */
    private static final List<String> REGISTERED = List.of(TENANT, PRINCIPAL, CORRELATION, IDEMPOTENCY_KEY, LOCALE);

    /** 键格式 {@code xihan.<name>}。 */
    private static final Pattern PATTERN = Pattern.compile("^xihan\\.[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private BaggageKeys() {
    }

    /**
     * 返回已登记的键，按登记顺序。
     *
     * @return 只读列表
     */
    public static List<String> registered() {
        return REGISTERED;
    }

    /**
     * 判断键是否符合 {@code xihan.<name>}。
     *
     * @param key 待判断的文本；{@code null} 视为不合法
     * @return 格式合法时为真
     */
    public static boolean isWellFormed(String key) {
        return key != null && PATTERN.matcher(key).matches();
    }

    /**
     * 判断键是否已登记。
     *
     * @param key 待判断的文本；{@code null} 视为未登记
     * @return 已登记时为真
     */
    public static boolean isRegistered(String key) {
        return key != null && REGISTERED.contains(key);
    }

    /**
     * 判断已登记键的值是否可以接受：非空、不超过 {@value #MAX_VALUE_LENGTH} 个字符、不含控制字符（ADR-0204）与孤立的代理项（ADR-0321）。
     *
     * <p>这些值会写进日志、审计与消息信封，换行能伪造日志行；载体在建调用上下文时丢弃不合的成员（{@code xihan.idempotency-key}
     * 例外，由 idempotency 阶段以校验错误拒绝）。孤立的代理项按 RFC-0001「孤立的代理项」判定：值出进程时编成 UTF-8，宽松的编码把它换成
     * U+FFFD，只差在孤立代理项上的两个值到了下游是同一个；成对的代理项是平面以外的一个字符，照常合法。
     *
     * @param value 待判断的值；{@code null} 视为不可接受
     * @return 可以接受时为真
     */
    public static boolean isAcceptableValue(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            // C0、DEL 与 C1 控制字符，与 .NET 的 char.IsControl 同一范围
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return !UnicodeText.hasLoneSurrogate(value);
    }
}
