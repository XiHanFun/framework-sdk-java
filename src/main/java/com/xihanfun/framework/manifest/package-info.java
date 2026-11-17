/**
 * {@code xihan.manifest.json} 的模型（RFC-0002，Schema 见 {@code spec/manifest/manifest.schema.json}）：只做数据与格式校验，
 * 收集、解析与求解是宿主的职责。
 *
 * <p>模型类型不可变、线程安全。组成类型（{@link com.xihanfun.framework.manifest.Binding}、
 * {@link com.xihanfun.framework.manifest.CapabilityRequirement} 等）在构造时校验格式，不合法抛
 * {@link java.lang.IllegalArgumentException}；{@link com.xihanfun.framework.manifest.Manifest} 在 {@code build()} 时校验字段与跨字段约束，
 * 抛带 {@code PRIMITIVES_VALIDATION_001} 的 {@link com.xihanfun.framework.primitives.FrameworkException}；
 * {@link com.xihanfun.framework.manifest.ManifestJson} 读取失败时抛带 {@code KERNEL_CAPABILITY_006} 与 .NET 同一组消息键、参数的
 * {@code FrameworkException}（RFC-0002 解析规则 2，ADR-0236）。
 */
package com.xihanfun.framework.manifest;
