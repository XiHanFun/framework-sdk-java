/**
 * XiHan Framework 原语层（RFC-0001）的 Java 形态。
 *
 * <p>核心类型在 {@code com.xihanfun.framework.primitives}，零依赖、不可变、线程安全；线上 JSON 编码在
 * {@code com.xihanfun.framework.primitives.json}，以 Jackson 模块的形式提供，Jackson 是可选依赖。
 */
module com.xihanfun.framework.primitives {
    exports com.xihanfun.framework.primitives;
    exports com.xihanfun.framework.primitives.json;

    // 只有 JSON 编码用到 Jackson：编译期必需、运行期可选。不加 transitive：可选依赖不能强加给下游的编译，用编码的模块自己 requires Jackson
    requires static com.fasterxml.jackson.databind;
}
