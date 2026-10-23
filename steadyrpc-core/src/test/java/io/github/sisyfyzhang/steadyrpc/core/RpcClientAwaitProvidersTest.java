package io.github.sisyfyzhang.steadyrpc.core;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReferenceOptions#awaitProviders(Duration)}（D-015）：registered ≠ discovered 的框架级解法。
 * 注册中心用进程内实现，"推送"在注册时同步发生，所以"先订阅后注册"场景下 reference 应当几乎立即返回。
 */
class RpcClientAwaitProvidersTest {

	public interface Greeter {
		String greet(String name);
	}

	/** 进程内注册中心的推送是同步的，留给线程调度与唤醒的上限。 */
	private static final Duration PUSH_LATENCY_BOUND = Duration.ofSeconds(2);

	private static String namespace() {
		return "await-" + UUID.randomUUID();
	}

	private static RpcClient client(String ns) {
		return RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build();
	}

	private static RpcServer server(String ns) {
		return RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).executor(ExecutorConfig.fixed(2)).build();
	}

	@Test
	void subscribeFirstThenRegisterReturnsAsSoonAsTheProviderIsPushed() throws Exception {
		String ns = namespace();
		try (RpcClient client = client(ns); RpcServer server = server(ns)) {
			ReferenceOptions options = ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(20));
			CompletableFuture<Greeter> reference = new CompletableFuture<>();
			Thread waiter = Thread.ofPlatform().name("await-providers-waiter").start(() -> {
				try {
					reference.complete(client.reference(Greeter.class, options));
				} catch (Throwable t) {
					reference.completeExceptionally(t);
				}
			});
			// 消费者先订阅：reference 线程进入等待（列表为空）之后再起提供者
			await().atMost(5, TimeUnit.SECONDS).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
			assertFalse(reference.isDone(), "reference must block while the provider list is empty");

			server.export(Greeter.class, name -> "hi " + name);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			long registeredAt = System.nanoTime();

			Greeter greeter = reference.get(PUSH_LATENCY_BOUND.toMillis(), TimeUnit.MILLISECONDS);
			long waitedAfterRegister = System.nanoTime() - registeredAt;
			assertTrue(waitedAfterRegister < PUSH_LATENCY_BOUND.toNanos(),
					"reference returned " + waitedAfterRegister / 1_000_000 + "ms after registration");
			// 返回时提供者视图已非空：紧随其后的第一次调用不会撞上 "No available provider"
			assertFalse(client.providers(ServiceKey.of(Greeter.class)).isEmpty());
			// 也不会撞上 "No connection pool"：ServiceDirectory 先执行 onChange（refreshConnections 建连接池）再唤醒等待者，
			// 所以 reference 返回时新地址的连接池已经存在（曾在负载高的全量构建里偶发，见 ServiceDirectoryTest 的时序用例）
			String reply = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> greeter.greet("bob"),
					"the first call right after reference() must find the connection pool");
			assertEquals("hi bob", reply);
			waiter.join(5_000);
		}
	}

	@Test
	void noProviderTimesOutWithRpcExceptionAndTheClientStillClosesCleanly() {
		String ns = namespace();
		RpcClient client = client(ns);
		Duration wait = Duration.ofMillis(300);
		long start = System.nanoTime();
		RpcException e = assertThrows(RpcException.class,
				() -> client.reference(Greeter.class, ReferenceOptions.defaults().awaitProviders(wait)));
		long elapsed = System.nanoTime() - start;
		assertTrue(elapsed >= wait.toNanos(), "must wait the full budget, waited " + elapsed / 1_000_000 + "ms");
		assertTrue(e.getMessage().contains(ServiceKey.of(Greeter.class).toString()), e.getMessage());
		assertTrue(e.getMessage().contains("300ms"), e.getMessage());
		assertTimeoutPreemptively(Duration.ofSeconds(10), client::close);
		assertEquals(0, client.inFlightCalls());
	}

	@Test
	void defaultDoesNotWaitAndCallsFailFastWithoutProviders() {
		String ns = namespace();
		assertNull(ReferenceOptions.defaults().awaitProviders(), "default must be 'do not wait'");
		try (RpcClient client = client(ns)) {
			long start = System.nanoTime();
			Greeter greeter = client.reference(Greeter.class);
			assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos(), "default reference must not block");
			RpcException e = assertThrows(RpcException.class, () -> greeter.greet("x"));
			assertTrue(e.getMessage().contains("No available provider"), e.getMessage());
		}
	}

	@Test
	void alreadyDiscoveredProvidersReturnImmediately() {
		String ns = namespace();
		try (RpcServer server = server(ns)) {
			server.export(Greeter.class, name -> "hi " + name);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			try (RpcClient client = client(ns)) {
				Greeter greeter = assertTimeoutPreemptively(Duration.ofSeconds(5),
						() -> client.reference(Greeter.class, ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(30))));
				assertEquals("hi amy", greeter.greet("amy"));
			}
		}
	}

	@Test
	void awaitProvidersOptionValidation() {
		assertThrows(IllegalArgumentException.class, () -> ReferenceOptions.defaults().awaitProviders(Duration.ofMillis(-1)));
		assertNull(ReferenceOptions.defaults().awaitProviders(Duration.ZERO).awaitProviders(), "ZERO means do not wait");
		assertEquals(Duration.ofSeconds(3), ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(3)).awaitProviders());
		// 其余 with-er 保留 awaitProviders
		assertEquals(Duration.ofSeconds(3), ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(3))
				.version("1").group("g").timeout(Duration.ofSeconds(1)).retries(1).inheritDeadline(false).awaitProviders());
		// 5 参构造器（引入 awaitProviders 之前的源码）等价于不等待
		assertNull(new ReferenceOptions("1", "g", null, 0, true).awaitProviders());
	}
}
