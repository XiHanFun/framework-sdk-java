package com.xihanfun.framework.plugin;

import com.xihanfun.framework.invocation.Context;
import com.xihanfun.framework.primitives.Result;
import com.xihanfun.framework.primitives.Unit;

/**
 * 事件处理器：处理宿主投递的一条消息（{@code invocation.md} §4）。
 *
 * <p>投递至少一次，处理器必须幂等（可用 Baggage 里的幂等键或信封 {@code id}）；返回失败由宿主的消息代理按重试与死信策略处理。
 * 抛出的异常翻译为 {@code KERNEL_UNEXPECTED_001}。
 */
@FunctionalInterface
public interface EventHandler {
    /**
     * 处理一条消息。
     *
     * @param context 调用上下文：追踪与 Baggage 来自投递的元数据，宿主取消投递时触发取消
     * @param envelope 信封
     * @param attempt 投递次数，从 1 起
     * @return 成功表示已处理
     */
    Result<Unit> handle(Context context, Envelope envelope, int attempt);
}
