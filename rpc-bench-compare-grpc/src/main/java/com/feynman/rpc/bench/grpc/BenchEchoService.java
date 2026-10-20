package com.feynman.rpc.bench.grpc;

import java.util.Map;

import com.feynman.rpc.bench.ServerProbes;
import com.feynman.rpc.bench.grpc.proto.BenchEchoGrpc;
import com.feynman.rpc.bench.grpc.proto.Bytes;
import com.feynman.rpc.bench.grpc.proto.Empty;
import com.feynman.rpc.bench.grpc.proto.Env;
import com.feynman.rpc.bench.grpc.proto.ServerStats;

import io.grpc.stub.StreamObserver;

/**
 * 与 {@code BenchServiceImpl} 对等的 gRPC 实现：{@code Echo} 原样返回请求消息（不拷贝、不做业务计算），
 * {@code Environment} 返回启动时采集好的环境，{@code Stats} 返回此刻的 GC 统计（{@link ServerProbes}，与 bench server 同一套字段）。
 */
final class BenchEchoService extends BenchEchoGrpc.BenchEchoImplBase {

	private final Env environment;

	BenchEchoService(Map<String, String> environment) {
		this.environment = Env.newBuilder().putAllValues(environment).build();
	}

	@Override
	public void echo(Bytes request, StreamObserver<Bytes> responseObserver) {
		responseObserver.onNext(request);
		responseObserver.onCompleted();
	}

	@Override
	public void environment(Empty request, StreamObserver<Env> responseObserver) {
		responseObserver.onNext(environment);
		responseObserver.onCompleted();
	}

	@Override
	public void stats(Empty request, StreamObserver<ServerStats> responseObserver) {
		responseObserver.onNext(ServerStats.newBuilder().putAllValues(ServerProbes.gcStats()).build());
		responseObserver.onCompleted();
	}
}
