package com.feynman.rpc.core.loadbalance;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.testutil.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class P2cLoadBalanceTest {

	@Test
	void emptyListReturnsNull() {
		assertNull(new P2cLoadBalance().select(List.of(), new LoadStats()));
	}

	@Test
	void singleCandidateReturnsItDirectly() {
		ServiceInstance only = Instances.at(1);
		assertSame(only, new P2cLoadBalance().select(List.of(only), new LoadStats()));
	}

	@Test
	void withOnlyTwoCandidatesAlwaysPicksTheLessLoadedOne() {
		// 正好两个节点时，P2C 的"采样两个"退化成"每次都比较全部"——没有随机性需要考虑，
		// 所以这是一个确定性、可靠的断言。
		ServiceInstance busy = Instances.at(1);
		ServiceInstance idle = Instances.at(2);
		LoadStats stats = new LoadStats();
		stats.begin(busy.address());
		stats.begin(busy.address());
		stats.begin(busy.address()); // 3 in flight, idle 保持 0

		P2cLoadBalance lb = new P2cLoadBalance();
		for (int i = 0; i < 20; i++) {
			assertEquals(idle.address(), lb.select(List.of(busy, idle), stats).address());
		}
	}

	@Test
	void statsAreScopedToTheInstancePassedIn() {
		// 负载统计归调用方（RpcClient）所有：另一份统计里的在途数不影响本次选择
		ServiceInstance a = Instances.at(1);
		ServiceInstance b = Instances.at(2);
		LoadStats someoneElses = new LoadStats();
		someoneElses.begin(a.address());
		LoadStats mine = new LoadStats();
		mine.begin(b.address());

		for (int i = 0; i < 20; i++) {
			assertEquals(a.address(), new P2cLoadBalance().select(List.of(a, b), mine).address());
		}
	}
}
