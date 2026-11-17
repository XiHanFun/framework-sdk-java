/**
 * 原语的线上 JSON 编码（RFC-0001「线上编码」）：以 Jackson 模块 {@link com.xihanfun.framework.primitives.json.PrimitivesModule} 提供。
 *
 * <p>原语核心类型不依赖 Jackson（原语层不含序列化代码）；本包是可选的编码适配，要用时把 {@code jackson-databind} 加进依赖，
 * 再向 {@code ObjectMapper} 注册 {@code new PrimitivesModule()}。编码与 .NET 参考实现和 Go SDK 逐字一致。
 */
package com.xihanfun.framework.primitives.json;
