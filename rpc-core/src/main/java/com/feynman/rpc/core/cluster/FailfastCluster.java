package com.feynman.rpc.core.cluster;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.client.RpcException;
import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.protocol.RpcMessage;

/** 默认策略：选一个节点，尝试一次，成败原样传播，从不重试。 */
public class FailfastCluster implements Cluster {

	@Override
	public CompletableFuture<RpcMessage> invoke(ClusterInvocation invocation) {
		Map<String, Integer> addressWeights = invocation.addressWeights();
		LoadBalance loadBalance = RpcConfig.resolve(ExtensionLoader.getExtensionLoader(LoadBalance.class), RpcConfig.LOADBALANCE_KEY);
		String address = loadBalance.select(addressWeights);

		if (address == null) {
			CompletableFuture<RpcMessage> failed = new CompletableFuture<>();
			failed.completeExceptionally(new RpcException("No available provider for: " + invocation.describe()));
			return failed;
		}
		return invocation.attempt(address);
	}
}
