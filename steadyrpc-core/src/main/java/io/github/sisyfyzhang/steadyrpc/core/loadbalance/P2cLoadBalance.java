package io.github.sisyfyzhang.steadyrpc.core.loadbalance;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * Power of Two Choices：按权重随机抽两个候选节点，选当前在途请求数（见 {@link LoadStats}）更少
 * 的那个。经典的 P2C 结论（Mitzenmacher 等）是：比较两次随机采样比单次随机选择的负载分布要好
 * 得多，且不需要维护一个全局"最小负载节点"这种有协调成本的状态——每次调用只需本地读一次
 * {@link LoadStats}。这是本项目里唯一真正花心思打磨的负载均衡策略，而不是照搬 Dubbo 全部策略。
 */
public class P2cLoadBalance implements LoadBalance {

	@Override
	public ServiceInstance select(List<ServiceInstance> candidates, LoadStats stats) {
		if (candidates.isEmpty()) {
			return null;
		}
		if (candidates.size() == 1) {
			return candidates.get(0);
		}

		ThreadLocalRandom random = ThreadLocalRandom.current();
		ServiceInstance candidateA = weightedPick(candidates, random);
		ServiceInstance candidateB = weightedPick(candidates, random);
		int guard = 0;
		while (candidateB.address().equals(candidateA.address()) && guard++ < 10) {
			candidateB = weightedPick(candidates, random);
		}

		return stats.active(candidateA.address()) <= stats.active(candidateB.address()) ? candidateA : candidateB;
	}

	private static ServiceInstance weightedPick(List<ServiceInstance> candidates, ThreadLocalRandom random) {
		int totalWeight = 0;
		for (ServiceInstance instance : candidates) {
			totalWeight += instance.weight();
		}
		int r = random.nextInt(totalWeight);
		for (ServiceInstance instance : candidates) {
			r -= instance.weight();
			if (r < 0) {
				return instance;
			}
		}
		return candidates.get(candidates.size() - 1);
	}
}
