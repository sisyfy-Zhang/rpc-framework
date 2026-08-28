package com.feynman.rpc.registry.nacos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 纯解析逻辑测试，不需要真实 Nacos 服务——Nacos 没有类似 curator-test {@code TestingServer}
 * 那种轻量级内嵌测试服务器，所以 {@code NacosRegistry}/{@code NamingService} 的真实交互
 * 按本项目的测试策略留给手动/可选的 Docker 验证；这里覆盖的是真正属于本项目自己代码的部分
 * （路径 <-> service/instance 的映射逻辑），有完整的自动化测试。
 */
class NacosPathTest {

	@Test
	void parsesFullPathIntoServiceHostPortWeight() {
		NacosPath parsed = NacosPath.parse("/rpc/192.168.1.1:9000#3");

		assertEquals("rpc", parsed.serviceName);
		assertEquals("192.168.1.1", parsed.host);
		assertEquals(9000, parsed.port);
		assertEquals(3, parsed.weight);
	}

	@Test
	void defaultsWeightToOneWhenOmitted() {
		NacosPath parsed = NacosPath.parse("/rpc/192.168.1.1:9000");
		assertEquals(1, parsed.weight);
	}

	@Test
	void rejectsPathWithoutAnySlash() {
		assertThrows(IllegalArgumentException.class, () -> NacosPath.parse("192.168.1.1:9000#1"));
	}

	@Test
	void rejectsNodeWithoutPort() {
		assertThrows(IllegalArgumentException.class, () -> NacosPath.parse("/rpc/192.168.1.1"));
	}

	@Test
	void serviceNameStripsLeadingSlash() {
		assertEquals("rpc", NacosPath.serviceNameOf("/rpc"));
	}

	@Test
	void serviceNameFallsBackWhenBasePathIsRoot() {
		assertEquals("rpc-default", NacosPath.serviceNameOf("/"));
	}

	@Test
	void childNameRoundTripsBackToTheSameShapeZookeeperUses() {
		assertEquals("192.168.1.1:9000#3", NacosPath.toChildName("192.168.1.1", 9000, 3));
	}
}
