package com.xihanfun.framework.plugin;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;
import java.time.Duration;
import java.util.Optional;

/**
 * 插件作者接入生命周期的回调（{@code lifecycle.md} §7）；都有缺省实现，只覆写需要的。
 *
 * <p>返回的失败原样作为 {@code Status.error} 交给宿主；抛出的异常翻译为 {@code PLUGINS_UNEXPECTED_001} + {@value PluginMessages#FAILED}，
 * {@code reason} 是异常的简单类型名（如 {@code IllegalStateException}），不带异常的文本，异常本身只写进 stderr 日志（§7.6）；返回 {@code null}
 * 与抛出 {@code NullPointerException} 相同。{@code configure} 或 {@code start} 失败时插件进入 {@code Failed}。回调在虚拟线程上执行，可以阻塞。
 */
public interface PluginHooks {
    /** 什么都不做的回调。 */
    PluginHooks NONE = new PluginHooks() {
    };

    /**
     * 在 {@code Configure} 时调用：读配置节、权限、绑定与工作目录。配置节不是 JSON 对象时运行时直接以 {@code PLUGINS_UNEXPECTED_001} 失败，不调用本方法。
     *
     * @param context 带宿主截止时间（{@code StartTimeout}）的上下文
     * @param configuration 下发的配置
     * @return 结果；失败即插件 {@code Failed}
     */
    default Result<Unit> configure(Context context, PluginConfiguration configuration) {
        return Result.success();
    }

    /**
     * 在 {@code Start} 时调用。成功返回后插件进入 {@code Started}，运行时随即求一次 {@link #health()}，按结果进入 {@code Ready}、{@code Degraded}
     * 或 {@code Failed}（没有健康回调即 {@code Ready}），然后才应答宿主；只有 {@code Ready} 与 {@code Degraded} 接受调用与投递。
     *
     * @param context 带宿主截止时间的上下文
     * @return 结果；失败即插件 {@code Failed}
     */
    default Result<Unit> start(Context context) {
        return Result.success();
    }

    /**
     * 停止时调用，在进程里最多调用一次，且只在 {@link #start(Context)} 成功过之后（{@code lifecycle.md} §7.5、§7.7）：
     * 收到 {@code Stop} 时，在途调用已经结束，或等满宽限后已经触发了它们的取消信号；stdin 关闭或收到终止信号时，在途调用的取消信号已经触发，
     * 从收到起最多 3 秒，到时进程不论本方法是否返回都以退出码 0 结束。在这里释放资源。
     *
     * @param context 截止时间是剩余宽限的上下文
     * @param reason 原因，原样取自 {@code Stop}（宿主写 {@code shutdown}、{@code unload} 或 {@code restart}）；stdin 关闭与终止信号为 {@code shutdown}
     * @param grace 剩余的宽限
     * @return 结果；失败写进 {@code StopResponse.status}，进程照常退出
     */
    default Result<Unit> stop(Context context, String reason, Duration grace) {
        return Result.success();
    }

    /**
     * 给出当前健康（{@code lifecycle.md} §7.4）：{@code Start} 成功后求一次，之后 {@code Ready}、{@code Degraded} 期间每次 {@code Health}
     * 都求一次，{@code WatchHealth} 每 10 秒求一次；要快、不阻塞。报 {@code Ready}、{@code Degraded} 或 {@code Failed} 的按合法迁移改变插件状态
     * （{@code Failed} 之后不再回来），报其他状态的不改变状态（{@code Start} 之后的第一次按 {@code Ready}）；不在清单 {@code contributes.health}
     * 里的健康项丢掉。抛出时这一次的结果是 {@code Degraded}、没有健康项，错误为 {@code PLUGINS_UNEXPECTED_001}（{@code phase} 为 {@code health}），
     * 插件也与报了 {@code Degraded} 一样迁到 {@code Degraded}，之后的结果照常决定状态。
     *
     * @return 健康；为空表示没有健康回调，按运行时自己的状态上报，没有健康项
     */
    default Optional<PluginHealth> health() {
        return Optional.empty();
    }
}
