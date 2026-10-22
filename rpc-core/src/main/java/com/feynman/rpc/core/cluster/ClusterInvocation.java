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
 *
 * <p>离群摘除（{@link OutlierDetector}，非 null 时）在这里生效：{@link #candidates()} 返回的已经去掉了处于摘除期的地址，
 * 所以每种 Cluster 拿到的都是过滤后的候选，交给 LoadBalance 的也是；failover 的"排除本次调用失败过的节点"照常在这之上进行。
 */
public final class ClusterInvocation {

	private final Supplier<List<ServiceInstance>> candidates;
	private final LoadBalance loadBalance;
	private final LoadStats loadStats;
	private final Function<ServiceInstance, CompletableFuture<Result>> attempt;
	private final int retries;
	private final String description;
	private final OutlierDetector outlierDetector;

	public ClusterInvocation(Supplier<List<ServiceInstance>> candidates, LoadBalance loadBalance, LoadStats loadStats,
			Function<ServiceInstance, CompletableFuture<Result>> attempt, int retries, String description) {
		this(candidates, loadBalance, loadStats, attempt, retries, description, null);
	}

	/** @param outlierDetector 该服务的离群摘除；null = 不过滤（离群摘除关闭） */
	public ClusterInvocation(Supplier<List<ServiceInstance>> candidates, LoadBalance loadBalance, LoadStats loadStats,
			Function<ServiceInstance, CompletableFuture<Result>> attempt, int retries, String description, OutlierDetector outlierDetector) {
		this.candidates = candidates;
		this.loadBalance = loadBalance;
		this.loadStats = loadStats;
		this.attempt = attempt;
		this.retries = retries;
		this.description = description;
		this.outlierDetector = outlierDetector;
	}

	/** 当前可选的提供者列表（每次调用都重新读取），已去掉处于离群摘除期的地址（全部被摘时退回全部，见 {@link OutlierDetector#filter}）。 */
	public List<ServiceInstance> candidates() {
		List<ServiceInstance> all = candidates.get();
		return outlierDetector == null ? all : outlierDetector.filter(all);
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
