package com.xihanfun.framework.plugin.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
 * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
 * </pre>
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class PluginGrpc {

  private PluginGrpc() {}

  public static final java.lang.String SERVICE_NAME = "xihan.plugin.v1.Plugin";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse> getHandshakeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Handshake",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse> getHandshakeMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest, com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse> getHandshakeMethod;
    if ((getHandshakeMethod = PluginGrpc.getHandshakeMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getHandshakeMethod = PluginGrpc.getHandshakeMethod) == null) {
          PluginGrpc.getHandshakeMethod = getHandshakeMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest, com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Handshake"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("Handshake"))
              .build();
        }
      }
    }
    return getHandshakeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse> getConfigureMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Configure",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse> getConfigureMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest, com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse> getConfigureMethod;
    if ((getConfigureMethod = PluginGrpc.getConfigureMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getConfigureMethod = PluginGrpc.getConfigureMethod) == null) {
          PluginGrpc.getConfigureMethod = getConfigureMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest, com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Configure"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("Configure"))
              .build();
        }
      }
    }
    return getConfigureMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StartRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.StartResponse> getStartMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Start",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.StartRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.StartResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StartRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.StartResponse> getStartMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StartRequest, com.xihanfun.framework.plugin.v1.PluginProto.StartResponse> getStartMethod;
    if ((getStartMethod = PluginGrpc.getStartMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getStartMethod = PluginGrpc.getStartMethod) == null) {
          PluginGrpc.getStartMethod = getStartMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.StartRequest, com.xihanfun.framework.plugin.v1.PluginProto.StartResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Start"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.StartRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.StartResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("Start"))
              .build();
        }
      }
    }
    return getStartMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StopRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.StopResponse> getStopMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Stop",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.StopRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.StopResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StopRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.StopResponse> getStopMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StopRequest, com.xihanfun.framework.plugin.v1.PluginProto.StopResponse> getStopMethod;
    if ((getStopMethod = PluginGrpc.getStopMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getStopMethod = PluginGrpc.getStopMethod) == null) {
          PluginGrpc.getStopMethod = getStopMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.StopRequest, com.xihanfun.framework.plugin.v1.PluginProto.StopResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Stop"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.StopRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.StopResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("Stop"))
              .build();
        }
      }
    }
    return getStopMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> getHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Health",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> getHealthMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest, com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> getHealthMethod;
    if ((getHealthMethod = PluginGrpc.getHealthMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getHealthMethod = PluginGrpc.getHealthMethod) == null) {
          PluginGrpc.getHealthMethod = getHealthMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest, com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Health"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("Health"))
              .build();
        }
      }
    }
    return getHealthMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> getWatchHealthMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "WatchHealth",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> getWatchHealthMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest, com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> getWatchHealthMethod;
    if ((getWatchHealthMethod = PluginGrpc.getWatchHealthMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getWatchHealthMethod = PluginGrpc.getWatchHealthMethod) == null) {
          PluginGrpc.getWatchHealthMethod = getWatchHealthMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest, com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "WatchHealth"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("WatchHealth"))
              .build();
        }
      }
    }
    return getWatchHealthMethod;
  }

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
    if ((getInvokeMethod = PluginGrpc.getInvokeMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getInvokeMethod = PluginGrpc.getInvokeMethod) == null) {
          PluginGrpc.getInvokeMethod = getInvokeMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest, com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Invoke"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("Invoke"))
              .build();
        }
      }
    }
    return getInvokeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame,
      com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> getInvokeStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "InvokeStream",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame,
      com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> getInvokeStreamMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame, com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> getInvokeStreamMethod;
    if ((getInvokeStreamMethod = PluginGrpc.getInvokeStreamMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getInvokeStreamMethod = PluginGrpc.getInvokeStreamMethod) == null) {
          PluginGrpc.getInvokeStreamMethod = getInvokeStreamMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame, com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "InvokeStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("InvokeStream"))
              .build();
        }
      }
    }
    return getInvokeStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse> getHandleEventMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "HandleEvent",
      requestType = com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest.class,
      responseType = com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest,
      com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse> getHandleEventMethod() {
    io.grpc.MethodDescriptor<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest, com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse> getHandleEventMethod;
    if ((getHandleEventMethod = PluginGrpc.getHandleEventMethod) == null) {
      synchronized (PluginGrpc.class) {
        if ((getHandleEventMethod = PluginGrpc.getHandleEventMethod) == null) {
          PluginGrpc.getHandleEventMethod = getHandleEventMethod =
              io.grpc.MethodDescriptor.<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest, com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "HandleEvent"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse.getDefaultInstance()))
              .setSchemaDescriptor(new PluginMethodDescriptorSupplier("HandleEvent"))
              .build();
        }
      }
    }
    return getHandleEventMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static PluginStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginStub>() {
        @java.lang.Override
        public PluginStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginStub(channel, callOptions);
        }
      };
    return PluginStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static PluginBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginBlockingV2Stub>() {
        @java.lang.Override
        public PluginBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginBlockingV2Stub(channel, callOptions);
        }
      };
    return PluginBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static PluginBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginBlockingStub>() {
        @java.lang.Override
        public PluginBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginBlockingStub(channel, callOptions);
        }
      };
    return PluginBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static PluginFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<PluginFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<PluginFutureStub>() {
        @java.lang.Override
        public PluginFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new PluginFutureStub(channel, callOptions);
        }
      };
    return PluginFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
   * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 握手：协商协议版本、核对原语层版本、交换清单、操作与订阅声明、消息目录。
     * </pre>
     */
    default void handshake(com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHandshakeMethod(), responseObserver);
    }

    /**
     * <pre>
     * 配置：下发配置节、已授予的权限、资源绑定与工作目录。
     * </pre>
     */
    default void configure(com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getConfigureMethod(), responseObserver);
    }

    /**
     * <pre>
     * 启动；返回后插件应能接受调用。
     * </pre>
     */
    default void start(com.xihanfun.framework.plugin.v1.PluginProto.StartRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StartResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStartMethod(), responseObserver);
    }

    /**
     * <pre>
     * 停止：在宽限期内完成在途调用并释放资源；返回后宿主等待进程退出。
     * </pre>
     */
    default void stop(com.xihanfun.framework.plugin.v1.PluginProto.StopRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StopResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getStopMethod(), responseObserver);
    }

    /**
     * <pre>
     * 健康探测。
     * </pre>
     */
    default void health(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHealthMethod(), responseObserver);
    }

    /**
     * <pre>
     * 健康推送：订阅后先发当前状态，之后状态或健康项变化时各发一条。
     * </pre>
     */
    default void watchHealth(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getWatchHealthMethod(), responseObserver);
    }

    /**
     * <pre>
     * 调用插件服务的一个一元操作。
     * </pre>
     */
    default void invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getInvokeMethod(), responseObserver);
    }

    /**
     * <pre>
     * 调用插件服务的一个流式操作：双向帧流，见 invocation.md。
     * </pre>
     */
    default io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> invokeStream(
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getInvokeStreamMethod(), responseObserver);
    }

    /**
     * <pre>
     * 把一条消息投递给插件的事件处理器。
     * </pre>
     */
    default void handleEvent(com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getHandleEventMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Plugin.
   * <pre>
   * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
   * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
   * </pre>
   */
  public static abstract class PluginImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return PluginGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Plugin.
   * <pre>
   * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
   * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
   * </pre>
   */
  public static final class PluginStub
      extends io.grpc.stub.AbstractAsyncStub<PluginStub> {
    private PluginStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginStub(channel, callOptions);
    }

    /**
     * <pre>
     * 握手：协商协议版本、核对原语层版本、交换清单、操作与订阅声明、消息目录。
     * </pre>
     */
    public void handshake(com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHandshakeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 配置：下发配置节、已授予的权限、资源绑定与工作目录。
     * </pre>
     */
    public void configure(com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getConfigureMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 启动；返回后插件应能接受调用。
     * </pre>
     */
    public void start(com.xihanfun.framework.plugin.v1.PluginProto.StartRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StartResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 停止：在宽限期内完成在途调用并释放资源；返回后宿主等待进程退出。
     * </pre>
     */
    public void stop(com.xihanfun.framework.plugin.v1.PluginProto.StopRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StopResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 健康探测。
     * </pre>
     */
    public void health(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 健康推送：订阅后先发当前状态，之后状态或健康项变化时各发一条。
     * </pre>
     */
    public void watchHealth(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getWatchHealthMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 调用插件服务的一个一元操作。
     * </pre>
     */
    public void invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getInvokeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 调用插件服务的一个流式操作：双向帧流，见 invocation.md。
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> invokeStream(
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getInvokeStreamMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * 把一条消息投递给插件的事件处理器。
     * </pre>
     */
    public void handleEvent(com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest request,
        io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getHandleEventMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Plugin.
   * <pre>
   * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
   * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
   * </pre>
   */
  public static final class PluginBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<PluginBlockingV2Stub> {
    private PluginBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginBlockingV2Stub(channel, callOptions);
    }

    /**
     * <pre>
     * 握手：协商协议版本、核对原语层版本、交换清单、操作与订阅声明、消息目录。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse handshake(com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHandshakeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 配置：下发配置节、已授予的权限、资源绑定与工作目录。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse configure(com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getConfigureMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 启动；返回后插件应能接受调用。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.StartResponse start(com.xihanfun.framework.plugin.v1.PluginProto.StartRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStartMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 停止：在宽限期内完成在途调用并释放资源；返回后宿主等待进程退出。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.StopResponse stop(com.xihanfun.framework.plugin.v1.PluginProto.StopRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 健康探测。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse health(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 健康推送：订阅后先发当前状态，之后状态或健康项变化时各发一条。
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<?, com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>
        watchHealth(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingV2ServerStreamingCall(
          getChannel(), getWatchHealthMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 调用插件服务的一个一元操作。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getInvokeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 调用插件服务的一个流式操作：双向帧流，见 invocation.md。
     * </pre>
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame, com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame>
        invokeStream() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getInvokeStreamMethod(), getCallOptions());
    }

    /**
     * <pre>
     * 把一条消息投递给插件的事件处理器。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse handleEvent(com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getHandleEventMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service Plugin.
   * <pre>
   * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
   * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
   * </pre>
   */
  public static final class PluginBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<PluginBlockingStub> {
    private PluginBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 握手：协商协议版本、核对原语层版本、交换清单、操作与订阅声明、消息目录。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse handshake(com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHandshakeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 配置：下发配置节、已授予的权限、资源绑定与工作目录。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse configure(com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getConfigureMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 启动；返回后插件应能接受调用。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.StartResponse start(com.xihanfun.framework.plugin.v1.PluginProto.StartRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStartMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 停止：在宽限期内完成在途调用并释放资源；返回后宿主等待进程退出。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.StopResponse stop(com.xihanfun.framework.plugin.v1.PluginProto.StopRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getStopMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 健康探测。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse health(com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHealthMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 健康推送：订阅后先发当前状态，之后状态或健康项变化时各发一条。
     * </pre>
     */
    public java.util.Iterator<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> watchHealth(
        com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getWatchHealthMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 调用插件服务的一个一元操作。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse invoke(com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getInvokeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 把一条消息投递给插件的事件处理器。
     * </pre>
     */
    public com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse handleEvent(com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getHandleEventMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Plugin.
   * <pre>
   * 插件实现、宿主调用。调用顺序：Handshake → Configure → Start → (Invoke | InvokeStream | HandleEvent | Health | WatchHealth)* → Stop。
   * 每次调用都带元数据 `xihan-plugin-session`（会话令牌），插件校验，缺失或不匹配以 `Unauthenticated` 拒绝（`handshake.md` §4）。
   * </pre>
   */
  public static final class PluginFutureStub
      extends io.grpc.stub.AbstractFutureStub<PluginFutureStub> {
    private PluginFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected PluginFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new PluginFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 握手：协商协议版本、核对原语层版本、交换清单、操作与订阅声明、消息目录。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse> handshake(
        com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHandshakeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 配置：下发配置节、已授予的权限、资源绑定与工作目录。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse> configure(
        com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getConfigureMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 启动；返回后插件应能接受调用。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.StartResponse> start(
        com.xihanfun.framework.plugin.v1.PluginProto.StartRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStartMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 停止：在宽限期内完成在途调用并释放资源；返回后宿主等待进程退出。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.StopResponse> stop(
        com.xihanfun.framework.plugin.v1.PluginProto.StopRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getStopMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 健康探测。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse> health(
        com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHealthMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 调用插件服务的一个一元操作。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse> invoke(
        com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getInvokeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 把一条消息投递给插件的事件处理器。
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse> handleEvent(
        com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getHandleEventMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_HANDSHAKE = 0;
  private static final int METHODID_CONFIGURE = 1;
  private static final int METHODID_START = 2;
  private static final int METHODID_STOP = 3;
  private static final int METHODID_HEALTH = 4;
  private static final int METHODID_WATCH_HEALTH = 5;
  private static final int METHODID_INVOKE = 6;
  private static final int METHODID_HANDLE_EVENT = 7;
  private static final int METHODID_INVOKE_STREAM = 8;

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
        case METHODID_HANDSHAKE:
          serviceImpl.handshake((com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse>) responseObserver);
          break;
        case METHODID_CONFIGURE:
          serviceImpl.configure((com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse>) responseObserver);
          break;
        case METHODID_START:
          serviceImpl.start((com.xihanfun.framework.plugin.v1.PluginProto.StartRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StartResponse>) responseObserver);
          break;
        case METHODID_STOP:
          serviceImpl.stop((com.xihanfun.framework.plugin.v1.PluginProto.StopRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StopResponse>) responseObserver);
          break;
        case METHODID_HEALTH:
          serviceImpl.health((com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>) responseObserver);
          break;
        case METHODID_WATCH_HEALTH:
          serviceImpl.watchHealth((com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>) responseObserver);
          break;
        case METHODID_INVOKE:
          serviceImpl.invoke((com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse>) responseObserver);
          break;
        case METHODID_HANDLE_EVENT:
          serviceImpl.handleEvent((com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest) request,
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse>) responseObserver);
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
        case METHODID_INVOKE_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.invokeStream(
              (io.grpc.stub.StreamObserver<com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getHandshakeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.HandshakeRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.HandshakeResponse>(
                service, METHODID_HANDSHAKE)))
        .addMethod(
          getConfigureMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.ConfigureRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.ConfigureResponse>(
                service, METHODID_CONFIGURE)))
        .addMethod(
          getStartMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.StartRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.StartResponse>(
                service, METHODID_START)))
        .addMethod(
          getStopMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.StopRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.StopResponse>(
                service, METHODID_STOP)))
        .addMethod(
          getHealthMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>(
                service, METHODID_HEALTH)))
        .addMethod(
          getWatchHealthMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.HealthRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.HealthResponse>(
                service, METHODID_WATCH_HEALTH)))
        .addMethod(
          getInvokeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.InvokeRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.InvokeResponse>(
                service, METHODID_INVOKE)))
        .addMethod(
          getInvokeStreamMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame,
              com.xihanfun.framework.plugin.v1.PluginProto.StreamFrame>(
                service, METHODID_INVOKE_STREAM)))
        .addMethod(
          getHandleEventMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xihanfun.framework.plugin.v1.PluginProto.HandleEventRequest,
              com.xihanfun.framework.plugin.v1.PluginProto.HandleEventResponse>(
                service, METHODID_HANDLE_EVENT)))
        .build();
  }

  private static abstract class PluginBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    PluginBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.xihanfun.framework.plugin.v1.PluginProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Plugin");
    }
  }

  private static final class PluginFileDescriptorSupplier
      extends PluginBaseDescriptorSupplier {
    PluginFileDescriptorSupplier() {}
  }

  private static final class PluginMethodDescriptorSupplier
      extends PluginBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    PluginMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (PluginGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new PluginFileDescriptorSupplier())
              .addMethod(getHandshakeMethod())
              .addMethod(getConfigureMethod())
              .addMethod(getStartMethod())
              .addMethod(getStopMethod())
              .addMethod(getHealthMethod())
              .addMethod(getWatchHealthMethod())
              .addMethod(getInvokeMethod())
              .addMethod(getInvokeStreamMethod())
              .addMethod(getHandleEventMethod())
              .build();
        }
      }
    }
    return result;
  }
}
