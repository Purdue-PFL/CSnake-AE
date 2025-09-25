package pfl.test_execute;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.64.0)",
    comments = "Source: TestExecutionService.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class TestExecutionGrpc {

  private TestExecutionGrpc() {}

  public static final java.lang.String SERVICE_NAME = "TestExecution";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<pfl.test_execute.TestExecutionService.ClientID,
      pfl.test_execute.TestExecutionService.Task> getGetTaskMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "getTask",
      requestType = pfl.test_execute.TestExecutionService.ClientID.class,
      responseType = pfl.test_execute.TestExecutionService.Task.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pfl.test_execute.TestExecutionService.ClientID,
      pfl.test_execute.TestExecutionService.Task> getGetTaskMethod() {
    io.grpc.MethodDescriptor<pfl.test_execute.TestExecutionService.ClientID, pfl.test_execute.TestExecutionService.Task> getGetTaskMethod;
    if ((getGetTaskMethod = TestExecutionGrpc.getGetTaskMethod) == null) {
      synchronized (TestExecutionGrpc.class) {
        if ((getGetTaskMethod = TestExecutionGrpc.getGetTaskMethod) == null) {
          TestExecutionGrpc.getGetTaskMethod = getGetTaskMethod =
              io.grpc.MethodDescriptor.<pfl.test_execute.TestExecutionService.ClientID, pfl.test_execute.TestExecutionService.Task>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "getTask"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pfl.test_execute.TestExecutionService.ClientID.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pfl.test_execute.TestExecutionService.Task.getDefaultInstance()))
              .setSchemaDescriptor(new TestExecutionMethodDescriptorSupplier("getTask"))
              .build();
        }
      }
    }
    return getGetTaskMethod;
  }

  private static volatile io.grpc.MethodDescriptor<pfl.test_execute.TestExecutionService.TaskResult,
      com.google.protobuf.UInt64Value> getTaskFinishMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "taskFinish",
      requestType = pfl.test_execute.TestExecutionService.TaskResult.class,
      responseType = com.google.protobuf.UInt64Value.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<pfl.test_execute.TestExecutionService.TaskResult,
      com.google.protobuf.UInt64Value> getTaskFinishMethod() {
    io.grpc.MethodDescriptor<pfl.test_execute.TestExecutionService.TaskResult, com.google.protobuf.UInt64Value> getTaskFinishMethod;
    if ((getTaskFinishMethod = TestExecutionGrpc.getTaskFinishMethod) == null) {
      synchronized (TestExecutionGrpc.class) {
        if ((getTaskFinishMethod = TestExecutionGrpc.getTaskFinishMethod) == null) {
          TestExecutionGrpc.getTaskFinishMethod = getTaskFinishMethod =
              io.grpc.MethodDescriptor.<pfl.test_execute.TestExecutionService.TaskResult, com.google.protobuf.UInt64Value>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "taskFinish"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  pfl.test_execute.TestExecutionService.TaskResult.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.google.protobuf.UInt64Value.getDefaultInstance()))
              .setSchemaDescriptor(new TestExecutionMethodDescriptorSupplier("taskFinish"))
              .build();
        }
      }
    }
    return getTaskFinishMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static TestExecutionStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TestExecutionStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TestExecutionStub>() {
        @java.lang.Override
        public TestExecutionStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TestExecutionStub(channel, callOptions);
        }
      };
    return TestExecutionStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static TestExecutionBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TestExecutionBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TestExecutionBlockingStub>() {
        @java.lang.Override
        public TestExecutionBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TestExecutionBlockingStub(channel, callOptions);
        }
      };
    return TestExecutionBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static TestExecutionFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<TestExecutionFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<TestExecutionFutureStub>() {
        @java.lang.Override
        public TestExecutionFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new TestExecutionFutureStub(channel, callOptions);
        }
      };
    return TestExecutionFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void getTask(pfl.test_execute.TestExecutionService.ClientID request,
        io.grpc.stub.StreamObserver<pfl.test_execute.TestExecutionService.Task> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetTaskMethod(), responseObserver);
    }

    /**
     */
    default void taskFinish(pfl.test_execute.TestExecutionService.TaskResult request,
        io.grpc.stub.StreamObserver<com.google.protobuf.UInt64Value> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTaskFinishMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service TestExecution.
   */
  public static abstract class TestExecutionImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return TestExecutionGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service TestExecution.
   */
  public static final class TestExecutionStub
      extends io.grpc.stub.AbstractAsyncStub<TestExecutionStub> {
    private TestExecutionStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestExecutionStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TestExecutionStub(channel, callOptions);
    }

    /**
     */
    public void getTask(pfl.test_execute.TestExecutionService.ClientID request,
        io.grpc.stub.StreamObserver<pfl.test_execute.TestExecutionService.Task> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetTaskMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void taskFinish(pfl.test_execute.TestExecutionService.TaskResult request,
        io.grpc.stub.StreamObserver<com.google.protobuf.UInt64Value> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTaskFinishMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service TestExecution.
   */
  public static final class TestExecutionBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<TestExecutionBlockingStub> {
    private TestExecutionBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestExecutionBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TestExecutionBlockingStub(channel, callOptions);
    }

    /**
     */
    public pfl.test_execute.TestExecutionService.Task getTask(pfl.test_execute.TestExecutionService.ClientID request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetTaskMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.google.protobuf.UInt64Value taskFinish(pfl.test_execute.TestExecutionService.TaskResult request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTaskFinishMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service TestExecution.
   */
  public static final class TestExecutionFutureStub
      extends io.grpc.stub.AbstractFutureStub<TestExecutionFutureStub> {
    private TestExecutionFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected TestExecutionFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new TestExecutionFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<pfl.test_execute.TestExecutionService.Task> getTask(
        pfl.test_execute.TestExecutionService.ClientID request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetTaskMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.google.protobuf.UInt64Value> taskFinish(
        pfl.test_execute.TestExecutionService.TaskResult request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTaskFinishMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_TASK = 0;
  private static final int METHODID_TASK_FINISH = 1;

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
        case METHODID_GET_TASK:
          serviceImpl.getTask((pfl.test_execute.TestExecutionService.ClientID) request,
              (io.grpc.stub.StreamObserver<pfl.test_execute.TestExecutionService.Task>) responseObserver);
          break;
        case METHODID_TASK_FINISH:
          serviceImpl.taskFinish((pfl.test_execute.TestExecutionService.TaskResult) request,
              (io.grpc.stub.StreamObserver<com.google.protobuf.UInt64Value>) responseObserver);
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
          getGetTaskMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pfl.test_execute.TestExecutionService.ClientID,
              pfl.test_execute.TestExecutionService.Task>(
                service, METHODID_GET_TASK)))
        .addMethod(
          getTaskFinishMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              pfl.test_execute.TestExecutionService.TaskResult,
              com.google.protobuf.UInt64Value>(
                service, METHODID_TASK_FINISH)))
        .build();
  }

  private static abstract class TestExecutionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    TestExecutionBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return pfl.test_execute.TestExecutionService.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("TestExecution");
    }
  }

  private static final class TestExecutionFileDescriptorSupplier
      extends TestExecutionBaseDescriptorSupplier {
    TestExecutionFileDescriptorSupplier() {}
  }

  private static final class TestExecutionMethodDescriptorSupplier
      extends TestExecutionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    TestExecutionMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (TestExecutionGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new TestExecutionFileDescriptorSupplier())
              .addMethod(getGetTaskMethod())
              .addMethod(getTaskFinishMethod())
              .build();
        }
      }
    }
    return result;
  }
}
