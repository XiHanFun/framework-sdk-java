package com.xihanfun.framework.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.FrameworkException;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/pipeline/ordering.json}（RFC-0004「排序规则」「打印格式」，ADR-0235）：
 * 期望顺序与打印全文逐字相同，每种错误在规定的那一步报出，消息原文逐字相同（ADR-0236）。
 */
class OrderingVectorsTest {
    /** 向量里按名字登记的实例都用这个类型；打印文本里的 {@code {type}} 换成它的类型名。 */
    static final class VectorMiddleware implements Middleware {
        @Override
        public Result<Unit> invoke(Context context, Step next) {
            return next.invoke(context);
        }
    }

    private static final String TYPE = VectorMiddleware.class.getName();

    @Test
    void casesFollowTheVectors() throws IOException {
        for (JsonNode vector : Vectors.read("pipeline/ordering.json").get("cases")) {
            PipelineBuilder builder = new PipelineBuilder();
            for (JsonNode step : vector.get("steps")) {
                apply(builder, step);
            }
            assertEquals(vector.get("dump").textValue().replace("{type}", TYPE), builder.dump(), vector.get("name").textValue());
        }
    }

    @Test
    void errorsAreReportedWhenTheVectorsSay() throws IOException {
        for (JsonNode vector : Vectors.read("pipeline/ordering.json").get("errors")) {
            String name = vector.get("name").textValue();
            String code = vector.get("error").textValue();
            String message = vector.get("message").textValue().replace("{type}", TYPE);
            JsonNode at = vector.get("at");
            PipelineBuilder builder = new PipelineBuilder();
            JsonNode steps = vector.get("steps");
            for (int index = 0; index < steps.size(); index++) {
                JsonNode step = steps.get(index);
                if (at.isInt() && at.intValue() == index) {
                    FrameworkException thrown = assertThrows(FrameworkException.class, () -> apply(builder, step), name + ": step " + index);
                    assertEquals(code, thrown.code(), name);
                    assertEquals(message, thrown.getMessage(), name);
                    break;
                }
                apply(builder, step);
            }
            if (at.isTextual()) {
                assertEquals("sort", at.textValue());
                for (var sort : new org.junit.jupiter.api.function.Executable[] {builder::describe, builder::dump,
                        () -> builder.build(context -> Result.success())}) {
                    FrameworkException thrown = assertThrows(FrameworkException.class, sort, name + " when sorting");
                    assertEquals(code, thrown.code(), name);
                    assertEquals(message, thrown.getMessage(), name);
                }
            }
        }
    }

    /**
     * 执行一步：use 按名字登记一个实例，before / after 是 [主体, 目标] 的按名字约束；阶段名经 {@link PipelineBuilder#parseStage(String)} 解析，
     * 未知阶段在这一步报 {@code KERNEL_PIPELINE_002}。
     */
    private static void apply(PipelineBuilder builder, JsonNode step) {
        if (step.has("use")) {
            Placement placement = "beginning".equals(step.get("placement").textValue()) ? Placement.BEGINNING : Placement.END;
            builder.useNamedInstance(step.get("use").textValue(), new VectorMiddleware(), PipelineBuilder.parseStage(step.get("stage").textValue()),
                    placement, step.get("owner").textValue());
        } else if (step.has("before")) {
            builder.before(step.get("before").get(0).textValue(), step.get("before").get(1).textValue());
        } else {
            builder.after(step.get("after").get(0).textValue(), step.get("after").get(1).textValue());
        }
    }
}
