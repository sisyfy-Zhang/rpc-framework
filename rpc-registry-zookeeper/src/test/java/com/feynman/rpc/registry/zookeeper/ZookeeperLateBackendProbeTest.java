package com.feynman.rpc.registry.zookeeper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 探针（{@code -Pprobe}，默认构建不跑）：订阅发起时 ZooKeeper 不在，过 D 秒才在原端口出现，订阅多久补推？
 * D 覆盖 Curator 后台操作"重试放弃"之前与之后（connection-timeout 5s + 指数退避 3 次，约 6~12s）。
 * 每个 D 记录：ZK 出现 → 消费端第一次 CONNECTED、→ 提供者注册完成、→ 订阅者收到含该实例的列表；以及消费端
 * 经历的连接状态序列（SUSPENDED 来自后台重试放弃时的 validateConnection）。结果写 target/probe-zk-late-backend.md。
 */
@Tag("probe")
class ZookeeperLateBackendProbeTest {

	private static final long CATCH_UP_LIMIT_MS = 90_000;

	record Trial(long delayMs, long subscribeMs, long connectedAfterUpMs, long registeredAfterUpMs, long seenAfterUpMs,
			long seenAfterRegisterMs, List<String> states) {
	}

	@Test
	void subscriptionCatchesUpWhateverTheDelayBeforeZooKeeperAppears() throws Exception {
		long[] delays = { 0, 2_000, 4_000, 6_000, 8_000, 10_000, 12_000, 15_000, 20_000, 35_000 };
		List<Trial> trials = new ArrayList<>();
		for (long delay : delays) {
			trials.add(trial(delay));
			System.out.println("[probe] " + trials.get(trials.size() - 1));
		}
		StringBuilder md = new StringBuilder("| ZK 出现前的延迟 D (ms) | subscribe 耗时 (ms) | ZK 出现 → 消费端 CONNECTED (ms) | ZK 出现 → 注册完成 (ms) | ZK 出现 → 订阅者看到 (ms) | 注册完成 → 订阅者看到 (ms) | 消费端连接状态序列（相对 subscribe 的 ms） |\n|---|---|---|---|---|---|---|\n");
		for (Trial t : trials) {
			md.append("| ").append(t.delayMs()).append(" | ").append(t.subscribeMs()).append(" | ").append(t.connectedAfterUpMs()).append(" | ")
					.append(t.registeredAfterUpMs()).append(" | ").append(t.seenAfterUpMs() < 0 ? "**未见（" + CATCH_UP_LIMIT_MS + "ms 内）**" : t.seenAfterUpMs())
					.append(" | ").append(t.seenAfterRegisterMs()).append(" | ").append(String.join(" → ", t.states())).append(" |\n");
		}
		Path out = Path.of("target", "probe-zk-late-backend.md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
		System.out.println(md);
	}

	/**
	 * CI 失败（paranoid 作业）的现场：TestingServer 启动时监听端口先于 ZooKeeperServer 就绪，这一瞬间连进来的客户端被服务端以
	 * "ZooKeeperServer not running" 关闭，而 ZK 的 {@code NIOServerCnxn.close} 在 {@code zkServer.removeCnxn} 处 NPE（3.9.4 时观察到；升到 3.9.6 后仍保留同一上界）
	 * （{@code zkDb} 为 null），{@code closeSock()} 没执行到——套接字留着、不回包。这里用"接受连接后不回包、也不关"的端口
	 * 复现客户端看到的东西：握手被吊住，直到 ZK 客户端的连接超时（sessionTimeout / 主机数）才断开重连。
	 */
	@Test
	void aHandshakeSwallowedDuringServerStartupDelaysCatchUpUntilTheClientConnectTimeout() throws Exception {
		int port = ZookeeperRegistryContractTest.unusedPort();
		RegistryConfig config = RegistryConfig.zookeeper("127.0.0.1:" + port);
		ServiceKey key = new ServiceKey("probe.HalfOpen", "1.0", "probe");
		ServiceInstance instance = new ServiceInstance(key, "127.0.0.1", 7100, Map.of(InstanceMetadata.WEIGHT, "1", InstanceMetadata.PROTOCOL, "2"));
		List<String> states = new CopyOnWriteArrayList<>();
		List<long[]> seen = new CopyOnWriteArrayList<>();
		java.net.ServerSocket blackHole = new java.net.ServerSocket(port);
		List<java.net.Socket> swallowed = new CopyOnWriteArrayList<>();
		Thread acceptor = Thread.ofVirtual().start(() -> {
			try {
				swallowed.add(blackHole.accept()); // 接受、不读不写不关：与 NPE 之后留下的服务端套接字一样
			} catch (java.io.IOException ignored) {
				// 关闭监听
			}
		});
		long t0 = System.nanoTime();
		ZookeeperRegistry consumer = new ZookeeperRegistry(config);
		consumer.curator().getConnectionStateListenable()
				.addListener((c, state) -> states.add(state + "@" + (System.nanoTime() - t0) / 1_000_000));
		try {
			consumer.subscribe(key, list -> {
				if (list.stream().anyMatch(i -> i.address().equals(instance.address()))) {
					seen.add(new long[] { System.nanoTime() });
				}
			});
			acceptor.join(10_000);
			long swallowedAt = (System.nanoTime() - t0) / 1_000_000;
			blackHole.close(); // 只关监听，被吞掉的那条连接保持打开
			try (TestingServer zk = new TestingServer(port, true); ZookeeperRegistry provider = new ZookeeperRegistry(config)) {
				long upAt = System.nanoTime();
				provider.register(instance);
				long registeredAt = System.nanoTime();
				long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CATCH_UP_LIMIT_MS);
				while (seen.isEmpty() && System.nanoTime() < deadline) {
					Thread.sleep(20);
				}
				String md = "| 吞掉握手的时刻 (ms) | ZK 出现 → 注册完成 (ms) | ZK 出现 → 订阅者看到 (ms) | 消费端连接状态序列（相对创建的 ms） |\n|---|---|---|---|\n| "
						+ swallowedAt + " | " + (registeredAt - upAt) / 1_000_000 + " | "
						+ (seen.isEmpty() ? "**未见（" + CATCH_UP_LIMIT_MS + "ms 内）**" : String.valueOf((seen.get(0)[0] - upAt) / 1_000_000)) + " | "
						+ String.join(" → ", states) + " |\n";
				Files.createDirectories(Path.of("target"));
				Files.writeString(Path.of("target", "probe-zk-half-open.md"), md, StandardCharsets.UTF_8);
				System.out.println("[probe half-open]\n" + md);
			}
		} finally {
			for (java.net.Socket s : swallowed) {
				s.close();
			}
			consumer.close();
		}
	}

	private Trial trial(long delayMs) throws Exception {
		int port = ZookeeperRegistryContractTest.unusedPort();
		RegistryConfig config = RegistryConfig.zookeeper("127.0.0.1:" + port);
		ServiceKey key = new ServiceKey("probe.Late" + delayMs, "1.0", "probe");
		ServiceInstance instance = new ServiceInstance(key, "127.0.0.1", 7000 + (int) (delayMs / 1000),
				Map.of(InstanceMetadata.WEIGHT, "1", InstanceMetadata.PROTOCOL, "2"));
		List<String> states = new CopyOnWriteArrayList<>();
		List<long[]> seen = new CopyOnWriteArrayList<>();
		long t0 = System.nanoTime();
		ZookeeperRegistry consumer = new ZookeeperRegistry(config);
		long[] connectedAt = { -1 };
		consumer.curator().getConnectionStateListenable().addListener((c, state) -> {
			long at = (System.nanoTime() - t0) / 1_000_000;
			states.add(state + "@" + at);
			if (state.isConnected() && connectedAt[0] < 0) {
				connectedAt[0] = System.nanoTime();
			}
		});
		try {
			consumer.subscribe(key, list -> {
				if (list.stream().anyMatch(i -> i.address().equals(instance.address()))) {
					seen.add(new long[] { System.nanoTime() });
				}
			});
			long subscribeMs = (System.nanoTime() - t0) / 1_000_000;
			long sleep = delayMs - (System.nanoTime() - t0) / 1_000_000;
			if (sleep > 0) {
				Thread.sleep(sleep);
			}
			try (TestingServer zk = new TestingServer(port, true)) {
				long upAt = System.nanoTime();
				long registeredAt;
				try (ZookeeperRegistry provider = new ZookeeperRegistry(config)) {
					for (;;) {
						try {
							provider.register(instance);
							registeredAt = System.nanoTime();
							break;
						} catch (RuntimeException e) {
							Thread.sleep(100);
						}
					}
					long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CATCH_UP_LIMIT_MS);
					while (seen.isEmpty() && System.nanoTime() < deadline) {
						Thread.sleep(20);
					}
				}
				long seenAt = seen.isEmpty() ? -1 : seen.get(0)[0];
				return new Trial(delayMs, subscribeMs, connectedAt[0] < 0 ? -1 : (connectedAt[0] - upAt) / 1_000_000,
						(registeredAt - upAt) / 1_000_000, seenAt < 0 ? -1 : (seenAt - upAt) / 1_000_000,
						seenAt < 0 ? -1 : (seenAt - registeredAt) / 1_000_000, List.copyOf(states));
			}
		} finally {
			consumer.close();
		}
	}
}
