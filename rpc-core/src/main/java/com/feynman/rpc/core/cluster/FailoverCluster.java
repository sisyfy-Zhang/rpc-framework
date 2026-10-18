package com.feynman.rpc.core.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 失败后换一个节点重试，最多重试 {@link ClusterInvocation#retries()} 次（ReferenceOptions 默认为 0，
 * 所以切到这个 Cluster 不会让所有调用都变得可重试——只有显式声明允许重试的才会）。
 *
 * <p>"失败"指 attempt 的 future 异常完成：传输失败或服务端 SERVER_OVERLOADED（过载 / 停机拒绝）。失败过的节点
 * 在本次调用的后续重试中被排除——刚拒绝过的节点马上再试大概率还是拒绝。每次重试都用本次调用剩余的 deadline，
 * 预算耗尽时 attempt 直接以 Result 形式返回超时，不再继续换节点。
 */
public class FailoverCluster implements Cluster {

	@Override
	public CompletableFuture<Result> invoke(ClusterInvocation invocation) {
		return attempt(invocation, new HashSet<>(), invocation.retries());
	}

	private CompletableFuture<Result> attempt(ClusterInvocation invocation, Set<String> excluded, int retriesLeft) {
		List<ServiceInstance> all = invocation.candidates();
		List<ServiceInstance> remaining = new ArrayList<>(all.size());
		for (ServiceInstance instance : all) {
			if (!excluded.contains(instance.address())) {
				remaining.add(instance);
			}
		}
		// 排除完还有得选就从剩下的里面选，全排除了（节点数 <= 已重试次数）就退回全集，好过直接放弃
		ServiceInstance instance = invocation.select(remaining.isEmpty() ? all : remaining);
		if (instance == null) {
			return CompletableFuture.failedFuture(new RpcException("No available provider for: " + invocation.describe()));
		}

		CompletableFuture<Result> attemptFuture = invocation.attempt(instance);
		if (retriesLeft <= 0) {
			return attemptFuture;
		}

		CompletableFuture<Result> result = new CompletableFuture<>();
		attemptFuture.whenComplete((resp, err) -> {
			if (err == null) {
				result.complete(resp);
				return;
			}
			Set<String> nextExcluded = new HashSet<>(excluded);
			nextExcluded.add(instance.address());
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
