package io.github.sisyfyzhang.steadyrpc.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryException;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * Nacos 时序探针（D-010 校准）：用真实的 Nacos 容器测两组数字，每项打印测量值并断言上界。默认构建不跑
 * （类名以 ProbeTest 结尾，根 pom 的 surefire 排除），{@code -Pprobe} 运行；Docker 不可用时整个类 skipped。
 *
 * <ol>
 *   <li><b>首订阅漏事件</b>：20 轮，每轮新建一个（已连上的）订阅方 Registry 订阅一个新 ServiceKey，同时另一个 Registry 在
 *       随机 0~50ms 之后注册一个实例。订阅方 10s 内必须看到该实例（看不到 = 漏事件，失败）；统计"首次推送就包含"与
 *       "靠后续推送补上"的次数与延迟。</li>
 *   <li><b>消失窗口</b>：a) 提供者 Registry {@code close()}（不注销：NacosRegistry.close 只关 NamingService，Nacos 客户端的
 *       shutDown 只销毁 gRPC 客户端、不调 deregisterInstance——已对 nacos-client 2.4.3 的字节码核对），模拟进程退出时
 *       内核关闭套接字（服务端收到 FIN）；a') 提供者经本测试里的 TCP 代理连 Nacos，代理"黑洞化"（停止转发、不关连接、
 *       不发 FIN），模拟宿主掉电 / 网络分区这种静默死亡——只能靠服务端的连接保活探测摘除。两者都断言 ≤ 40s
 *       （契约测试声明的 disappearanceWindow）。</li>
 *   <li><b>注册失败上限</b>：b) {@code docker pause} Nacos 容器后对一个已连上的 Registry 调 register，测抛出 RegistryException
 *       的耗时，断言 ≤ 15s（契约测试声明的 registerFailureBound）；另测一个暂停期间新建的 Registry。之后 {@code docker unpause}，
 *       确认注册恢复、订阅方看得到。</li>
 * </ol>
 * 原始结果同时写到 {@code target/probe-nacos-timing.md}（构建产物）；整理后的结论见 docs/probes/nacos-timing.md。
 */
@Tag("probe")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NacosTimingProbeTest {

	static final int FIRST_SUBSCRIBE_ROUNDS = 20;
	static final Duration FIRST_SUBSCRIBE_VISIBLE_BOUND = Duration.ofSeconds(10);
	static final int CLOSE_ROUNDS = 5;
	static final int SILENT_ROUNDS = 2;
	static final Duration DISAPPEARANCE_BOUND = Duration.ofSeconds(40);
	static final Duration REGISTER_FAILURE_BOUND = Duration.ofSeconds(15);
	static final Duration PUSH_TIMEOUT = Duration.ofSeconds(20);

	private GenericContainer<?> container;
	private int httpPort;
	private final List<String> report = Collections.synchronizedList(new ArrayList<>());
	private final Random random = new Random(20_261_019L);
	private int nextPort = 31000;

	@BeforeAll
	void startNacos() {
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Nacos timing probe skipped: Docker is not available");
		httpPort = NacosTestContainers.freePortPair();
		container = NacosTestContainers.create(httpPort);
		long start = System.nanoTime();
		container.start();
		log("# Nacos 时序探针原始输出");
		log("");
		log("- 镜像：" + NacosTestContainers.IMAGE + "（standalone），HTTP 127.0.0.1:" + httpPort + "，gRPC " + (httpPort + 1000)
				+ "；容器启动 " + millis(start) + "ms");
		log("- JVM：" + System.getProperty("java.runtime.version") + " · " + System.getProperty("os.name") + " "
				+ System.getProperty("os.version") + " · 可用核数 " + Runtime.getRuntime().availableProcessors());
		log("- nacos-client：" + com.alibaba.nacos.common.utils.VersionUtils.version);
		log("");
	}

	@AfterAll
	void stopNacos() throws IOException {
		if (container != null) {
			try {
				container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
			} catch (RuntimeException ignored) {
				// 没有暂停
			}
			container.stop();
		}
		if (!report.isEmpty()) {
			Path out = Path.of("target", "probe-nacos-timing.md");
			Files.createDirectories(out.getParent());
			Files.writeString(out, String.join("\n", report) + "\n", StandardCharsets.UTF_8);
			System.out.println("[probe nacos] raw results written to " + out.toAbsolutePath());
		}
	}

	private RegistryConfig config() {
		return RegistryConfig.nacos("127.0.0.1:" + httpPort);
	}

	// ------------------------------------------------------------------ 1. 首订阅漏事件

	@Test
	@Order(1)
	void firstSubscriptionDoesNotMissAConcurrentRegistration() throws Exception {
		log("## 1. 首订阅漏事件（" + FIRST_SUBSCRIBE_ROUNDS + " 轮）");
		log("");
		log("| 轮 | 注册延后 (ms) | subscribe 耗时 (ms) | register 耗时 (ms) | 注册相对订阅返回 | 推送次数 | 首次推送含实例 | 可见延迟 (ms，自注册开始) |");
		log("|---|---|---|---|---|---|---|---|");
		List<Long> firstPushLatency = new ArrayList<>();
		List<Long> laterPushLatency = new ArrayList<>();
		List<Integer> missed = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try (NacosRegistry provider = new NacosRegistry(config())) {
			// 让提供者的连接先建好：第一次 register 包含建连，不该算进竞态
			ServiceKey warmKey = key("Warm");
			provider.register(instance(warmKey, port()));
			for (int round = 1; round <= FIRST_SUBSCRIBE_ROUNDS; round++) {
				ServiceKey key = key("FirstSubscribe");
				ServiceInstance instance = instance(key, port());
				int delayMs = random.nextInt(51);
				try (NacosRegistry consumer = new NacosRegistry(config())) {
					TimedRecorder recorder = new TimedRecorder();
					CountDownLatch go = new CountDownLatch(1);
					Future<long[]> subscribe = pool.submit(() -> {
						go.await();
						long s = System.nanoTime();
						consumer.subscribe(key, recorder);
						return new long[] { s, System.nanoTime() };
					});
					Future<long[]> register = pool.submit(() -> {
						go.await();
						TimeUnit.MILLISECONDS.sleep(delayMs);
						long s = System.nanoTime();
						provider.register(instance);
						return new long[] { s, System.nanoTime() };
					});
					go.countDown();
					long[] sub = subscribe.get(30, TimeUnit.SECONDS);
					long[] reg = register.get(30, TimeUnit.SECONDS);
					TimedRecorder.Push seen = recorder.awaitContaining(instance.address(), FIRST_SUBSCRIBE_VISIBLE_BOUND, reg[0]);
					boolean firstContains = recorder.first() != null && contains(recorder.first().instances(), instance.address());
					String order = reg[1] < sub[1] ? "订阅返回前已注册完" : reg[0] > sub[1] ? "订阅返回后才开始注册" : "两者重叠";
					Long latency = seen == null ? null : (seen.nanos() - reg[0]) / 1_000_000;
					if (seen == null) {
						missed.add(round);
					} else if (firstContains) {
						firstPushLatency.add(latency);
					} else {
						laterPushLatency.add(latency);
					}
					log("| " + round + " | " + delayMs + " | " + (sub[1] - sub[0]) / 1_000_000 + " | " + (reg[1] - reg[0]) / 1_000_000 + " | "
							+ order + " | " + recorder.count() + " | " + (firstContains ? "是" : "否") + " | "
							+ (seen == null ? "**10s 内未见（漏事件）**" : latency) + " |");
				}
				provider.unregister(instance);
			}
		} finally {
			pool.shutdownNow();
		}
		log("");
		log("- 首次推送就包含：" + firstPushLatency.size() + " 轮，可见延迟 " + stats(firstPushLatency));
		log("- 靠后续推送补上：" + laterPushLatency.size() + " 轮，可见延迟 " + stats(laterPushLatency));
		log("- 10s 内未见（漏事件）：" + missed.size() + " 轮 " + missed);
		log("");
		assertTrue(missed.isEmpty(), "subscriber never saw a concurrently registered instance in rounds " + missed);
	}

	// ------------------------------------------------------------------ 2a. 消失窗口：close 不注销

	@Test
	@Order(2)
	void instancesDisappearAfterTheProviderRegistryIsClosedWithoutDeregistering() throws Exception {
		log("## 2a. 消失窗口：提供者 Registry close()（不注销，连接正常关闭）");
		log("");
		log("| 轮 | 消失耗时 (ms，自 close 开始) | close 耗时 (ms) |");
		log("|---|---|---|");
		List<Long> gone = new ArrayList<>();
		try (NacosRegistry consumer = new NacosRegistry(config())) {
			for (int round = 1; round <= CLOSE_ROUNDS; round++) {
				ServiceKey key = key("Close");
				ServiceInstance instance = instance(key, port());
				TimedRecorder recorder = new TimedRecorder();
				consumer.subscribe(key, recorder);
				NacosRegistry provider = new NacosRegistry(config());
				provider.register(instance);
				assertNotNull(recorder.awaitContaining(instance.address(), PUSH_TIMEOUT, System.nanoTime()), "provider visible");
				long start = System.nanoTime();
				provider.close();
				long closeMs = millis(start);
				TimedRecorder.Push absent = recorder.awaitAbsent(instance.address(), DISAPPEARANCE_BOUND.plusSeconds(30));
				Long ms = absent == null ? null : (absent.nanos() - start) / 1_000_000;
				gone.add(ms == null ? Long.MAX_VALUE : ms);
				log("| " + round + " | " + (ms == null ? "**> " + DISAPPEARANCE_BOUND.plusSeconds(30).toSeconds() + "s**" : ms) + " | " + closeMs + " |");
				consumer.unsubscribe(key, recorder);
			}
		}
		log("");
		log("- 消失耗时 " + stats(gone) + "（上界 " + DISAPPEARANCE_BOUND.toSeconds() + "s）");
		log("");
		for (long ms : gone) {
			assertTrue(ms <= DISAPPEARANCE_BOUND.toMillis(), "instance disappeared after " + ms + "ms > " + DISAPPEARANCE_BOUND);
		}
	}

	// ------------------------------------------------------------------ 2a'. 消失窗口：静默死亡（黑洞代理）

	@Test
	@Order(3)
	void instancesDisappearAfterTheProviderGoesSilentWithoutClosingItsConnection() throws Exception {
		log("## 2a'. 消失窗口：提供者静默死亡（经 TCP 代理连 Nacos，代理停止转发、不关连接、不发 FIN）");
		log("");
		log("| 轮 | 消失耗时 (ms，自黑洞化开始) |");
		log("|---|---|");
		List<Long> gone = new ArrayList<>();
		try (NacosRegistry consumer = new NacosRegistry(config())) {
			for (int round = 1; round <= SILENT_ROUNDS; round++) {
				ServiceKey key = key("Silent");
				ServiceInstance instance = instance(key, port());
				TimedRecorder recorder = new TimedRecorder();
				consumer.subscribe(key, recorder);
				int proxyPort = NacosTestContainers.freePortPair();
				try (BlackholeProxy proxy = new BlackholeProxy(proxyPort, httpPort)) {
					NacosRegistry provider = new NacosRegistry(RegistryConfig.nacos("127.0.0.1:" + proxyPort));
					try {
						provider.register(instance);
						assertNotNull(recorder.awaitContaining(instance.address(), PUSH_TIMEOUT, System.nanoTime()), "provider visible");
						long start = System.nanoTime();
						proxy.blackhole();
						TimedRecorder.Push absent = recorder.awaitAbsent(instance.address(), DISAPPEARANCE_BOUND.plusSeconds(60));
						Long ms = absent == null ? null : (absent.nanos() - start) / 1_000_000;
						gone.add(ms == null ? Long.MAX_VALUE : ms);
						log("| " + round + " | " + (ms == null ? "**> " + DISAPPEARANCE_BOUND.plusSeconds(60).toSeconds() + "s**" : ms) + " |");
					} finally {
						proxy.close(); // 先断开代理，provider.close 不会再卡在黑洞里
						provider.close();
					}
				}
				consumer.unsubscribe(key, recorder);
			}
		}
		log("");
		log("- 消失耗时 " + stats(gone) + "（上界 " + DISAPPEARANCE_BOUND.toSeconds() + "s）");
		log("");
		for (long ms : gone) {
			assertTrue(ms <= DISAPPEARANCE_BOUND.toMillis(), "silent provider disappeared after " + ms + "ms > " + DISAPPEARANCE_BOUND);
		}
	}

	// ------------------------------------------------------------------ 2b. 注册失败上限（docker pause）

	@Test
	@Order(4)
	void registerAgainstAPausedNacosFailsWithinTheBoundAndRecoversAfterUnpause() throws Exception {
		log("## 2b. 注册失败上限：docker pause Nacos 容器");
		log("");
		try (NacosRegistry consumer = new NacosRegistry(config()); NacosRegistry connected = new NacosRegistry(config())) {
			ServiceKey key = key("Pause");
			connected.register(instance(key("PauseWarm"), port())); // 确认连接已建好
			ServiceInstance instance = instance(key, port());
			TimedRecorder recorder = new TimedRecorder();
			consumer.subscribe(key, recorder);

			container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
			long pausedAt = System.nanoTime();
			String connectedOutcome;
			long connectedMs;
			String freshOutcome;
			long freshCreateMs = -1;
			long freshRegisterMs = -1;
			try {
				long start = System.nanoTime();
				connectedOutcome = outcome(() -> connected.register(instance));
				connectedMs = millis(start);

				long createStart = System.nanoTime();
				NacosRegistry fresh = null;
				try {
					fresh = new NacosRegistry(config());
					freshCreateMs = millis(createStart);
					NacosRegistry f = fresh;
					long registerStart = System.nanoTime();
					freshOutcome = outcome(() -> f.register(instance(key("PauseFresh"), port())));
					freshRegisterMs = millis(registerStart);
				} catch (RuntimeException e) {
					freshCreateMs = millis(createStart);
					freshOutcome = "创建即失败：" + e;
				} finally {
					if (fresh != null) {
						fresh.close();
					}
				}
			} finally {
				container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
			}
			long pausedMs = millis(pausedAt);
			long unpausedAt = System.nanoTime();
			// 恢复：同一个 Registry 再注册，直到成功；订阅方随后看得到
			long recoveredMs = -1;
			String lastError = null;
			while (millis(unpausedAt) < 90_000) {
				try {
					connected.register(instance);
					recoveredMs = millis(unpausedAt);
					break;
				} catch (RegistryException e) {
					lastError = e.toString();
					TimeUnit.MILLISECONDS.sleep(200);
				}
			}
			TimedRecorder.Push visible = recoveredMs < 0 ? null : recorder.awaitContaining(instance.address(), PUSH_TIMEOUT, unpausedAt);
			long visibleMs = visible == null ? -1 : (visible.nanos() - unpausedAt) / 1_000_000;

			log("| 情形 | 结果 | 耗时 (ms) |");
			log("|---|---|---|");
			log("| 已连上的 Registry 在暂停期间 register | " + connectedOutcome + " | " + connectedMs + " |");
			log("| 暂停期间新建 Registry | " + (freshRegisterMs < 0 ? freshOutcome : "创建成功（未抛异常）") + " | " + freshCreateMs + " |");
			if (freshRegisterMs >= 0) {
				log("| 暂停期间新建的 Registry register | " + freshOutcome + " | " + freshRegisterMs + " |");
			}
			log("| 暂停总时长 | — | " + pausedMs + " |");
			log("| unpause 后同一 Registry 再注册成功 | " + (recoveredMs < 0 ? "**90s 内未恢复**：" + lastError : "成功") + " | " + recoveredMs + " |");
			log("| unpause 后订阅方看到该实例 | " + (visible == null ? "**未看到**" : "看到") + " | " + visibleMs + " |");
			log("");

			assertTrue(connectedOutcome.startsWith("RegistryException"), "register against a paused Nacos: " + connectedOutcome);
			assertTrue(connectedMs <= REGISTER_FAILURE_BOUND.toMillis(), "register blocked " + connectedMs + "ms > " + REGISTER_FAILURE_BOUND);
			assertTrue(recoveredMs >= 0, "register did not recover within 90s after unpause: " + lastError);
			assertNotNull(visible, "subscriber did not see the instance registered after unpause");
		}
	}

	// ------------------------------------------------------------------ 工具

	private interface Action {
		void run() throws Exception;
	}

	private static String outcome(Action action) {
		try {
			action.run();
			return "正常返回（未抛异常）";
		} catch (RegistryException e) {
			Throwable root = e;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			String message = String.valueOf(root.getMessage()).replace('|', '/');
			return "RegistryException（根因 " + root.getClass().getSimpleName() + ": "
					+ (message.length() > 80 ? message.substring(0, 80) + "…" : message) + "）";
		} catch (Exception e) {
			return "其它异常：" + e;
		}
	}

	private void log(String line) {
		report.add(line);
		System.out.println("[probe nacos] " + line);
	}

	private static long millis(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static ServiceKey key(String what) {
		return new ServiceKey("probe." + what + UUID.randomUUID().toString().replace("-", ""), "1.0", "probe");
	}

	private synchronized int port() {
		return nextPort++;
	}

	private static ServiceInstance instance(ServiceKey key, int port) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, "1");
		metadata.put(InstanceMetadata.PROTOCOL, "2");
		return new ServiceInstance(key, "127.0.0.1", port, metadata);
	}

	private static boolean contains(List<ServiceInstance> instances, String address) {
		return instances.stream().anyMatch(i -> i.address().equals(address));
	}

	/** n、最小、中位、p90、最大（n ≤ 20 时 p90 就是第 ⌈0.9n⌉ 个）。 */
	static String stats(List<Long> values) {
		if (values.isEmpty()) {
			return "（无样本）";
		}
		List<Long> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int n = sorted.size();
		long p50 = sorted.get((n - 1) / 2);
		long p90 = sorted.get(Math.max(0, (int) Math.ceil(0.9 * n) - 1));
		return String.format(Locale.ROOT, "n=%d，min %d / p50 %d / p90 %d / max %d ms", n, sorted.get(0), p50, p90, sorted.get(n - 1));
	}

	/** 记录每次推送及其到达时刻（nanoTime）。 */
	static final class TimedRecorder implements InstanceListener {
		record Push(long nanos, List<ServiceInstance> instances) {
		}

		private final List<Push> pushes = new CopyOnWriteArrayList<>();

		@Override
		public void onInstancesChanged(List<ServiceInstance> instances) {
			pushes.add(new Push(System.nanoTime(), List.copyOf(instances)));
		}

		int count() {
			return pushes.size();
		}

		Push first() {
			return pushes.isEmpty() ? null : pushes.get(0);
		}

		/** 第一次包含该地址的推送（{@code since} 之前的也算：首次推送可能早于计时起点）；超时返回 null。 */
		Push awaitContaining(String address, Duration timeout, long since) throws InterruptedException {
			long deadline = System.nanoTime() + timeout.toNanos();
			while (true) {
				for (Push push : pushes) {
					if (contains(push.instances(), address)) {
						return push;
					}
				}
				if (System.nanoTime() > deadline) {
					return null;
				}
				TimeUnit.MILLISECONDS.sleep(5);
			}
		}

		/** 等到最近一次推送不含该地址，返回那次推送；超时返回 null。 */
		Push awaitAbsent(String address, Duration timeout) throws InterruptedException {
			long deadline = System.nanoTime() + timeout.toNanos();
			while (System.nanoTime() < deadline) {
				Push latest = pushes.isEmpty() ? null : pushes.get(pushes.size() - 1);
				if (latest != null && !contains(latest.instances(), address)) {
					return latest;
				}
				TimeUnit.MILLISECONDS.sleep(5);
			}
			return null;
		}
	}

	/**
	 * 把 {@code listenPort} / {@code listenPort + 1000} 转发到 Nacos 的 HTTP / gRPC 端口（Nacos 客户端按 HTTP 端口 + 1000
	 * 推算 gRPC 端口，所以两个端口都要代理）。{@link #blackhole()} 之后：已有连接继续读、但把两个方向的数据都丢掉，
	 * 不关闭套接字（对端收不到 FIN / RST）；新连接照样接受、但不连上游、只读不回。{@link #close()} 关闭全部套接字。
	 */
	static final class BlackholeProxy implements AutoCloseable {
		private final List<ServerSocket> listeners = new ArrayList<>();
		private final List<Socket> sockets = new CopyOnWriteArrayList<>();
		private volatile boolean blackholed;

		BlackholeProxy(int listenPort, int nacosHttpPort) throws IOException {
			listen(listenPort, nacosHttpPort);
			listen(listenPort + 1000, nacosHttpPort + 1000);
		}

		private void listen(int port, int upstreamPort) throws IOException {
			ServerSocket server = new ServerSocket();
			server.bind(new InetSocketAddress("127.0.0.1", port));
			listeners.add(server);
			Thread.ofVirtual().start(() -> {
				while (!server.isClosed()) {
					try {
						Socket client = server.accept();
						sockets.add(client);
						if (blackholed) {
							Thread.ofVirtual().start(() -> pump(client, null));
							continue;
						}
						Socket upstream = new Socket("127.0.0.1", upstreamPort);
						sockets.add(upstream);
						Thread.ofVirtual().start(() -> pump(client, upstream));
						Thread.ofVirtual().start(() -> pump(upstream, client));
					} catch (IOException e) {
						return;
					}
				}
			});
		}

		private void pump(Socket from, Socket to) {
			byte[] buffer = new byte[16 * 1024];
			try (InputStream in = from.getInputStream()) {
				OutputStream out = to == null ? null : to.getOutputStream();
				int n;
				while ((n = in.read(buffer)) >= 0) {
					if (!blackholed && out != null) {
						out.write(buffer, 0, n);
						out.flush();
					}
				}
			} catch (IOException e) {
				// 关闭
			}
			// 不在这里关闭对端：黑洞化之后任何一侧都不该收到 FIN
			if (!blackholed) {
				closeQuietly(to);
			}
		}

		void blackhole() {
			blackholed = true;
		}

		@Override
		public void close() {
			listeners.forEach(BlackholeProxy::closeQuietly);
			sockets.forEach(BlackholeProxy::closeQuietly);
		}

		private static void closeQuietly(AutoCloseable closeable) {
			if (closeable == null) {
				return;
			}
			try {
				closeable.close();
			} catch (Exception ignored) {
				// 清理
			}
		}
	}

	@Test
	@Order(0)
	void statsFormatting() {
		assertEquals("n=3，min 1 / p50 2 / p90 3 / max 3 ms", stats(List.of(3L, 1L, 2L)));
	}
}
