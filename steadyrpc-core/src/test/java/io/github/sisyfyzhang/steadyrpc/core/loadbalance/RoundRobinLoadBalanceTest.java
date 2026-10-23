package io.github.sisyfyzhang.steadyrpc.core.loadbalance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.testutil.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoundRobinLoadBalanceTest {

	@Test
	void emptyListReturnsNull() {
		assertNull(new RoundRobinLoadBalance().select(List.of(), new LoadStats()));
	}

	@Test
	void distributesProportionallyToWeight() {
		RoundRobinLoadBalance lb = new RoundRobinLoadBalance();
		List<ServiceInstance> candidates = List.of(Instances.at(1, 1), Instances.at(2, 3));
		LoadStats stats = new LoadStats();

		Map<Integer, Integer> counts = new HashMap<>();
		int iterations = 400; // 是 (1+3) 的倍数，这样分布结果能精确整除
		for (int i = 0; i < iterations; i++) {
			counts.merge(lb.select(candidates, stats).port(), 1, Integer::sum);
		}

		assertEquals(iterations / 4, counts.get(1));
		assertEquals(iterations * 3 / 4, counts.get(2));
	}
}
