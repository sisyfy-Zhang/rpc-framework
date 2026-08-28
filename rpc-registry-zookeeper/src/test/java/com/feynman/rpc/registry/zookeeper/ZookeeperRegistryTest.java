package com.feynman.rpc.registry.zookeeper;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.registry.Registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 Curator 内嵌的 {@link TestingServer} 做真实 ZooKeeper 集成测试——同进程、随机端口，
 * 每个测试各自启停。取代了开发早期"手动起一个独立 ZK jar"的做法——后者在本地沙箱环境下
 * 确实存在连接不稳定的问题（已独立验证过，和本项目代码无关）。
 */
class ZookeeperRegistryTest {

	private TestingServer testingServer;
	private ZookeeperRegistry registry;

	@BeforeEach
	void startEmbeddedZookeeper() throws Exception {
		testingServer = new TestingServer(true);
		System.setProperty("rpc.registry.zookeeper.address", testingServer.getConnectString());
		registry = new ZookeeperRegistry();
	}

	@AfterEach
	void stopEmbeddedZookeeper() throws Exception {
		System.clearProperty("rpc.registry.zookeeper.address");
		if (testingServer != null) {
			testingServer.close();
		}
	}

	@Test
	void registerThenGetChildrenSeesTheNode() {
		registry.register("/rpc/127.0.0.1:9000#1");

		List<String> children = registry.getChildren("/rpc");
		assertEquals(1, children.size());
		assertEquals("127.0.0.1:9000#1", children.get(0));
	}

	@Test
	void unregisterRemovesTheNode() {
		registry.register("/rpc/127.0.0.1:9000#1");
		registry.unregister("/rpc/127.0.0.1:9000#1");

		List<String> children = registry.getChildren("/rpc");
		assertTrue(children.isEmpty());
	}

	@Test
	void subscribeFiresImmediatelyAndAgainOnChange() throws Exception {
		ArrayBlockingQueue<List<String>> updates = new ArrayBlockingQueue<>(10);
		registry.subscribe("/rpc", updates::add);

		// 首次推送，即便此时还没有任何注册
		List<String> first = updates.poll(5, TimeUnit.SECONDS);
		assertTrue(first != null && first.isEmpty());

		registry.register("/rpc/127.0.0.1:9001#1");

		List<String> second = updates.poll(5, TimeUnit.SECONDS);
		assertEquals(1, second.size());
		assertEquals("127.0.0.1:9001#1", second.get(0));
	}
}
