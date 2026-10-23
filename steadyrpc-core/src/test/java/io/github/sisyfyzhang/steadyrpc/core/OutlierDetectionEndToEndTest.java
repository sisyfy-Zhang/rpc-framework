package io.github.sisyfyzhang.steadyrpc.core;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.OutlierDetectionConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离群摘除端到端（进程内注册中心 + 真实 RpcServer）：一个提供者把请求挂住到超过调用超时，failfast + 轮询下
 * 它先造成几个超时、随即被摘，之后错误停止；到期放回，若已恢复就照常分到流量，仍然挂住就再被摘（时长翻倍）。
 * 另有：业务异常不触发摘除、注册中心移除地址时状态被清掉、关掉离群摘除时挂住的节点一直分到流量。
 */
class OutlierDetectionEndToEndTest {

	public interface Echo {
		String echo(String text);
	}

	/** {@code hang} 为 true 时每个请求先停 {@link #HANG}（远超调用超时）再回；"boom" 开头的输入抛业务异常。 */
	static final class HangableEcho implements Echo {
		volatile boolean hang;

		@Override
		public String echo(String text) {
			if (hang) {
				LockSupport.parkNanos(HANG.toNanos());
			}
			if (text.startsWith("boom")) {
				throw new IllegalStateException("business failure: " + text);
			}
			return text;
		}
	}

	static final Duration HANG = Duration.ofMillis(600);
	static final Duration CALL_TIMEOUT = Duration.ofMillis(200);
	static final int CONSECUTIVE = 3;
	static final OutlierDetectionConfig FAST = OutlierDetectionConfig.builder().consecutiveFailures(CONSECUTIVE)
			.baseEjection(Duration.ofSeconds(1)).maxEjection(Duration.ofSeconds(2)).build();

	private final String ns = "outlier-" + UUID.randomUUID();
	private final List<RpcServer> servers = new ArrayList<>();
	private final List<HangableEcho> impls = new ArrayList<>();
	private final List<AutoCloseable> closeables = new ArrayList<>();

	@AfterEach
	void cleanup() throws Exception {
		impls.forEach(impl -> impl.hang = false);
		for (int i = closeables.size() - 1; i >= 0; i--) {
			closeables.get(i).close();
		}
		servers.forEach(RpcServer::close);
	}

	private String startServer() throws Exception {
		HangableEcho impl = new HangableEcho();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).executor(ExecutorConfig.virtualThreads()).build();
		server.export(Echo.class, impl);
		server.start();
		server.registered().get(5, TimeUnit.SECONDS);
		servers.add(server);
		impls.add(impl);
		return "127.0.0.1:" + server.port();
	}

	private RpcClient client(OutlierDetectionConfig outlier) {
		RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns))
				.cluster("failfast").loadBalance("roundrobin").outlierDetection(outlier).build();
		closeables.add(client);
		return client;
	}

	private static Echo reference(RpcClient client, int providers) {
		Echo echo = client.reference(Echo.class, ReferenceOptions.defaults().timeout(CALL_TIMEOUT).awaitProviders(Duration.ofSeconds(5)));
		await().atMost(5, TimeUnit.SECONDS).until(() -> client.providers(ServiceKey.of(Echo.class)).size() == providers);
		return echo;
	}

	/** 顺序发 {@code n} 次调用，返回失败数。 */
	private static int call(Echo echo, int n) {
		int errors = 0;
		for (int i = 0; i < n; i++) {
			try {
				assertEquals("x" + i, echo.echo("x" + i));
			} catch (RpcException e) {
				errors++;
			}
		}
		return errors;
	}

	/** 调用到第一次摘除发生为止（上限 {@code max} 次），返回失败数。 */
	private static int callUntilEjected(RpcClient client, Echo echo, long ejectionsBefore, int max) {
		int errors = 0;
		for (int i = 0; i < max && client.outlierEjections() == ejectionsBefore; i++) {
			try {
				echo.echo("y" + i);
			} catch (RpcException e) {
				errors++;
			}
		}
		assertEquals(ejectionsBefore + 1, client.outlierEjections(), "no ejection within " + max + " calls");
		return errors;
	}

	private static long attempts(RpcClient client, String address) {
		RpcClient.NodeCallStats stats = client.nodeCallStats().get(address);
		return stats == null ? 0 : stats.attempts();
	}

	@Test
	void hungNodeIsEjectedErrorsStopAndItReturnsAfterExpiry() throws Exception {
		startServer();
		startServer();
		String hung = startServer();
		impls.get(2).hang = true;
		RpcClient client = client(FAST);
		Echo echo = reference(client, 3);

		int errors = callUntilEjected(client, echo, 0, 60);
		assertEquals(CONSECUTIVE, errors, "every attempt on the hung node timed out until the streak reached the threshold");
		assertEquals(Set.of(hung), client.ejectedNodes().keySet());
		assertEquals(CONSECUTIVE, client.nodeCallStats().get(hung).timeouts());

		// 摘除期间：错误停止，挂住的节点一个尝试都分不到
		long hungAttempts = attempts(client, hung);
		assertEquals(0, call(echo, 30));
		assertEquals(hungAttempts, attempts(client, hung));
		assertEquals(3, client.providers(ServiceKey.of(Echo.class)).size(), "ejection does not change the registry view");

		// 节点恢复；摘除到期后自动回到候选集，照常分到流量
		impls.get(2).hang = false;
		await().atMost(3, TimeUnit.SECONDS).until(() -> client.ejectedNodes().isEmpty());
		assertEquals(1, client.outlierRestores());
		assertEquals(0, call(echo, 30));
		assertTrue(attempts(client, hung) >= hungAttempts + 9, "back in rotation: " + client.nodeCallStats());
		assertEquals(1, client.outlierEjections());
		assertEquals(0, client.outlierAllEjectedFallbacks());
	}

	@Test
	void stillHungAfterExpiryIsEjectedAgainForLonger() throws Exception {
		startServer();
		startServer();
		String hung = startServer();
		impls.get(2).hang = true;
		RpcClient client = client(FAST);
		Echo echo = reference(client, 3);

		callUntilEjected(client, echo, 0, 60);
		await().atMost(3, TimeUnit.SECONDS).until(() -> client.ejectedNodes().isEmpty());
		// 放回之后仍然挂住：连续计数从零开始，又是 CONSECUTIVE 个超时之后再摘，这次时长 2 × baseEjection
		int errors = callUntilEjected(client, echo, 1, 60);
		assertEquals(CONSECUTIVE, errors);
		Instant until = client.ejectedNodes().get(hung);
		assertTrue(until.isAfter(Instant.now().plusMillis(1500)), "second ejection lasts 2s: " + until);
		assertEquals(0, call(echo, 15));
	}

	@Test
	void businessExceptionsNeverEject() throws Exception {
		startServer();
		startServer();
		startServer();
		RpcClient client = client(FAST);
		Echo echo = reference(client, 3);
		for (int i = 0; i < 40; i++) {
			int n = i;
			assertThrows(IllegalStateException.class, () -> echo.echo("boom-" + n));
		}
		assertEquals(0, client.outlierEjections(), "a business exception is an answer: the node is alive");
		assertTrue(client.ejectedNodes().isEmpty());
	}

	@Test
	void registryRemovalClearsTheAddressState() throws Exception {
		startServer();
		int deadPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			deadPort = socket.getLocalPort();
		}
		String dead = "127.0.0.1:" + deadPort;
		ServiceInstance deadInstance = new ServiceInstance(ServiceKey.of(Echo.class), "127.0.0.1", deadPort, Map.of(InstanceMetadata.WEIGHT, "1"));
		Registry fake = new InMemoryRegistryFactory().create(InMemoryRegistryFactory.config(ns));
		closeables.add(fake);
		fake.register(deadInstance);
		RpcClient client = client(FAST);
		Echo echo = reference(client, 2);

		// 连不上的地址：每次尝试都是传输失败（立即失败），三次之后被摘
		assertEquals(CONSECUTIVE, callUntilEjected(client, echo, 0, 30));
		assertEquals(Set.of(dead), client.ejectedNodes().keySet());

		// 注册中心摘掉它：状态随之清掉（不是"放回"）
		InMemoryRegistryFactory.unregister(ns, deadInstance);
		await().atMost(5, TimeUnit.SECONDS).until(() -> client.providers(ServiceKey.of(Echo.class)).size() == 1);
		assertTrue(client.ejectedNodes().isEmpty());
		assertEquals(0, client.outlierRestores());

		// 同一地址重新注册：从零开始，再被摘时是第一次摘除的时长（baseEjection = 1s），不是翻倍
		fake.register(deadInstance);
		await().atMost(5, TimeUnit.SECONDS).until(() -> client.providers(ServiceKey.of(Echo.class)).size() == 2);
		assertTrue(client.ejectedNodes().isEmpty());
		callUntilEjected(client, echo, 1, 30);
		Instant until = client.ejectedNodes().get(dead);
		assertTrue(until.isBefore(Instant.now().plusMillis(1200)), "fresh state: first ejection again (1s): " + until);
	}

	@Test
	void disabledKeepsRoutingToTheHungNode() throws Exception {
		startServer();
		startServer();
		String hung = startServer();
		impls.get(2).hang = true;
		RpcClient client = client(OutlierDetectionConfig.disabled());
		Echo echo = reference(client, 3);
		int errors = call(echo, 24);
		assertEquals(8, errors, "a third of the calls time out on the hung node");
		assertEquals(0, client.outlierEjections());
		assertTrue(client.ejectedNodes().isEmpty());
		assertEquals(8, client.nodeCallStats().get(hung).timeouts());
	}
}
