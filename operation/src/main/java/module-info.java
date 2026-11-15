/**
 * 操作契约与 XGI 消息（RFC-0011）：业务以操作为单位声明，调用器让它经过完整管道并在 handle 阶段执行。
 *
 * <p>请求与响应类型由 Jackson 反射编解码：放在模块路径上的应用要把这些类型所在的包 {@code opens} 给
 * {@code com.fasterxml.jackson.databind}。
 */
module com.xihanfun.framework.operation {
    exports com.xihanfun.framework.operation;

    requires transitive com.xihanfun.framework.pipeline;
    // ObjectMapper 出现在登记表的构造参数里
    requires transitive com.fasterxml.jackson.databind;
}
