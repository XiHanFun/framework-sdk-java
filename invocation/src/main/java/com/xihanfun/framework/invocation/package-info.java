/**
 * 调用上下文（RFC-0003）：取消、截止时间、W3C 追踪、已登记的 Baggage、请求作用域对象袋与按派生传递的值。
 *
 * <p>不绑定任何协议，载体映射由协议适配器与插件运行时实现。{@link com.xihanfun.framework.invocation.Context} 不可变，
 * 派生方法返回新实例，可以在线程之间传递、被多个虚拟线程并发读取；取消状态可以被检查、可以注册回调，
 * 超时与取消翻译为 {@code KERNEL_TIMEOUT_001} / {@code KERNEL_CANCELLED_001}。
 */
package com.xihanfun.framework.invocation;
