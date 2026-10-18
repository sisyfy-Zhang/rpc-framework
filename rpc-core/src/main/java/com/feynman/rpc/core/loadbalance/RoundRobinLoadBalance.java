package com.feynman.rpc.core.loadbalance;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 基线/对照组策略：按 weight 加权轮询。完全不感知节点实际负载——
 * 存在的意义就是给 {@link P2cLoadBalance} 提供一个朴素的性能对照。
 */
public class RoundRobinLoadBalance implements LoadBalance {

	private final AtomicInteger cursor = new AtomicInteger(0);

	@Override
	public ServiceInstance select(List<ServiceInstance> candidates, LoadStats stats) {
		if (candidates.isEmpty()) {
			return null;
		}
		int totalWeight = 0;
		for (ServiceInstance instance : candidates) {
			totalWeight += instance.weight();
		}
		// 不再像旧实现那样每次把地址按权重展开成一个新列表：按游标在累计权重上定位，结果相同、不分配内存
		int position = Math.floorMod(cursor.getAndIncrement(), totalWeight);
		for (ServiceInstance instance : candidates) {
			position -= instance.weight();
			if (position < 0) {
				return instance;
			}
		}
		return candidates.get(candidates.size() - 1);
	}
}
