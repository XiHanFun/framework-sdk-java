/**
 * 调用上下文（RFC-0003）：取消与截止时间、W3C 追踪、已登记的 Baggage 与请求作用域对象袋。
 */
module com.xihanfun.framework.invocation {
    exports com.xihanfun.framework.invocation;

    requires transitive com.xihanfun.framework.primitives;
}
