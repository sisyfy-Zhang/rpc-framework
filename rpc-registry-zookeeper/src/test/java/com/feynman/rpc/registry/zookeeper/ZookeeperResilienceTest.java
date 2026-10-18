package com.feynman.rpc.registry.zookeeper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.registry.tck.PushRecorder;

/**
 * A0-3 与 ZooKeeper 实现特有的韧性行为：会话过期后重注册、服务端重启后订阅不死、构造不阻塞、线程按实例命名。
 * 通用语义（首次推送、close 等）在 {@link ZookeeperRegistryContractTest}。
 */
class ZookeeperResilienceTest {

	private static final ServiceKey KEY = new ServiceKey("com.example.Inventory", "1.0", "default");

	private TestingServer server;
	private CuratorFramework inspector;
	private final List<ZookeeperRegistry> registries = new ArrayList<>();

	@BeforeEach
	void start() throws Exception {
		server = new TestingServer(true);
		inspector = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100));
		inspector.start();
		assertTrue(inspector.blockUntilConnected(5, TimeUnit.SECONDS));
	}

	@AfterEach
	void stop() throws Exception {
		registries.forEach(ZookeeperRegistry::close);
		if (inspector != null) inspector.close();
		if (server != null) server.close();
	}

	private ZookeeperRegistry registry(String address) {
		ZookeeperRegistry registry = new ZookeeperRegistry(RegistryConfig.zookeeper(address));
		registries.add(registry);
		return registry;
	}

	private ZookeeperRegistry registry() {
		return registry(server.getConnectString());
	}

	private static ServiceInstance instance(int port) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, "3");
		metadata.put(InstanceMetadata.PROTOCOL, "2");
		metadata.put(InstanceMetadata.TIMESTAMP, "1700000000000");
		metadata.put(InstanceMetadata.PID, "4242");
		return new ServiceInstance(KEY, "127.0.0.1", port, metadata);
	}

	private Stat stat(ServiceInstance instance) throws Exception {
		return inspector.checkExists().forPath(ZookeeperRegistry.instancePath(instance));
	}

	private String data(ServiceInstance instance) throws Exception {
		return new String(inspector.getData().forPath(ZookeeperRegistry.instancePath(instance)), StandardCharsets.UTF_8);
	}

	private static long sessionOf(ZookeeperRegistry registry) throws Exception {
		return registry.curator().getZookeeperClient().getZooKeeper().getSessionId();
	}

	/**
	 * A0-3：提供者会话被杀后 10s 内节点在新会话上重新出现、元数据一致；另一个 Registry 的订阅在过期前后都看到正确列表。
	 *
	 * <p>注意 curator-test 5.x 的 {@code KillSession.kill} 只在<b>客户端</b>注入会话过期（{@code injectSessionExpiration}）：
	 * 服务端的旧会话要到会话超时才过期，旧节点在此期间仍然存在。所以只断言"节点存在"会空转通过——
	 * 这里断言节点的 ephemeralOwner 变成了新会话 ID，证明它确实是重注册出来的，而不是旧会话的残留。
	 */
	@Test
	void killedSessionIsReRegisteredWithin10s_andAnotherSubscriberKeepsSeeingTheInstance() throws Exception {
		ServiceInstance instance = instance(9101);
		ZookeeperRegistry provider = registry();
		ZookeeperRegistry consumer = registry();
		PushRecorder recorder = new PushRecorder("consumer");
		consumer.subscribe(KEY, recorder);

		provider.register(instance);
		recorder.awaitLatest(Duration.ofSeconds(5), "sees instance before expiry",
				list -> PushRecorder.containsAddress(list, instance.address()));
		long oldSession = sessionOf(provider);
		assertEquals(oldSession, stat(instance).getEphemeralOwner());
		String metadataBefore = data(instance);

		long killedAt = System.nanoTime();
		KillSession.kill(provider.curator().getZookeeperClient().getZooKeeper());

		await("node re-created on a new session").atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
				.until(() -> {
					Stat stat = stat(instance);
					return stat != null && stat.getEphemeralOwner() != oldSession && stat.getEphemeralOwner() == sessionOf(provider);
				});
		long reRegisteredMs = (System.nanoTime() - killedAt) / 1_000_000;
		long newSession = sessionOf(provider);
		assertNotEquals(oldSession, newSession);
		assertEquals(metadataBefore, data(instance), "metadata must survive re-registration");

		List<ServiceInstance> latest = recorder.awaitLatest(Duration.ofSeconds(5), "still sees instance after expiry",
				list -> PushRecorder.containsAddress(list, instance.address()));
		assertEquals(instance.metadata(), PushRecorder.metadataOf(latest, instance.address()));
		System.out.printf("[A0-3] session 0x%x killed; node re-created on session 0x%x after %dms; data=%s; consumer pushes=%s%n",
				oldSession, newSession, reRegisteredMs, data(instance), recorder.all());

		// 重注册之后的变化仍然能推到订阅者：订阅与注册都活着
		ServiceInstance second = instance(9102);
		provider.register(second);
		recorder.awaitLatest(Duration.ofSeconds(5), "sees the second instance",
				list -> PushRecorder.containsAddress(list, second.address()) && PushRecorder.containsAddress(list, instance.address()));
	}

	/** 订阅方自己的会话被杀：CuratorCache 在新会话上重建，之后的变化照样推送。 */
	@Test
	void subscriberSessionExpiryDoesNotKillTheSubscription() throws Exception {
		ZookeeperRegistry provider = registry();
		ZookeeperRegistry consumer = registry();
		PushRecorder recorder = new PushRecorder("consumer");
		consumer.subscribe(KEY, recorder);
		ServiceInstance first = instance(9201);
		provider.register(first);
		recorder.awaitLatest(Duration.ofSeconds(5), "sees first", list -> PushRecorder.containsAddress(list, first.address()));

		long oldSession = sessionOf(consumer);
		KillSession.kill(consumer.curator().getZookeeperClient().getZooKeeper());
		await().atMost(10, TimeUnit.SECONDS).until(() -> consumer.curator().getZookeeperClient().isConnected()
				&& sessionOf(consumer) != oldSession);

		ServiceInstance second = instance(9202);
		provider.register(second);
		recorder.awaitLatest(Duration.ofSeconds(10), "sees second after own session expiry",
				list -> PushRecorder.containsAddress(list, second.address()));
		provider.unregister(first);
		recorder.awaitLatest(Duration.ofSeconds(10), "sees first removed after own session expiry",
				list -> !PushRecorder.containsAddress(list, first.address()) && PushRecorder.containsAddress(list, second.address()));
	}

	/** 服务端重启（同端口、同数据目录）后订阅仍收到后续变化：watcher 不会因为一次断连而静默死亡。 */
	@Test
	void subscriptionSurvivesServerRestart() throws Exception {
		ZookeeperRegistry provider = registry();
		ZookeeperRegistry consumer = registry();
		PushRecorder recorder = new PushRecorder("consumer");
		consumer.subscribe(KEY, recorder);
		ServiceInstance before = instance(9301);
		provider.register(before);
		recorder.awaitLatest(Duration.ofSeconds(5), "sees instance before restart",
				list -> PushRecorder.containsAddress(list, before.address()));

		long restartAt = System.nanoTime();
		server.restart();
		await("both registries reconnect").atMost(20, TimeUnit.SECONDS)
				.until(() -> provider.curator().getZookeeperClient().isConnected() && consumer.curator().getZookeeperClient().isConnected());
		System.out.printf("[A0-3] TestingServer restarted, both registries reconnected after %dms%n",
				(System.nanoTime() - restartAt) / 1_000_000);

		ServiceInstance after = instance(9302);
		await("register after restart").atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
			provider.register(after);
			return true;
		});
		List<ServiceInstance> latest = recorder.awaitLatest(Duration.ofSeconds(10), "sees change made after restart",
				list -> PushRecorder.containsAddress(list, after.address()) && PushRecorder.containsAddress(list, before.address()));
		System.out.println("[A0-3] consumer after restart: " + latest.stream().map(ServiceInstance::address).toList());

		provider.unregister(after);
		recorder.awaitLatest(Duration.ofSeconds(10), "sees removal made after restart",
				list -> !PushRecorder.containsAddress(list, after.address()));
	}

	/** 服务端停一段时间再起（会话未过期）：订阅者在断连期间不收到空列表，恢复后列表仍正确。 */
	@Test
	void disconnectionDoesNotPushAnEmptyList() throws Exception {
		ZookeeperRegistry provider = registry();
		ZookeeperRegistry consumer = registry();
		PushRecorder recorder = new PushRecorder("consumer");
		consumer.subscribe(KEY, recorder);
		ServiceInstance instance = instance(9401);
		provider.register(instance);
		recorder.awaitLatest(Duration.ofSeconds(5), "sees instance", list -> PushRecorder.containsAddress(list, instance.address()));
		int pushesBefore = recorder.count();

		server.stop();
		Thread.sleep(2000);
		server.restart();
		await().atMost(20, TimeUnit.SECONDS).until(() -> consumer.curator().getZookeeperClient().isConnected());
		Thread.sleep(500);

		for (List<ServiceInstance> push : recorder.all().subList(pushesBefore, recorder.count())) {
			assertTrue(PushRecorder.containsAddress(push, instance.address()), "pushed a list without the live instance: " + recorder);
		}
		assertTrue(PushRecorder.containsAddress(recorder.latest(), instance.address()));
	}

	/** 构造不等连接：不可达地址上 new 立即返回；register 在 connect-wait-ms 内抛 RegistryException；subscribe 不推空列表。 */
	@Test
	void unreachableServer_constructorReturnsImmediately_registerFailsFast_subscribeDoesNotPushEmpty() throws Exception {
		int port = ZookeeperRegistryContractTest.unusedPort();
		long start = System.nanoTime();
		ZookeeperRegistry registry = registry("127.0.0.1:" + port);
		long constructMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(constructMs < 500, "constructor blocked " + constructMs + "ms");

		start = System.nanoTime();
		assertThrows(RegistryException.class, () -> registry.register(instance(9501)));
		long registerMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(registerMs < 2500, "register blocked " + registerMs + "ms");

		PushRecorder recorder = new PushRecorder("consumer");
		start = System.nanoTime();
		registry.subscribe(KEY, recorder);
		long subscribeMs = (System.nanoTime() - start) / 1_000_000;
		assertTrue(subscribeMs < 2500, "subscribe blocked " + subscribeMs + "ms");
		assertNull(recorder.latest(), "must not push an empty list while it cannot know the providers");
		System.out.printf("[ZK unreachable] construct=%dms, register failed after %dms, subscribe returned after %dms without pushing%n",
				constructMs, registerMs, subscribeMs);

		// 后端在这个端口上出现：订阅补推，register 成功
		try (TestingServer late = new TestingServer(port, true)) {
			recorder.awaitLatest(Duration.ofSeconds(15), "catches up once the server appears", list -> true);
			ServiceInstance instance = instance(9502);
			await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
				registry.register(instance);
				return true;
			});
			recorder.awaitLatest(Duration.ofSeconds(5), "sees instance", list -> PushRecorder.containsAddress(list, instance.address()));
			registry.close();
		}
	}

	/** 同一 JVM 两个实例的线程按 instanceId 区分；close 后 5s 内全部结束。 */
	@Test
	void threadsAreNamedPerInstanceAndEndAfterClose() throws Exception {
		ZookeeperRegistry a = registry();
		ZookeeperRegistry b = registry();
		a.subscribe(KEY, list -> { });
		b.register(instance(9601));
		Set<String> threadsA = threads(a.threadNamePrefix());
		Set<String> threadsB = threads(b.threadNamePrefix());
		System.out.println("[threads] A=" + threadsA + " B=" + threadsB);

		assertNotEquals(a.threadNamePrefix(), b.threadNamePrefix());
		assertFalse(threadsA.isEmpty());
		assertFalse(threadsB.isEmpty());
		assertTrue(threadsA.stream().noneMatch(threadsB::contains));
		// ZooKeeper 客户端自己的线程也带上了实例前缀
		assertTrue(threadsA.stream().anyMatch(n -> n.contains("SendThread")), threadsA.toString());
		assertTrue(threadsA.stream().anyMatch(n -> n.contains("EventThread")), threadsA.toString());
		// Curator 线程 rpc-registry-zk-{id}-{n}
		assertTrue(threadsA.stream().anyMatch(n -> n.matches("rpc-registry-zk-\\d+-\\d+")), threadsA.toString());

		a.close();
		b.close();
		await().atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
				.until(() -> threads(a.threadNamePrefix()).isEmpty() && threads(b.threadNamePrefix()).isEmpty());
	}

	private static Set<String> threads(String prefix) {
		Set<String> names = new TreeSet<>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && thread.getName().startsWith(prefix)) {
				names.add(thread.getName());
			}
		}
		return names;
	}

	/** 同地址再次注册只改数据、不删重建（订阅者不会看到一次"消失又出现"）。 */
	@Test
	void reRegisterOnTheSameSessionUpdatesDataInPlace() throws Exception {
		ZookeeperRegistry provider = registry();
		ServiceInstance v1 = instance(9701);
		provider.register(v1);
		long czxid = stat(v1).getCzxid();

		ServiceInstance v2 = new ServiceInstance(KEY, "127.0.0.1", 9701, Map.of(InstanceMetadata.WEIGHT, "9"));
		provider.register(v2);

		Stat stat = stat(v2);
		assertNotNull(stat);
		assertEquals(czxid, stat.getCzxid(), "node must be updated in place, not deleted and re-created");
		assertEquals("weight=9", data(v2));
	}
}
