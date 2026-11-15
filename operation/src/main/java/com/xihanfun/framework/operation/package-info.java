/**
 * 操作契约与 XGI 消息（RFC-0011）：契约、Schema、处理器、登记表与经完整管道执行的调用器。
 *
 * <p>XGI 的规范编码是 JSON：{@link com.xihanfun.framework.operation.OperationInvoker#invoke(com.xihanfun.framework.invocation.Context,
 * OperationRequest)} 解码请求、经管道在 handle 阶段执行、编码响应；类型化入口不经 JSON。处理器、登记表与调用器都按并发执行设计：
 * 同一个调用器可以被任意多个虚拟线程同时使用，每次调用的操作状态随派生的上下文传递，嵌套调用互不干扰。
 */
package com.xihanfun.framework.operation;
