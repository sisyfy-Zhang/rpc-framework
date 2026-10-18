package com.feynman.rpc.core.cluster;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * {@link Cluster} 策略发起一次调用（以及重试时重新发起）所需要的全部输入，全部由 RpcClient 显式传入：
 * Cluster 实现不再从任何全局配置里解析 LoadBalance，也不自己去读负载统计。
 *
 * <p>候选列表是 {@link Supplier}：重试（尤其是 failback 的延迟重试）时应读取那一刻的最新列表，而不是首次调用时的快照。
 */
public final class ClusterInvocation {

	private final Supplier<List<ServiceInstance>> candidates;
	private final LoadBalance loadBalance;
	private final LoadStats loadStats;
	private final Function<ServiceInstance, CompletableFuture<Result>> attempt;
	private final int retries;
	private final String description;

	public ClusterInvocation(Supplier<List<ServiceInstance>> candidates, LoadBalance loadBalance, LoadStats loadStats,
			Function<ServiceInstance, CompletableFuture<Result>> attempt, int retries, String description) {
		this.candidates = candidates;
		this.loadBalance = loadBalance;
		this.loadStats = loadStats;
		this.attempt = attempt;
		this.retries = retries;
		this.description = description;
	}

	/** 当前可选的提供者列表（每次调用都重新读取）。 */
	public List<ServiceInstance> candidates() {
		return candidates.get();
	}

	public LoadBalance loadBalance() {
		return loadBalance;
	}

	public LoadStats loadStats() {
		return loadStats;
	}

	/** 用本次调用的 LoadBalance 与统计从 {@code from} 中选一个节点；为空返回 null。 */
	public ServiceInstance select(List<ServiceInstance> from) {
		return loadBalance.select(from, loadStats);
	}

	/** 向指定节点发送一次请求；不负责选址也不负责重试。 */
	public CompletableFuture<Result> attempt(ServiceInstance instance) {
		return attempt.apply(instance);
	}

	/** 首次之外允许的额外重试次数（0 = 只试一次），来自 ReferenceOptions.retries()。 */
	public int retries() {
		return retries;
	}

	/** 供错误信息/日志使用。 */
	public String describe() {
		return description;
	}
}
