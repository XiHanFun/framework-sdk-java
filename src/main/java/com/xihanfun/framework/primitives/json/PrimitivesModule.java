package com.xihanfun.framework.primitives.json;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xihanfun.framework.primitives.Error;
import com.xihanfun.framework.primitives.LifecycleState;
import com.xihanfun.framework.primitives.Maybe;
import com.xihanfun.framework.primitives.PipelineStage;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Stability;
import com.xihanfun.framework.primitives.TypedId;
import com.xihanfun.framework.primitives.Unit;
import java.io.Serial;

/**
 * 原语线上编码的 Jackson 模块。
 *
 * <p>编码：
 * <ul>
 *   <li>{@link Result}：{@code {"ok":true,"value":…}} 或 {@code {"ok":false,"error":{…}}}；无值成功 {@code Result<Unit>} 只写
 *       {@code {"ok":true}}，读到带 {@code value} 的即拒绝；{@code Result<Maybe<T>>} 的无值是 {@code "value":null}</li>
 *   <li>{@link Error}：{@code {"code":…,"message":…,"messageKey":…,"details":{…},"inner":{…}}}，没有的成员不写，也不许写成 {@code null}；
 *       {@code message} 必填，是发送端已解析的文案，{@code messageKey} 让接收端可再翻译</li>
 *   <li>{@link Maybe}：有值为值本身，无值为 {@code null}；对象里缺这个字段也解码为无值</li>
 *   <li>{@link TypedId}：底层值的编码</li>
 *   <li>{@link Stability}、{@link PipelineStage}：清单里的小写名；{@link LifecycleState}：状态名</li>
 * </ul>
 *
 * <p>解码按 RFC-0001「Result」的规则（ADR-0233，向量 {@code conformance/vectors/primitives/wire-encoding.json}）：
 * 不合规则的以 Jackson 的 {@code MismatchedInputException} 拒绝，不静默补默认值；Result 与 Error 的未知成员忽略。
 *
 * <p>本类型继承 Jackson 的模块类型，模块描述以 {@code requires transitive com.fasterxml.jackson.databind} 把 Jackson 交给使用者。
 */
public final class PrimitivesModule extends SimpleModule {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 创建模块并登记全部编解码器。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PrimitivesModule() {
        super("xihan-framework-primitives");
        addSerializer(Error.class, new ErrorCodec.Serializer());
        addDeserializer(Error.class, new ErrorCodec.Deserializer());
        // 泛型类型以原始类登记，解码器在 createContextual 里取实际的类型参数
        addSerializer((Class) Result.class, new ResultCodec.Serializer());
        addDeserializer((Class) Result.class, new ResultCodec.Deserializer(null));
        addSerializer((Class) Maybe.class, new MaybeCodec.Serializer());
        addDeserializer((Class) Maybe.class, new MaybeCodec.Deserializer(null));
        addSerializer((Class) TypedId.class, new TypedIdCodec.Serializer());
        addDeserializer((Class) TypedId.class, new TypedIdCodec.Deserializer(null));
        addSerializer(Unit.class, new UnitCodec.Serializer());
        addDeserializer(Unit.class, new UnitCodec.Deserializer());
        addSerializer(Stability.class, new NameCodec.Serializer<>(Stability.class, Stability::wireName));
        addDeserializer(Stability.class, new NameCodec.Deserializer<>(Stability.class, "stability", Stability::parse));
        addSerializer(PipelineStage.class, new NameCodec.Serializer<>(PipelineStage.class, PipelineStage::wireName));
        addDeserializer(PipelineStage.class, new NameCodec.Deserializer<>(PipelineStage.class, "pipeline stage", PipelineStage::parse));
        addSerializer(LifecycleState.class, new NameCodec.Serializer<>(LifecycleState.class, LifecycleState::wireName));
        addDeserializer(LifecycleState.class, new NameCodec.Deserializer<>(LifecycleState.class, "lifecycle state", LifecycleState::parse));
    }
}
