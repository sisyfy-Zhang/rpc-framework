package com.feynman.rpc.core.cluster;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.loadbalance.RoundRobinLoadBalance;
import com.feynman.rpc.core.registry.ServiceInstance;

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
