package com.xihanfun.framework.plugin.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class HostGrpc {

  private HostGrpc() {}

  public static final java.lang.String SERVICE_NAME = "xihan.plugin.v1.Host";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> getInvokeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Invoke",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> getInvokeMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest, com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> getInvokeMethod;
    if ((getInvokeMethod = HostGrpc.getInvokeMethod) == null) {
      synchronized (HostGrpc.class) {
        if ((getInvokeMethod = HostGrpc.getInvokeMethod) == null) {
          HostGrpc.getInvokeMethod = getInvokeMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest, com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Invoke"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new HostMethodDescriptorSupplier("Invoke"))
              .build();
        }
      }
    }
    return getInvokeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse> getPublishMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Publish",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse> getPublishMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest, com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse> getPublishMethod;
    if ((getPublishMethod = HostGrpc.getPublishMethod) == null) {
      synchronized (HostGrpc.class) {
        if ((getPublishMethod = HostGrpc.getPublishMethod) == null) {
          HostGrpc.getPublishMethod = getPublishMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest, com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Publish"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse.getDefaultInstance()))
              .setSchemaDescriptor(new HostMethodDescriptorSupplier("Publish"))
              .build();
        }
      }
    }
    return getPublishMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static HostStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostStub>() {
        @java.lang.Override
        public HostStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostStub(channel, callOptions);
        }
      };
    return HostStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static HostBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostBlockingV2Stub>() {
        @java.lang.Override
        public HostBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostBlockingV2Stub(channel, callOptions);
        }
      };
    return HostBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static HostBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostBlockingStub>() {
        @java.lang.Override
        public HostBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostBlockingStub(channel, callOptions);
        }
      };
    return HostBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static HostFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<HostFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<HostFutureStub>() {
        @java.lang.Override
        public HostFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new HostFutureStub(channel, callOptions);
        }
      };
    return HostFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 经宿主的完整管道调用一个操作；需要权限 `capability:kernel.pipeline`。
     * </pre>
     */
    default void invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInvokeMethod(), responseObserver);
    }

    /**
     * <pre>
     * 经宿主的消息代理发布消息（批量，逐项结果）；需要权限 `capability:messaging.queue`。
     * </pre>
     */
    default void publish(com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPublishMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Host.
   * <pre>
   * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
   * </pre>
   */
  public static abstract class HostImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return HostGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Host.
   * <pre>
   * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
   * </pre>
   */
  public static final class HostStub
      extends io.grpc.stub.AbstractAsyncStub<HostStub> {
    private HostStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostStub(channel, callOptions);
    }

    /**
     * <pre>
     * 经宿主的完整管道调用一个操作；需要权限 `capability:kernel.pipeline`。
     * </pre>
     */
    public void invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInvokeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 经宿主的消息代理发布消息（批量，逐项结果）；需要权限 `capability:messaging.queue`。
     * </pre>
     */
    public void publish(com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPublishMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Host.
   * <pre>
   * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
   * </pre>
   */
  public static final class HostBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<HostBlockingV2Stub> {
    private HostBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * 经宿主的完整管道调用一个操作；需要权限 `capability:kernel.pipeline`。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInvokeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 经宿主的消息代理发布消息（批量，逐项结果）；需要权限 `capability:messaging.queue`。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse publish(com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getPublishMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service Host.
   * <pre>
   * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
   * </pre>
   */
  public static final class HostBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<HostBlockingStub> {
    private HostBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 经宿主的完整管道调用一个操作；需要权限 `capability:kernel.pipeline`。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInvokeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 经宿主的消息代理发布消息（批量，逐项结果）；需要权限 `capability:messaging.queue`。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse publish(com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPublishMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Host.
   * <pre>
   * 宿主实现、插件回调。每次调用必须带元数据 `xihan-plugin-session`（握手前经环境变量下发的会话令牌）。
   * </pre>
   */
  public static final class HostFutureStub
      extends io.grpc.stub.AbstractFutureStub<HostFutureStub> {
    private HostFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected HostFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new HostFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 经宿主的完整管道调用一个操作；需要权限 `capability:kernel.pipeline`。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> invoke(
        com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInvokeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 经宿主的消息代理发布消息（批量，逐项结果）；需要权限 `capability:messaging.queue`。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse> publish(
        com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPublishMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_INVOKE = 0;
  private static final int METHODID_PUBLISH = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_INVOKE:
          serviceImpl.invoke((com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse>) responseObserver);
          break;
        case METHODID_PUBLISH:
          serviceImpl.publish((com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getInvokeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse>(
                service, METHODID_INVOKE)))
        .addMethod(
          getPublishMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.PublishRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.PublishResponse>(
                service, METHODID_PUBLISH)))
        .build();
  }

  private static abstract class HostBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    HostBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.xihanfun.framework.plugin.v1.PluginProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Host");
    }
  }

  private static final class HostFileDescriptorSupplier
      extends HostBaseDescriptorSupplier {
    HostFileDescriptorSupplier() {}
  }

  private static final class HostMethodDescriptorSupplier
      extends HostBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    HostMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (HostGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new HostFileDescriptorSupplier())
              .addMethod(getInvokeMethod())
              .addMethod(getPublishMethod())
              .build();
        }
      }
    }
    return result;
  }
}
