package com.feynman.rpc.it;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.registry.zookeeper.ZookeeperRegistryFactory;

/**
 * 注册中心不可达时的启动行为：
 * <ul>
 *   <li>RpcServer 配一个没有监听的 ZK 地址：start() 1 秒内返回、端口可以被直连调用；随后在那个端口上起 ZK，
 *       后台注册在 15 秒内完成（{@code registered()}）。</li>
 *   <li>RpcClient 配一个没有监听的 ZK 地址：构造与 reference() 都不阻塞（ZK 实现的 subscribe 最多等 connect-wait-ms），
 *       期间调用以"无可用提供者"快速失败；ZK 出现后订阅补推，调用恢复。</li>
 * </ul>
 */
class UnreachableRegistryTest {

	public interface Echo {
		String echo(String s);
	}

	private static int unusedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	void serverStartDoesNotWaitForAnUnreachableRegistry_andRegistersOnceItAppears() throws Exception {
		int zkPort = unusedPort();
		RpcServer server = RpcServer.builder().port(0).host("127.0.0.1")
				.registry(RegistryConfig.zookeeper("127.0.0.1:" + zkPort)).build();
		server.export(Echo.class, s -> "echo:" + s);

		long start = System.nanoTime();
		server.start();
		long startMillis = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[unreachable] RpcServer.start() returned in %dms with registry 127.0.0.1:%d unreachable%n", startMillis, zkPort);
		assertTrue(startMillis < 1000, "start() blocked " + startMillis + "ms on an unreachable registry");
		assertFalse(server.isRegistered());

		// 端口已经在服务：客户端走另一个（可达的）注册中心，手工登记服务端地址，直连调用
		try (ZkFixture clientZk = new ZkFixture()) {
			Registry manual = new ZookeeperRegistryFactory().create(clientZk.registry());
			try {
				manual.register(new ServiceInstance(ServiceKey.of(Echo.class), "127.0.0.1", server.port(),
						Map.of(InstanceMetadata.WEIGHT, "1", InstanceMetadata.PROTOCOL, "2")));
				try (RpcClient client = RpcClient.builder().registry(clientZk.registry()).defaultTimeout(Duration.ofSeconds(5)).build()) {
					assertEquals("echo:direct", client.reference(Echo.class).echo("direct"));
				}
			} finally {
				manual.close();
			}
		}
		assertFalse(server.isRegistered(), "nothing to register against yet");

		// 注册中心在原地址出现：后台重试在 15s 内把服务注册上
		long appearedAt = System.nanoTime();
		try (TestingServer zk = new TestingServer(zkPort, true)) {
			server.registered().get(15, TimeUnit.SECONDS);
			long registeredMs = (System.nanoTime() - appearedAt) / 1_000_000;
			assertTrue(server.isRegistered());
			try (CuratorFramework inspector = CuratorFrameworkFactory.newClient(zk.getConnectString(), new RetryOneTime(100))) {
				inspector.start();
				inspector.blockUntilConnected(5, TimeUnit.SECONDS);
				String path = "/rpc/" + ServiceKey.of(Echo.class) + "/providers";
				List<String> providers = inspector.getChildren().forPath(path);
				assertEquals(List.of("127.0.0.1:" + server.port()), providers);
				String data = new String(inspector.getData().forPath(path + "/" + providers.get(0)), StandardCharsets.UTF_8);
				System.out.printf("[unreachable] ZooKeeper appeared on port %d; registered() completed %dms later; node data=%s%n",
						zkPort, registeredMs, data);
			}
			server.close();
		}
		await().atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(() -> ZkFixture.rpcThreads().isEmpty());
	}

	@Test
	void clientReferenceDoesNotWaitForAnUnreachableRegistry_andCatchesUpOnceItAppears() throws Exception {
		int zkPort = unusedPort();
		RegistryConfig registry = RegistryConfig.zookeeper("127.0.0.1:" + zkPort);
		long start = System.nanoTime();
		try (RpcClient client = RpcClient.builder().registry(registry).defaultTimeout(Duration.ofSeconds(3)).build()) {
			Echo echo = client.reference(Echo.class);
			long referenceMillis = (System.nanoTime() - start) / 1_000_000;
			System.out.printf("[unreachable] RpcClient build + reference() returned in %dms with registry unreachable%n", referenceMillis);
			assertTrue(referenceMillis < 2500, "reference() blocked " + referenceMillis + "ms");
			assertTrue(client.providers(ServiceKey.of(Echo.class)).isEmpty());
			long callStart = System.nanoTime();
			RpcException noProvider = assertThrows(RpcException.class, () -> echo.echo("x"));
			System.out.printf("[unreachable] call without providers failed in %dms: %s%n",
					(System.nanoTime() - callStart) / 1_000_000, noProvider.getMessage());

			try (TestingServer zk = new TestingServer(zkPort, true);
					RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(registry).build()) {
				server.export(Echo.class, s -> "echo:" + s);
				server.start();
				server.registered().get(15, TimeUnit.SECONDS);
				await("subscription catches up").atMost(15, TimeUnit.SECONDS)
						.until(() -> !client.providers(ServiceKey.of(Echo.class)).isEmpty());
				assertEquals("echo:late", echo.echo("late"));
			}
		}
		await().atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(() -> ZkFixture.rpcThreads().isEmpty());
	}
}
