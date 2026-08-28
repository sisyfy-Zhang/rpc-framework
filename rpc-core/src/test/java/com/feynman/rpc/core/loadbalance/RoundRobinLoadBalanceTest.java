package com.feynman.rpc.core.loadbalance;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoundRobinLoadBalanceTest {

	@Test
	void emptyMapReturnsNull() {
		assertNull(new RoundRobinLoadBalance().select(Map.of()));
	}

	@Test
	void distributesProportionallyToWeight() {
		RoundRobinLoadBalance lb = new RoundRobinLoadBalance();
		Map<String, Integer> weights = new HashMap<>();
		weights.put("a", 1);
		weights.put("b", 3);

		Map<String, AtomicInteger> counts = new HashMap<>();
		counts.put("a", new AtomicInteger());
		counts.put("b", new AtomicInteger());

		int iterations = 400; // 是 (1+3) 的倍数，这样分布结果能精确整除
		for (int i = 0; i < iterations; i++) {
			counts.get(lb.select(weights)).incrementAndGet();
		}

		assertEquals(iterations / 4, counts.get("a").get());
		assertEquals(iterations * 3 / 4, counts.get("b").get());
	}
}
