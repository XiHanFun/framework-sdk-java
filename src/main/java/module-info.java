/**
 * XiHan Framework Spec 的 Java 实现：原语、清单、调用上下文、管道与操作契约。
 *
 * <ul>
 *   <li>{@code com.xihanfun.framework.primitives}：原语层（RFC-0001、RFC-0010），核心类型零依赖、不可变、线程安全；线上 JSON
 *       编码在 {@code com.xihanfun.framework.primitives.json}，以 Jackson 模块的形式提供。</li>
 *   <li>{@code com.xihanfun.framework.manifest}：{@code xihan.manifest.json} 的模型、读取、规范化输出与校验（RFC-0002）。</li>
 *   <li>{@code com.xihanfun.framework.invocation}：调用上下文（RFC-0003），取消与截止时间、W3C 追踪、已登记的 Baggage 与请求作用域对象袋。</li>
 *   <li>{@code com.xihanfun.framework.pipeline}：固定阶段的执行管道（RFC-0004），中间件按阶段贡献，排序确定、可打印、可测试。</li>
 *   <li>{@code com.xihanfun.framework.operation}：操作契约与 XGI 消息（RFC-0011），调用器让操作经过完整管道并在 handle 阶段执行。</li>
 * </ul>
 *
 * <p>请求与响应类型由 Jackson 反射编解码：放在模块路径上的应用要把这些类型所在的包 {@code opens} 给
 * {@code com.fasterxml.jackson.databind}。
 */
module com.xihanfun.framework {
    exports com.xihanfun.framework.primitives;
    exports com.xihanfun.framework.primitives.json;
    exports com.xihanfun.framework.manifest;
    exports com.xihanfun.framework.invocation;
    exports com.xihanfun.framework.pipeline;
    exports com.xihanfun.framework.operation;

    // ObjectMapper 出现在登记表的构造参数里，PrimitivesModule 继承 Jackson 的模块类型，所以传递给使用者
    requires transitive com.fasterxml.jackson.databind;
}
