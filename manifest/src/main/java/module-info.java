/**
 * {@code xihan.manifest.json} 的模型、读取、规范化输出与校验（RFC-0002）。
 */
module com.xihanfun.framework.manifest {
    exports com.xihanfun.framework.manifest;

    requires transitive com.xihanfun.framework.primitives;
    // Jackson 只在读取实现里用到，不出现在公开 API 上
    requires com.fasterxml.jackson.databind;
}
