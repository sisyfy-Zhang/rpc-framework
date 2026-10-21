package com.feynman.rpc.core;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.testutil.FailingRegistryFactory;
import com.feynman.rpc.core.testutil.FlakyRegistryFactory;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.LossyRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

/**
 * RpcServer 的后台注册状态机：start 不等注册、失败退避重试直到成功、registered() / isRegistered() 的语义、
 * start 之后 export 的服务、close 取消未完成的注册并结束注册线程。
 */
class RpcServerRegistrationTest {

	public interface Ping {
		String ping(String s);
	}

	public interface Pong {
		String pong(String s);
	}

	@Test
	void registeredCompletesAfterTransientFailures_retryingWithBackoff() throws Exception {
		int before = FlakyRegistryFactory.ATTEMPTS.get();
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(RegistryConfig.of("flaky", "2")).build()) {
			server.export(Ping.class, s -> s);
			long start = System.nanoTime();
			server.start();
			assertTrue((System.nanoTime() - start) / 1_000_000 < 1000, "start() must not wait for registration");
			assertFalse(server.isRegistered());

			// 两次失败（退避 500ms、1000ms）之后第三次成功
			server.registered().get(10, TimeUnit.SECONDS);
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			assertTrue(server.isRegistered());
			assertEquals(3, FlakyRegistryFactory.ATTEMPTS.get() - before);
			assertTrue(elapsed >= 1400, "expected backoff of ~1.5s before success, got " + elapsed + "ms");
			System.out.println("[registration] registered after 2 simulated failures in " + elapsed + "ms");
		}
	}

	@Test
	void exportAfterRegistrationRenewsTheFuture() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(InMemoryRegistryFactory.config(ns)).build()) {
			server.export(Ping.class, s -> s);
			server.start();
			server.registered().get(5, TimeUnit.SECONDS);
			assertTrue(server.isRegistered());

			server.export(Pong.class, s -> s);
			server.registered().get(5, TimeUnit.SECONDS);
			assertTrue(server.isRegistered());
			assertEquals(1, InMemoryRegistryFactory.instances(ns, ServiceKey.of(Pong.class)).size());
		}
	}

	/**
	 * 注册中心报告"已注册的节点丢失"（如 ZK 会话过期）时 isRegistered() 变 false，重建完成后恢复 true；
	 * registered() 只表示"曾经全部注册完"，不随之变化。
	 */
	@Test
	void reportedRegistrationLossFlipsIsRegisteredUntilRestored() throws Exception {
		String ns = "lossy-" + UUID.randomUUID();
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(RegistryConfig.of("lossy", ns)).build()) {
			server.export(Ping.class, s -> s);
			server.start();
			server.registered().get(5, TimeUnit.SECONDS);
			assertTrue(server.isRegistered());

			LossyRegistryFactory.lose(ns);
			assertFalse(server.isRegistered(), "registrations lost: not discoverable");
			assertTrue(server.registered().isDone(), "registered() means 'was fully registered once' and is not reset");

			LossyRegistryFactory.restore(ns);
			assertTrue(server.isRegistered());
		}
	}

	/** 不实现回调的 Registry（缺省 addRegistrationListener 返回 false）：注册成功之后 isRegistered() 一直为 true，行为不变。 */
	@Test
	void registryWithoutLossReportingKeepsTheOldBehaviour() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		assertFalse(new InMemoryRegistryFactory().create(InMemoryRegistryFactory.config(ns)).addRegistrationListener(null));
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(InMemoryRegistryFactory.config(ns)).build()) {
			server.export(Ping.class, s -> s);
			server.start();
			server.registered().get(5, TimeUnit.SECONDS);
			assertTrue(server.isRegistered());
			Thread.sleep(200);
			assertTrue(server.isRegistered());
		}
	}

	@Test
	void noRegistryConfiguredMeansRegisteredRightAfterStart() throws Exception {
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").build()) {
			CompletableFuture<Void> beforeStart = server.registered();
			assertFalse(beforeStart.isDone());
			assertFalse(server.isRegistered());
			server.export(Ping.class, s -> s);
			server.start();
			server.registered().get(1, TimeUnit.SECONDS);
			assertTrue(server.isRegistered());
		}
	}

	@Test
	void closeCancelsPendingRegistrationAndStopsTheRegistrationThread() throws Exception {
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(RegistryConfig.of("failing", "nowhere")).build();
		server.export(Ping.class, s -> s);
		server.start();
		CompletableFuture<Void> registered = server.registered();
		int before = FailingRegistryFactory.REGISTER_ATTEMPTS.get();
		await().atMost(5, TimeUnit.SECONDS).until(() -> FailingRegistryFactory.REGISTER_ATTEMPTS.get() > before);
		assertTrue(liveRegistrationThreads() > 0, "registration should run on its own thread while retrying");
		assertFalse(registered.isDone());

		server.close();

		ExecutionException e = assertThrows(ExecutionException.class, () -> registered.get(1, TimeUnit.SECONDS));
		assertInstanceOf(CancellationException.class, e.getCause());
		assertFalse(server.isRegistered());
		await().atMost(Duration.ofSeconds(5)).until(() -> liveRegistrationThreads() == 0);
		int afterClose = FailingRegistryFactory.REGISTER_ATTEMPTS.get();
		Thread.sleep(1500); // 超过一个退避周期：关闭后不再有重试
		assertEquals(afterClose, FailingRegistryFactory.REGISTER_ATTEMPTS.get(), "registration retried after close()");
	}

	@Test
	void unknownRegistryTypeFailsStartBeforeBinding() {
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).registry(RegistryConfig.of("no-such-registry", "x")).build();
		server.export(Ping.class, s -> s);
		assertThrows(RuntimeException.class, server::start);
		assertFalse(server.isRunning());
		server.close();
	}

	private static long liveRegistrationThreads() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(t -> t.isAlive() && t.getName().startsWith("rpc-server-registry-"))
				.count();
	}
}
