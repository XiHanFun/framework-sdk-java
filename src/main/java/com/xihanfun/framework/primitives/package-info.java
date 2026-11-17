/**
 * 原语层（RFC-0001）：错误与错误码、{@link com.xihanfun.framework.primitives.Result}、
 * {@link com.xihanfun.framework.primitives.Maybe}、类型化标识、生命周期状态、管道阶段、稳定性、Baggage 键与消息原语。
 *
 * <p>本包零行为、零依赖、只增不改；全部类型不可变、可在线程之间共享。语义与线上编码和 .NET 参考实现及 Go SDK 一致，
 * 由 framework-spec 的一致性向量验证。
 *
 * <p>可空性约定：本包的公开成员不接受也不返回 {@code null}；可能没有的值以 {@link java.util.Optional} 返回，
 * 数据字段与线上编码用 {@link com.xihanfun.framework.primitives.Maybe}。参数传入 {@code null} 抛出
 * {@link java.lang.NullPointerException}，格式不合法抛出 {@link java.lang.IllegalArgumentException}。
 */
package com.xihanfun.framework.primitives;
