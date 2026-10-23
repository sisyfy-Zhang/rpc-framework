package io.github.sisyfyzhang.steadyrpc.core.cluster;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadStats;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.RoundRobinLoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

final class StubInvocations {

	private StubInvocations() {
	}

	static ClusterInvocation of(List<ServiceInstance> candidates, int retries,
			Function<ServiceInstance, CompletableFuture<Result>> attempt) {
		return new ClusterInvocation(() -> candidates, new RoundRobinLoadBalance(), new LoadStats(), attempt, retries, "test");
	}

	static CompletableFuture<Result> failed(String message) {
		return CompletableFuture.failedFuture(new RuntimeException(message));
	}
}
