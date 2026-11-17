/**
 * XiHan Framework Spec 的 Java 实现：原语、清单、调用上下文、管道、操作契约与进程外插件运行时。
 *
 * <ul>
 *   <li>{@code com.xihanfun.framework.primitives}：原语层（RFC-0001、RFC-0010），核心类型零依赖、不可变、线程安全；线上 JSON
 *       编码在 {@code com.xihanfun.framework.primitives.json}，以 Jackson 模块的形式提供。</li>
 *   <li>{@code com.xihanfun.framework.manifest}：{@code xihan.manifest.json} 的模型、读取、规范化输出与校验（RFC-0002）。</li>
 *   <li>{@code com.xihanfun.framework.invocation}：调用上下文（RFC-0003），取消与截止时间、W3C 追踪、已登记的 Baggage 与请求作用域对象袋。</li>
 *   <li>{@code com.xihanfun.framework.pipeline}：固定阶段的执行管道（RFC-0004），中间件按阶段贡献，排序确定、可打印、可测试。</li>
 *   <li>{@code com.xihanfun.framework.operation}：操作契约与 XGI 消息（RFC-0011），调用器让操作经过完整管道并在 handle 阶段执行。</li>
 *   <li>{@code com.xihanfun.framework.plugin}：进程外插件运行时（RFC-0017），协议 {@code xihan.plugin.v1} 经 grpc-java；
 *       它的传输是可选依赖，只有用到本包的应用需要。</li>
 * </ul>
 *
 * <p>请求与响应类型由 Jackson 反射编解码：放在模块路径上的应用要把这些类型所在的包 {@code opens} 给
 * {@code com.fasterxml.jackson.databind}。在模块路径上用插件运行时，gRPC 与 protobuf 的自动模块要进入模块图
 * （应用自己 {@code requires} 它们，或 {@code --add-modules}）。
 */
// grpc-java 与 protobuf-java 只以 Automatic-Module-Name 提供模块名，没有模块描述；只有它们是 requires static 的自动模块
@SuppressWarnings("requires-automatic")
module com.xihanfun.framework {
    exports com.xihanfun.framework.primitives;
    exports com.xihanfun.framework.primitives.json;
    exports com.xihanfun.framework.manifest;
    exports com.xihanfun.framework.invocation;
    exports com.xihanfun.framework.pipeline;
    exports com.xihanfun.framework.operation;
    exports com.xihanfun.framework.plugin;

    // 生成的协议代码不导出；protobuf 的描述符反射（toString、按字段名访问）要读它
    opens com.xihanfun.framework.plugin.v1 to com.google.protobuf;

    // ObjectMapper 出现在登记表的构造参数里，PrimitivesModule 继承 Jackson 的模块类型，所以传递给使用者
    requires transitive com.fasterxml.jackson.databind;

    // 插件运行时的传输是可选依赖：编译时读得到，运行时只有用到 com.xihanfun.framework.plugin 的应用需要把它们放上来；
    // com.google.common 是生成代码里 FutureStub 的返回类型（ListenableFuture）
    requires static io.grpc;
    requires static io.grpc.stub;
    requires static io.grpc.protobuf;
    requires static io.grpc.netty.shaded;
    requires static com.google.protobuf;
    requires static com.google.common;

    // 插件日志把 java.util.logging（gRPC 与 System.Logger 缺省的输出）改写成协议的 JSON 行
    requires java.logging;
}
