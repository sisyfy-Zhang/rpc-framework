package com.feynman.rpc.core.loadbalance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基线/对照组策略：按 weight 把每个地址展开多份后轮询。完全不感知节点实际负载——
 * 存在的意义就是给 {@link P2cLoadBalance} 提供一个朴素的性能对照。
 */
public class RoundRobinLoadBalance implements LoadBalance {

	private final AtomicInteger cursor = new AtomicInteger(0);

	@Override
	public String select(Map<String, Integer> addressWeights) {
		if (addressWeights.isEmpty()) {
			return null;
		}
		List<String> expanded = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : addressWeights.entrySet()) {
			int weight = Math.max(1, entry.getValue());
			for (int i = 0; i < weight; i++) {
				expanded.add(entry.getKey());
			}
		}
		int index = Math.floorMod(cursor.getAndIncrement(), expanded.size());
		return expanded.get(index);
	}
}
