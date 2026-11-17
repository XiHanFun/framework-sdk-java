package com.xihanfun.framework.plugin;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.time.Duration;
import java.util.Optional;

/**
 * 插件作者接入生命周期的回调（{@code lifecycle.md} §2、§4、§5）；都有缺省实现，只覆写需要的。
 *
 * <p>返回的失败原样作为 {@code Status.error} 交给宿主；抛出的异常翻译为 {@code PLUGINS_UNEXPECTED_001} +
 * {@value PluginMessages#FAILED}（框架异常的错误放进 {@code inner}），异常本身写进日志。{@code configure} 或 {@code start}
 * 失败时插件进入 {@code Failed}。回调在虚拟线程上执行，可以阻塞。
 */
public interface PluginHooks {
    /** 什么都不做的回调。 */
    PluginHooks NONE = new PluginHooks() {
    };

    /**
     * 在 {@code Configure} 时调用：读配置节、权限、绑定与工作目录。
     *
     * @param context 带宿主截止时间（{@code StartTimeout}）的上下文
     * @param configuration 下发的配置
     * @return 结果；失败即插件 {@code Failed}
     */
    default Result<Unit> configure(Context context, PluginConfiguration configuration) {
        return Result.success();
    }

    /**
     * 在 {@code Start} 时调用；成功返回后插件接受调用并报 {@code Ready}。
     *
     * @param context 带宿主截止时间的上下文
     * @return 结果；失败即插件 {@code Failed}
     */
    default Result<Unit> start(Context context) {
        return Result.success();
    }

    /**
     * 在 {@code Stop} 时调用，或在 stdin 关闭、收到终止信号时调用，只调用一次，且只在 {@code configure} 调用过之后：
     * 在途调用已经完成（或等满了宽限），在这里释放资源。
     *
     * @param context 截止时间是宽限期结束的上下文
     * @param reason 原因：{@code shutdown}、{@code unload} 或 {@code restart}；stdin 关闭与终止信号为 {@code shutdown}
     * @param grace 剩余的宽限
     * @return 结果；失败只记录，进程照常退出
     */
    default Result<Unit> stop(Context context, String reason, Duration grace) {
        return Result.success();
    }

    /**
     * 给出当前健康；{@code Health} 调用与 {@code WatchHealth} 推送（每秒核对一次是否变化）都用它，要快、不阻塞。
     *
     * @return 健康；为空时按运行时自己的状态上报，没有健康项
     */
    default Optional<PluginHealth> health() {
        return Optional.empty();
    }
}
