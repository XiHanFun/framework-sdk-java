/**
 * 原语的线上 JSON 编码（RFC-0001「线上编码」）：以 Jackson 模块 {@link com.xihanfun.framework.primitives.json.PrimitivesModule} 提供。
 *
 * <p>原语核心类型不依赖 Jackson（原语层不含序列化代码），编码集中在本包：向 {@code ObjectMapper} 注册 {@code new PrimitivesModule()} 即可。
 * 写出与读取的规则见 RFC-0001「Result」，由向量 {@code conformance/vectors/primitives/wire-encoding.json} 逐字核对。
 */
package com.xihanfun.framework.primitives.json;
