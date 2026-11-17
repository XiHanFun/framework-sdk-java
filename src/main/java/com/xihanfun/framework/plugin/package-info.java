/**
 * 进程外插件运行时（RFC-0017，{@code spec/plugin/}）：用本 SDK 写在宿主进程之外运行的插件。
 *
 * <p>{@link com.xihanfun.framework.plugin.PluginRuntime#serve(com.xihanfun.framework.plugin.PluginOptions)} 以宿主启动的子进程身份运行：
 * 读宿主下发的环境变量、监听本机端点、写握手行，提供 {@code xihan.plugin.v1} 的 {@code Plugin} 服务，stdin 关闭即退出。插件的操作就是
 * {@link com.xihanfun.framework.operation.OperationRegistry} 里登记的操作，宿主发来的调用经调用器与完整管道执行；
 * {@link com.xihanfun.framework.plugin.Host} 回调宿主的 {@code Invoke} 与 {@code Publish}。
 *
 * <p>传输是 grpc-java（{@code grpc-netty-shaded}、{@code grpc-protobuf}、{@code grpc-stub}）与 {@code protobuf-java}，它们是本构件的可选依赖：
 * 用本包的应用自己加进依赖，其余包不引用它们。公开类型不出现 gRPC 与 protobuf 的类型；协议代码在不导出的
 * {@code com.xihanfun.framework.plugin.v1}，由 {@code spec/plugin/plugin.proto} 生成。
 */
package com.xihanfun.framework.plugin;
