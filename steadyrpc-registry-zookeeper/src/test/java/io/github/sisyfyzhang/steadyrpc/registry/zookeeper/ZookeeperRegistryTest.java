package io.github.sisyfyzhang.steadyrpc.registry.zookeeper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.extension.ExtensionLoader;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 Curator 内嵌的 {@link TestingServer} 做真实 ZooKeeper 集成测试——同进程、随机端口，
 * 每个测试各自启停，不依赖本机已起的 ZK。地址通过 {@link RegistryConfig} 传入，不再经 System 属性。
 */
class ZookeeperRegistryTest {

	private static final ServiceKey KEY = new ServiceKey("com.example.UserFacade", "1.0", "default");

	private TestingServer testingServer;
	private Registry registry;
	private CuratorFramework inspector;

	@BeforeEach
	void startEmbeddedZookeeper() throws Exception {
		testingServer = new TestingServer(true);
		registry = ExtensionLoader.getExtensionLoader(RegistryFactory.class)
				.getExtension("zookeeper")
				.create(RegistryConfig.zookeeper(testingServer.getConnectString()));
		inspector = CuratorFrameworkFactory.newClient(testingServer.getConnectString(), new RetryOneTime(100));
		inspector.start();
		inspector.blockUntilConnected(5, TimeUnit.SECONDS);
	}

	@AfterEach
	void stopEmbeddedZookeeper() throws Exception {
		if (registry != null) registry.close();
		if (inspector != null) inspector.close();
		if (testingServer != null) testingServer.close();
	}

	private static ServiceInstance instance(int port, int weight) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, String.valueOf(weight));
		metadata.put(InstanceMetadata.PROTOCOL, "1");
		metadata.put(InstanceMetadata.TIMESTAMP, "1700000000000");
		metadata.put(InstanceMetadata.PID, "4242");
		return new ServiceInstance(KEY, "127.0.0.1", port, metadata);
	}

	@Test
	void factoryIsDiscoveredThroughSpi() {
		assertInstanceOf(ZookeeperRegistry.class, registry);
	}

	/**
	 * 默认参数收紧（D-010）：会话超时 30s（ZK 不可达时下线节点的残留窗口）、建连超时 5s、重试 500ms 起 3 次。
	 * 会话超时断言的是与服务端<b>协商后</b>的值。TestingServer 默认 tickTime 1000ms，服务端上限 20 × tickTime = 20s，
	 * 会把 30s 压到 20s；这里单独起一个 tickTime 2000ms（上限 40s）的服务端，30s 才能原样生效。
	 */
	@Test
	void tightenedDefaultsAreInEffect() throws Exception {
		try (TestingServer server = new TestingServer(new org.apache.curator.test.InstanceSpec(null, -1, -1, -1, true, -1, 2000, -1), true);
				ZookeeperRegistry zk = new ZookeeperRegistry(RegistryConfig.zookeeper(server.getConnectString()))) {
			zk.register(instance(9001, 1)); // 等到真正连上，协商出会话超时
			CuratorFramework curator = zk.curator();
			assertEquals(5000, curator.getZookeeperClient().getConnectionTimeoutMs());
			assertEquals(30000, curator.getZookeeperClient().getLastNegotiatedSessionTimeoutMs());
			org.apache.curator.retry.ExponentialBackoffRetry retry = assertInstanceOf(
					org.apache.curator.retry.ExponentialBackoffRetry.class, curator.getZookeeperClient().getRetryPolicy());
			assertEquals(500, retry.getBaseSleepTimeMs());
			assertEquals(3, retry.getN());
		}
	}

	@Test
	void registerCreatesEphemeralNodeAtTheAgreedPathWithMetadataAsData() throws Exception {
		registry.register(instance(9000, 3));

		String path = "/rpc/com.example.UserFacade:1.0:default/providers/127.0.0.1:9000";
		assertNotNull(inspector.checkExists().forPath(path), "node missing at " + path);
		assertTrue(inspector.checkExists().forPath(path).getEphemeralOwner() != 0, "node must be ephemeral");
		assertEquals("weight=3&proto=1&ts=1700000000000&pid=4242",
				new String(inspector.getData().forPath(path), StandardCharsets.UTF_8));
	}

	@Test
	void unregisterRemovesTheNodeAndIsIdempotent() throws Exception {
		ServiceInstance instance = instance(9000, 1);
		registry.register(instance);
		registry.unregister(instance);
		registry.unregister(instance);

		assertTrue(inspector.getChildren().forPath(ZookeeperRegistry.providersPath(KEY)).isEmpty());
	}

	@Test
	void subscribePushesImmediatelyThenOnEveryChangeWithMetadata() throws Exception {
		ArrayBlockingQueue<List<ServiceInstance>> updates = new ArrayBlockingQueue<>(10);
		registry.subscribe(KEY, updates::add);

		// 首次推送，即便此时还没有任何注册
		List<ServiceInstance> first = updates.poll(5, TimeUnit.SECONDS);
		assertNotNull(first);
		assertTrue(first.isEmpty());

		registry.register(instance(9001, 5));

		List<ServiceInstance> second = updates.poll(5, TimeUnit.SECONDS);
		assertNotNull(second);
		assertEquals(1, second.size());
		assertEquals("127.0.0.1:9001", second.get(0).address());
		assertEquals(5, second.get(0).weight());
		assertEquals(KEY, second.get(0).key());
	}

	@Test
	void subscriptionsAreScopedPerServiceKey() throws Exception {
		ServiceKey other = new ServiceKey("com.example.Other", "", "default");
		ArrayBlockingQueue<List<ServiceInstance>> updates = new ArrayBlockingQueue<>(10);
		registry.subscribe(other, updates::add);
		updates.poll(5, TimeUnit.SECONDS);

		registry.register(instance(9002, 1)); // 注册的是 KEY，不是 other

		assertNull(updates.poll(500, TimeUnit.MILLISECONDS), "a provider of another service must not be pushed");
	}

	@Test
	void unsubscribeStopsCallbacks() throws Exception {
		ArrayBlockingQueue<List<ServiceInstance>> updates = new ArrayBlockingQueue<>(10);
		InstanceListener listener = updates::add;
		registry.subscribe(KEY, listener);
		updates.poll(5, TimeUnit.SECONDS);

		registry.unsubscribe(KEY, listener);
		registry.register(instance(9003, 1));

		assertNull(updates.poll(500, TimeUnit.MILLISECONDS));
	}

	@Test
	void closingTheRegistryRemovesItsEphemeralNodes() throws Exception {
		registry.register(instance(9004, 1));
		String providers = ZookeeperRegistry.providersPath(KEY);
		assertEquals(1, inspector.getChildren().forPath(providers).size());

		registry.close();
		registry = null;

		await().atMost(5, TimeUnit.SECONDS).until(() -> inspector.getChildren().forPath(providers).isEmpty());
	}
}
