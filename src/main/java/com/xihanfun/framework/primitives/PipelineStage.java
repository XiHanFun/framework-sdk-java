package com.xihanfun.framework.primitives;

import com.xihanfun.framework.internal.WireNames;
import java.util.List;
import java.util.Optional;

/**
 * 管道的十一个阶段（RFC-0004）：声明顺序就是执行顺序，名称与顺序是规范性内容。
 */
public enum PipelineStage {
    /** 协议消息归一化、大小限制、构造上下文。 */
    INGRESS("ingress"),
    /** Trace、日志、指标。 */
    OBSERVE("observe"),
    /** 超时、限流、并发上限、熔断。 */
    RESILIENCE("resilience"),
    /** 主体解析。 */
    AUTHENTICATE("authenticate"),
    /** 租户解析与隔离上下文。 */
    TENANT("tenant"),
    /** 策略判定。 */
    AUTHORIZE("authorize"),
    /** 契约校验。 */
    VALIDATE("validate"),
    /** 幂等键去重。 */
    IDEMPOTENCY("idempotency"),
    /** 工作单元。 */
    TRANSACTION("transaction"),
    /** 调用操作。 */
    HANDLE("handle"),
    /** 结果与错误翻译、写回传播字段。 */
    EGRESS("egress");

    /** 按执行顺序的全部阶段。 */
    private static final List<PipelineStage> ALL = List.of(values());

    /** 清单与打印里的小写阶段名。 */
    private final String wireName;

    PipelineStage(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 按执行顺序列出全部阶段。
     *
     * @return 只读列表
     */
    public static List<PipelineStage> all() {
        return ALL;
    }

    /**
     * 按清单名解析阶段，区分大小写。
     *
     * @param name 清单里的阶段名，如 {@code observe}
     * @return 阶段；名字未知时为空
     */
    public static Optional<PipelineStage> parse(String name) {
        return WireNames.find(values(), PipelineStage::wireName, name);
    }

    /**
     * 返回清单与打印里的阶段名；与其他枚举的取名方法同名。
     *
     * @return 小写阶段名，如 {@code observe}
     */
    public String wireName() {
        return wireName;
    }

    /**
     * 返回清单里的阶段名。
     *
     * @return 小写阶段名
     * @deprecated 用 {@link #wireName()}：各枚举的取名方法统一叫这个名字，返回值相同。
     */
    @Deprecated
    public String manifestName() {
        return wireName;
    }
}
