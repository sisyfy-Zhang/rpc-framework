package io.github.sisyfyzhang.steadyrpc.core.testutil;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadStats;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.RoundRobinLoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 统计选址次数的 LoadBalance（SPI 名 {@value #NAME}），委托给轮询。Cluster 每发起一次尝试（含重试）恰好选址一次，
 * 所以选址次数 = 尝试次数——这是从客户端外部观测"有没有重试"的唯一入口（Cluster 对 Filter 不可见）。
 * 实例由每个 RpcClient 各自 new，拿不到引用，所以计数是静态的；仅测试使用，用前 {@link #reset()}。
 */
public class CountingLoadBalance implements LoadBalance {

	public static final String NAME = "counting";

	private static final AtomicInteger SELECTIONS = new AtomicInteger();

	private final RoundRobinLoadBalance delegate = new RoundRobinLoadBalance();

	public static void reset() {
		SELECTIONS.set(0);
	}

	public static int selections() {
		return SELECTIONS.get();
	}

	@Override
	public ServiceInstance select(List<ServiceInstance> candidates, LoadStats stats) {
		SELECTIONS.incrementAndGet();
		return delegate.select(candidates, stats);
	}
}
