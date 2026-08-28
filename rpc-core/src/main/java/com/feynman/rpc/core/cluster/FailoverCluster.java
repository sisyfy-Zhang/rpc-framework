package com.feynman.rpc.core.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.client.RpcException;
import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.protocol.RpcMessage;

/**
 * 失败后换一个节点重试，最多重试 {@link ClusterInvocation#maxRetries()} 次（{@code @RemoteInvoke}
 * 默认为 0，所以切到这个 Cluster 不会让所有调用都变得可重试——只有显式声明允许重试的才会）。
 */
public class FailoverCluster implements Cluster {

	@Override
	public CompletableFuture<RpcMessage> invoke(ClusterInvocation invocation) {
		return attempt(invocation, new HashSet<>(), invocation.maxRetries());
	}

	private CompletableFuture<RpcMessage> attempt(ClusterInvocation invocation, Set<String> excluded, int retriesLeft) {
		LoadBalance loadBalance = RpcConfig.resolve(ExtensionLoader.getExtensionLoader(LoadBalance.class), RpcConfig.LOADBALANCE_KEY);
		Map<String, Integer> all = invocation.addressWeights();

		Map<String, Integer> candidates = new HashMap<>(all);
		candidates.keySet().removeAll(excluded);
		// 排除完还有得选就从剩下的里面选，全排除了（节点数 <= 已重试次数）就退回全集，好过直接放弃
		String address = loadBalance.select(candidates.isEmpty() ? all : candidates);

		if (address == null) {
			CompletableFuture<RpcMessage> failed = new CompletableFuture<>();
			failed.completeExceptionally(new RpcException("No available provider for: " + invocation.describe()));
			return failed;
		}

		CompletableFuture<RpcMessage> attemptFuture = invocation.attempt(address);
		if (retriesLeft <= 0) {
			return attemptFuture;
		}

		CompletableFuture<RpcMessage> result = new CompletableFuture<>();
		attemptFuture.whenComplete((resp, err) -> {
			if (err == null) {
				result.complete(resp);
				return;
			}
			Set<String> nextExcluded = new HashSet<>(excluded);
			nextExcluded.add(address);
			attempt(invocation, nextExcluded, retriesLeft - 1).whenComplete((retryResp, retryErr) -> {
				if (retryErr == null) {
					result.complete(retryResp);
				} else {
					result.completeExceptionally(retryErr);
				}
			});
		});
		return result;
	}
}
