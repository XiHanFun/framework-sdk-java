/**
 * SDK 的内部实现：模块内各包共用的辅助类型，如规范所说的空白（RFC-0001「空白字符」）、枚举按线上名字的查找（{@code WireNames}）、
 * 不止一个包要核对的文法（{@code Grammars}）、编不成 UTF-8 的文本的广义 UTF-8 写法（{@code GeneralizedUtf8}）与按值模型写出 JSON 值
 * （{@code JsonValueWriter}，原语的线上编码与插件协议的错误细节共用）。
 *
 * <p>本包不属于公开 API：{@code module-info.java} 不导出它，模块外看不见；其中的类型为了让模块内的 {@code primitives} 与
 * {@code plugin} 等包共用而声明为 {@code public}，随时可能改变，使用者不要依赖。
 */
package com.xihanfun.framework.internal;
