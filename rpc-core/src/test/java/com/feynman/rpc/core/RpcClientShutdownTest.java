package com.feynman.rpc.core;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消费端优雅停机：
 * <ul>
 *   <li>{@code client.close()} 期间在途调用正常返回、新调用抛 IllegalStateException、close 后在途为零；</li>
 *   <li>在途超过 shutdownTimeout 的调用以 RpcException 失败，close 不会无限等；</li>
 *   <li>provider 从注册中心摘除（进程仍在服务）时，本端正在进行的调用正常返回；该地址的连接不立即关闭，
 *       在途归零后才关闭（activeConnections 由 &gt;0 变 0）。</li>
 * </ul>
 * 注册中心用进程内实现：可以只摘注册、不停服务端，把"消费端排空"与"服务端停机"分开验证。
 */
class RpcClientShutdownTest {

	public interface Slow {
		String slow(Integer millis);
	}

	static final class SlowImpl implements Slow {
		volatile CountDownLatch started = new CountDownLatch(1);

		@Override
		public String slow(Integer millis) {
			started.countDown();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "done:" + millis;
		}
	}

	private static String namespace() {
		return "ns-" + UUID.randomUUID();
	}

	private static RpcServer server(String ns) {
		return RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(InMemoryRegistryFactory.config(ns))
				.executor(ExecutorConfig.fixed(4)).build();
	}

	@Test
	void closeWaitsForInFlightCallsAndRejectsNewOnes() throws Exception {
		String ns = namespace();
		SlowImpl impl = new SlowImpl();
		try (RpcServer server = server(ns)) {
			server.export(Slow.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build();
			Slow slow = client.reference(Slow.class);
			assertEquals("done:1", slow.slow(1)); // 预热连接

			impl.started = new CountDownLatch(1);
			CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(() -> slow.slow(400));
			assertTrue(impl.started.await(5, TimeUnit.SECONDS));
			assertEquals(1, client.inFlightCalls());

			CompletableFuture<Void> closing = CompletableFuture.runAsync(client::close);
			await().atMost(2, TimeUnit.SECONDS).until(() -> {
				try {
					slow.slow(1);
					return false;
				} catch (IllegalStateException e) {
					return true; // 关闸之后的新调用：使用错误，立即抛出，不发到网络上
				}
			});
			assertEquals("done:400", inFlight.get(5, TimeUnit.SECONDS), "the in-flight call completes normally");
			closing.get(5, TimeUnit.SECONDS);
			assertEquals(0, client.inFlightCalls());
			assertEquals(0, client.pendingRequests());
			assertThrows(IllegalStateException.class, () -> client.reference(Slow.class));
		}
	}

	@Test
	void callsStillInFlightAfterShutdownTimeoutFailWithRpcException() throws Exception {
		String ns = namespace();
		SlowImpl impl = new SlowImpl();
		try (RpcServer server = server(ns)) {
			server.export(Slow.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns))
					.shutdownTimeout(Duration.ofMillis(200)).build();
			Slow slow = client.reference(Slow.class);
			impl.started = new CountDownLatch(1);
			CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(() -> slow.slow(3000));
			assertTrue(impl.started.await(5, TimeUnit.SECONDS));

			long start = System.nanoTime();
			client.close();
			long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			assertTrue(closeMillis < 1500, "close waited " + closeMillis + "ms, shutdownTimeout is 200ms");
			Throwable failure = assertThrows(Exception.class, () -> inFlight.get(5, TimeUnit.SECONDS)).getCause();
			assertTrue(failure instanceof RpcException, String.valueOf(failure));
			assertEquals(0, client.inFlightCalls());
			assertEquals(0, client.pendingRequests());
		}
	}

	@Test
	void unregisteredProviderKeepsItsConnectionsUntilInFlightCallsComplete() throws Exception {
		String ns = namespace();
		SlowImpl impl = new SlowImpl();
		try (RpcServer server = server(ns);
				RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns))
						.emptyProtection(false).build()) {
			server.export(Slow.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			Slow slow = client.reference(Slow.class);
			assertEquals("done:1", slow.slow(1));
			String address = "127.0.0.1:" + server.port();
			await().atMost(5, TimeUnit.SECONDS).until(() -> client.activeConnections(address) == 2);

			impl.started = new CountDownLatch(1);
			CompletableFuture<String> inFlight = CompletableFuture.supplyAsync(() -> slow.slow(500));
			assertTrue(impl.started.await(5, TimeUnit.SECONDS));

			// provider 摘注册（优雅下线第一步），进程还在服务
			ServiceInstance instance = client.providers(ServiceKey.of(Slow.class)).get(0);
			InMemoryRegistryFactory.unregister(ns, instance);
			assertTrue(client.providers(ServiceKey.of(Slow.class)).isEmpty(), "the push removed the provider");
			int whileDraining = client.activeConnections(address);
			System.out.println("[client-drain] provider unregistered with 1 call in flight; active connections = " + whileDraining);
			assertTrue(whileDraining > 0, "connections of an unregistered provider must stay open while calls are in flight");
			// 新调用不会再选到它
			RpcException noProvider = assertThrows(RpcException.class, () -> slow.slow(1));
			assertTrue(noProvider.getMessage().contains("No available provider"), noProvider.getMessage());

			assertEquals("done:500", inFlight.get(5, TimeUnit.SECONDS), "the in-flight call completes normally");
			long drainedAt = System.nanoTime();
			await().atMost(2, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(10)).until(() -> client.activeConnections(address) == 0);
			System.out.println("[client-drain] connections closed " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - drainedAt)
					+ "ms after the last in-flight call completed");
			assertEquals(0, client.pendingRequests());
		}
	}
}
