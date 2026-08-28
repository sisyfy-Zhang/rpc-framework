package com.feynman.rpc.core.loadbalance;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class P2cLoadBalanceTest {

	@Test
	void emptyMapReturnsNull() {
		assertNull(new P2cLoadBalance().select(Map.of()));
	}

	@Test
	void singleAddressReturnsItDirectly() {
		assertEquals("only", new P2cLoadBalance().select(Map.of("only", 1)));
	}

	@Test
	void withOnlyTwoCandidatesAlwaysPicksTheLessLoadedOne() {
		// 正好两个节点时，P2C 的"采样两个"退化成"每次都比较全部"——没有随机性需要考虑，
		// 所以这是一个确定性、可靠的断言。
		LoadStats.begin("p2c-busy");
		LoadStats.begin("p2c-busy");
		LoadStats.begin("p2c-busy"); // 3 in flight, "p2c-idle" stays at its default of 0

		P2cLoadBalance lb = new P2cLoadBalance();
		Map<String, Integer> weights = Map.of("p2c-busy", 1, "p2c-idle", 1);

		for (int i = 0; i < 20; i++) {
			assertEquals("p2c-idle", lb.select(weights));
		}
	}
}
