package com.xihanfun.framework.primitives;

/**
 * 框架与本 SDK 的信息（RFC-0001 FrameworkInfo）。
 *
 * <p>全部是编译期常量，不经反射；{@link #SDK_VERSION} 与 Maven 项目版本由测试核对一致。
 */
public final class FrameworkInfo {
    /** 框架名。 */
    public static final String NAME = "XiHan Framework";

    /** 本 SDK 实现的原语层大版本。 */
    public static final String SPEC_VERSION = "1";

    /** 插件握手时上报的 SDK 名。 */
    public static final String SDK_NAME = "framework-sdk-java";

    /** 本 SDK 的版本，与 Maven 项目版本相同。 */
    public static final String SDK_VERSION = "0.1.0-SNAPSHOT";

    /** 插件握手时上报的实现语言。 */
    public static final String SDK_LANGUAGE = "java";

    /** SDK 仓库地址。 */
    public static final String REPOSITORY = "https://github.com/XiHanFun/framework-sdk-java";

    /** 文档地址。 */
    public static final String DOCUMENTATION = "https://framework.docs.xihanfun.com";

    private FrameworkInfo() {
    }
}
