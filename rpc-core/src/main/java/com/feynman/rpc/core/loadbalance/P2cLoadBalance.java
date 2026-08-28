package com.feynman.rpc.core.loadbalance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Power of Two Choices：按权重随机抽两个候选地址，选当前在途请求数（见 {@link LoadStats}）更少
 * 的那个。经典的 P2C 结论（Mitzenmacher 等）是：比较两次随机采样比单次随机选择的负载分布要好
 * 得多，且不需要维护一个全局"最小负载节点"这种有协调成本的状态——每次调用只需本地读一次
 * {@link LoadStats}。这是本项目里唯一真正花心思打磨的负载均衡策略，而不是照搬 Dubbo 全部策略。
 */
public class P2cLoadBalance implements LoadBalance {

	@Override
	public String select(Map<String, Integer> addressWeights) {
		if (addressWeights.isEmpty()) {
			return null;
		}
		List<String> addresses = new ArrayList<>(addressWeights.keySet());
		if (addresses.size() == 1) {
			return addresses.get(0);
		}

		ThreadLocalRandom random = ThreadLocalRandom.current();
		String candidateA = weightedPick(addressWeights, addresses, random);
		String candidateB = weightedPick(addressWeights, addresses, random);
		int guard = 0;
		while (candidateB.equals(candidateA) && guard++ < 10) {
			candidateB = weightedPick(addressWeights, addresses, random);
		}

		return LoadStats.active(candidateA) <= LoadStats.active(candidateB) ? candidateA : candidateB;
	}

	private static String weightedPick(Map<String, Integer> addressWeights, List<String> addresses, ThreadLocalRandom random) {
		int totalWeight = 0;
		for (String addr : addresses) {
			totalWeight += Math.max(1, addressWeights.get(addr));
		}
		int r = random.nextInt(totalWeight);
		int cumulative = 0;
		for (String addr : addresses) {
			cumulative += Math.max(1, addressWeights.get(addr));
			if (r < cumulative) {
				return addr;
			}
		}
		return addresses.get(addresses.size() - 1);
	}
}
