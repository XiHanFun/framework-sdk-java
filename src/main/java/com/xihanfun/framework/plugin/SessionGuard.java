package com.xihanfun.framework.plugin;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * 只放行带本次会话令牌的宿主调用（{@code handshake.md} §4，ADR-0202）：宿主调用 {@code Plugin} 服务的每一次 RPC（含 {@code Handshake}）
 * 都在元数据 {@value PluginRuntime#SESSION_METADATA} 里带 {@code XIHAN_PLUGIN_SESSION}，缺失或不匹配以 {@code Unauthenticated} 拒绝、不执行；
 * 本机别的进程连上插件端点也冒充不了宿主。
 */
final class SessionGuard implements ServerInterceptor {
    /** 元数据里的会话令牌。 */
    static final Metadata.Key<String> KEY = Metadata.Key.of(PluginRuntime.SESSION_METADATA, Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] token;

    SessionGuard(String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!accepts(headers)) {
            call.close(Status.UNAUTHENTICATED.withDescription("The plugin session token is missing or invalid."), new Metadata());
            // 不调用 next：拒绝的调用碰不到服务实现
            return new ServerCall.Listener<>() {
            };
        }
        return next.startCall(call, headers);
    }

    /**
     * 只认恰好一个值；按常数时间比较（MessageDigest.isEqual 对等长的输入不随第一个不同的字节提前返回），耗时不泄露令牌的前缀。
     */
    boolean accepts(Metadata headers) {
        Iterable<String> values = headers.getAll(KEY);
        if (values == null || token.length == 0) {
            return false;
        }
        Iterator<String> iterator = values.iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        String presented = iterator.next();
        if (iterator.hasNext()) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }
}
