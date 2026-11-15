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
 *   <li>{@link Result}：{@code {"ok":true,"value":…}} 或 {@code {"ok":false,"error":{…}}}；{@code Result<Unit>} 的值是 {@code {}}</li>
 *   <li>{@link Error}：{@code {"code":…,"message":…,"messageKey":…,"details":{…},"inner":{…}}}，没有的字段省略；
 *       {@code message} 是发送端已解析的文案，{@code messageKey} 让接收端可再翻译</li>
 *   <li>{@link Maybe}：有值为值本身，无值为 {@code null}；对象里缺这个字段也解码为无值</li>
 *   <li>{@link TypedId}：底层值的编码</li>
 *   <li>{@link Stability}、{@link PipelineStage}：清单里的小写名；{@link LifecycleState}：状态名</li>
 * </ul>
 *
 * <p>解码是严格的：错误码不合法、失败结果没有错误、成功结果没有值（{@code Result<Unit>} 除外）都以 Jackson 的
 * {@code MismatchedInputException} 拒绝，不静默补默认值。
 *
 * <p>Jackson 是本模块的可选依赖，不能以 {@code requires transitive} 强加给下游；用编码的模块自己 {@code requires com.fasterxml.jackson.databind}。
 */
// 基类来自可选依赖：下游自己读 Jackson，exports 检查的「未经 transitive 导出」在这里是有意的
@SuppressWarnings("exports")
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
        addSerializer(PipelineStage.class, new NameCodec.Serializer<>(PipelineStage.class, PipelineStage::manifestName));
        addDeserializer(PipelineStage.class, new NameCodec.Deserializer<>(PipelineStage.class, "pipeline stage", PipelineStage::parse));
        addSerializer(LifecycleState.class, new NameCodec.Serializer<>(LifecycleState.class, LifecycleState::stateName));
        addDeserializer(LifecycleState.class, new NameCodec.Deserializer<>(LifecycleState.class, "lifecycle state", LifecycleState::parse));
    }
}
