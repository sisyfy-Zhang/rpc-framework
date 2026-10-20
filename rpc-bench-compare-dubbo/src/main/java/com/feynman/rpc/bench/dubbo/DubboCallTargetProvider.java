package com.feynman.rpc.bench.dubbo;

import java.util.Optional;
import java.util.Set;

import com.feynman.rpc.bench.CallTarget;
import com.feynman.rpc.bench.CallTargetProvider;
import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.Profile;
import com.feynman.rpc.bench.ServerLauncher;

/**
 * {@code transport: dubbo}（{@code META-INF/services/com.feynman.rpc.bench.CallTargetProvider}）：Dubbo 3 triple、Java 接口模式。
 * 卡片的 {@code serializer} 写 Dubbo 实际使用的序列化名（开压前与调用 URL 核对）；目标经 {@code --direct host:port} 给出；
 * 服务端是 {@link DubboBenchServer}（{@code bench server --transport dubbo ...}）。
 */
public final class DubboCallTargetProvider implements CallTargetProvider {

	public static final String TRANSPORT = "dubbo";

	@Override
	public String transport() {
		return TRANSPORT;
	}

	/** Dubbo 3.3 内置、能用于 Java 接口模式的序列化名；实际生效的是哪一个由开压前的核对决定。 */
	@Override
	public Set<String> serializers() {
		return Set.of("hessian2", "fastjson2");
	}

	@Override
	public CallTarget create(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options) {
		return DubboCallTarget.connect(profile, endpoint, options);
	}

	@Override
	public Optional<ServerLauncher> serverLauncher() {
		return Optional.of(DubboBenchServer::run);
	}
}
