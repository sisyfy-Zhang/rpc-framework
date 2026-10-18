package com.feynman.rpc.core.cluster;

import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.registry.ServiceInstance;

/** 默认策略：选一个节点，尝试一次，成败原样传播，从不重试。 */
public class FailfastCluster implements Cluster {

	@Override
	public CompletableFuture<Result> invoke(ClusterInvocation invocation) {
		ServiceInstance instance = invocation.select(invocation.candidates());
		if (instance == null) {
			return CompletableFuture.failedFuture(new RpcException("No available provider for: " + invocation.describe()));
		}
		return invocation.attempt(instance);
	}
}
