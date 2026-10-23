package io.github.sisyfyzhang.steadyrpc.it;

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

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcException;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.registry.zookeeper.ZookeeperRegistryFactory;

/**
 * 注册中心不可达时的启动行为：
 * <ul>
 *   <li>RpcServer 配一个没有监听的 ZK 地址：start() 1 秒内返回、端口可以被直连调用；随后在那个端口上起 ZK，
 *       后台注册在 {@link #CATCH_UP_BOUND_SECONDS} 秒内完成（{@code registered()}）。</li>
 *   <li>RpcClient 配一个没有监听的 ZK 地址：构造与 reference() 都不阻塞（ZK 实现的 subscribe 最多等 connect-wait-ms），
 *       期间调用以"无可用提供者"快速失败；ZK 出现后订阅补推，调用恢复。</li>
 * </ul>
 */
class UnreachableRegistryTest {

	/**
	 * ZK 出现之后、补推 / 后台注册完成的等待上界。不是 15s：TestingServer 启动时监听端口先于 ZooKeeperServer 就绪，恰好在这一瞬间
	 * 连进来的客户端被服务端以 "ZooKeeperServer not running" 关闭，而 ZK 的 {@code NIOServerCnxn.close}（3.9.4 时观察到；3.9.6 仍保留 45s 上界）在
	 * {@code zkServer.removeCnxn} 处 NPE（{@code zkDb} 为 null），{@code closeSock()} 没执行到——服务端不回包也不关套接字，
	 * 客户端的握手被吊到 ZK 客户端连接超时（sessionTimeout 30s / 1 台主机 = 30s）才断开重连。CI paranoid 作业的那次失败正是这样
	 * （日志里 ZK 出现时的 NPE、之后 15s 内消费端没有 CONNECTED）；探针 ZookeeperLateBackendProbeTest 复现为 ZK 出现后 30.3s 才补推。
	 * 30s + 重连退避（≤1s）+ 推送合并 + 余量 → 45s。
	 */
	static final long CATCH_UP_BOUND_SECONDS = 45;

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

		// 注册中心在原地址出现：后台重试把服务注册上（上界见 CATCH_UP_BOUND_SECONDS）
		long appearedAt = System.nanoTime();
		try (TestingServer zk = new TestingServer(zkPort, true)) {
			server.registered().get(CATCH_UP_BOUND_SECONDS, TimeUnit.SECONDS);
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
			long buildMillis = (System.nanoTime() - start) / 1_000_000;
			long referenceStart = System.nanoTime();
			Echo echo = client.reference(Echo.class);
			long referenceMillis = (System.nanoTime() - referenceStart) / 1_000_000;
			System.out.printf("[unreachable] RpcClient build returned in %dms, reference() in %dms with registry unreachable%n", buildMillis,
					referenceMillis);
			// reference() 是订阅发生的地方：最多等 connect-wait-ms（1000ms）。build 不连注册中心，但冷 JVM 的类加载算在它头上
			// （Windows 本机约 1.5s），所以分开计时、各自给界——以前合在一起的 2500ms 在 Windows 冷 JVM 上 10 次 10 次超
			assertTrue(referenceMillis < 2500, "reference() blocked " + referenceMillis + "ms");
			assertTrue(buildMillis < 5000, "RpcClient build blocked " + buildMillis + "ms");
			assertTrue(client.providers(ServiceKey.of(Echo.class)).isEmpty());
			long callStart = System.nanoTime();
			RpcException noProvider = assertThrows(RpcException.class, () -> echo.echo("x"));
			System.out.printf("[unreachable] call without providers failed in %dms: %s%n",
					(System.nanoTime() - callStart) / 1_000_000, noProvider.getMessage());

			try (TestingServer zk = new TestingServer(zkPort, true);
					RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(registry).build()) {
				server.export(Echo.class, s -> "echo:" + s);
				server.start();
				server.registered().get(CATCH_UP_BOUND_SECONDS, TimeUnit.SECONDS);
				await("subscription catches up").atMost(CATCH_UP_BOUND_SECONDS, TimeUnit.SECONDS)
						.until(() -> !client.providers(ServiceKey.of(Echo.class)).isEmpty());
				assertEquals("echo:late", echo.echo("late"));
			}
		}
		await().atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(() -> ZkFixture.rpcThreads().isEmpty());
	}
}
