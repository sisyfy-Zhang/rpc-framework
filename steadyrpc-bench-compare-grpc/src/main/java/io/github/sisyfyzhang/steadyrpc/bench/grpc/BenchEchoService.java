package io.github.sisyfyzhang.steadyrpc.bench.grpc;

import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.bench.ServerProbes;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.BenchEchoGrpc;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.Bytes;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.Empty;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.Env;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.ServerStats;

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
		responseObserver.onNext(ServerStats.newBuilder().putAllValues(ServerProbes.stats()).build());
		responseObserver.onCompleted();
	}
}
