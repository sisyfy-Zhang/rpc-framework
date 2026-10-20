package com.feynman.rpc.bench.grpc;

import java.util.Optional;
import java.util.Set;

import com.feynman.rpc.bench.CallTarget;
import com.feynman.rpc.bench.CallTargetProvider;
import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.Profile;
import com.feynman.rpc.bench.ServerLauncher;

/**
 * {@code transport: grpc}（{@code META-INF/services/com.feynman.rpc.bench.CallTargetProvider}）：卡片的 {@code serializer} 只能是
 * {@code protobuf}（gRPC 的生成消息），目标经 {@code --direct host:port} 给出；服务端是 {@link GrpcBenchServer}
 * （{@code bench server --transport grpc ...}）。
 */
public final class GrpcCallTargetProvider implements CallTargetProvider {

	public static final String TRANSPORT = "grpc";

	@Override
	public String transport() {
		return TRANSPORT;
	}

	@Override
	public Set<String> serializers() {
		return Set.of("protobuf");
	}

	@Override
	public CallTarget create(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options) {
		return GrpcCallTarget.connect(profile, endpoint, options);
	}

	@Override
	public Optional<ServerLauncher> serverLauncher() {
		return Optional.of(GrpcBenchServer::run);
	}
}
