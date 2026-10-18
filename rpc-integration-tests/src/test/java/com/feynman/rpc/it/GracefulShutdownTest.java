package com.feynman.rpc.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcOverloadedException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.examples.provider.ProviderApplication;
import com.feynman.rpc.registry.zookeeper.ZookeeperRegistryFactory;
import com.feynman.rpc.starter.RpcService;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-2（进程内版）：服务端优雅下线。
 * <ul>
 *   <li>3 个各 300ms 的在途慢调用期间 {@code server.close()}：注册节点在第一个慢调用完成<b>之前</b>就从注册中心消失
 *       （用另一个 Registry 订阅观察）；close 期间到达的新请求收到 RpcOverloadedException（"server shutting down"）；
 *       3 个在途调用全部正常返回；close 总耗时 &lt; 300ms + 1s；之后 rpc- 线程为零。</li>
 *   <li>在途调用 2s、shutdownTimeout 500ms：close 约 500ms 返回，在途调用在客户端以 RpcException 失败（连接关闭即失败，
 *       不挂到超时）。</li>
 *   <li>Boot 变体：rpc-examples 的 provider 应用 {@code ConfigurableApplicationContext.close()} 走同样流程
 *       （通过 RpcServer 状态 DRAINING、注册节点先消失、在途完成、新请求被拒断言）。</li>
 * </ul>
 * Windows 上 {@code Process.destroy()} 是 TerminateProcess，不触发 JVM 关闭钩子，真实 SIGTERM 用例见 {@link SigtermShutdownTest}
 * （仅 Linux，CI 的 sigterm 作业）；这里验证的是 SIGTERM 之后实际执行的那条路径（SmartLifecycle.stop → RpcServer.close）。
 */
class GracefulShutdownTest {

	public interface SlowEcho {
		String slow(int millis);

		String ping();
	}

	static class SlowEchoImpl implements SlowEcho {
		volatile CountDownLatch started = new CountDownLatch(0);

		@Override
		public String slow(int millis) {
			started.countDown();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			}
			return "slow-" + millis;
		}

		@Override
		public String ping() {
			return "pong";
		}
	}

	/** Boot 变体里由组件注册暴露的同一个服务。 */
	@RpcService
	public static class BootSlowEcho extends SlowEchoImpl implements SlowEcho { // 直接实现接口：exporter 只看直接接口
	}

	/** 用一个独立的 Registry 订阅观察：记下"提供者从列表里消失"的时刻。 */
	static final class DisappearanceWatch implements AutoCloseable {
		private final Registry registry;
		private final AtomicLong goneAt = new AtomicLong();
		private final AtomicLong seenAt = new AtomicLong();

		DisappearanceWatch(ZkFixture zk, ServiceKey key, String address) {
			registry = new ZookeeperRegistryFactory().create(zk.registry());
			registry.subscribe(key, instances -> {
				boolean present = instances.stream().map(ServiceInstance::address).anyMatch(address::equals);
				if (present) {
					seenAt.compareAndSet(0, System.nanoTime());
				} else if (seenAt.get() != 0) {
					goneAt.compareAndSet(0, System.nanoTime());
				}
			});
			await("watcher sees " + address).atMost(10, TimeUnit.SECONDS).until(() -> seenAt.get() != 0);
		}

		long goneAt() {
			return goneAt.get();
		}

		@Override
		public void close() {
			registry.close();
		}
	}

	private static long millisSince(long nanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
	}

	@Test
	void closeUnregistersFirstRejectsNewRequestsAndLetsInFlightCallsFinish() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			SlowEchoImpl impl = new SlowEchoImpl();
			RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry())
					.executor(ExecutorConfig.fixed(4)).build();
			server.export(SlowEcho.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			String address = "127.0.0.1:" + server.port();

			try (RpcClient client = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(5)).build();
					DisappearanceWatch watch = new DisappearanceWatch(zk, ServiceKey.of(SlowEcho.class), address)) {
				SlowEcho echo = client.reference(SlowEcho.class);
				assertEquals("pong", echo.ping()); // 预热连接

				impl.started = new CountDownLatch(3);
				List<CompletableFuture<String>> slowCalls = new ArrayList<>();
				AtomicLong firstSlowDoneAt = new AtomicLong();
				for (int i = 0; i < 3; i++) {
					CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> echo.slow(300));
					call.whenComplete((r, e) -> firstSlowDoneAt.compareAndSet(0, System.nanoTime()));
					slowCalls.add(call);
				}
				assertTrue(impl.started.await(5, TimeUnit.SECONDS), "slow calls never started");
				assertEquals(3, server.inFlight());

				long closeStart = System.nanoTime();
				CompletableFuture<Void> closing = CompletableFuture.runAsync(server::close);
				await("server enters DRAINING").atMost(2, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(5))
						.until(() -> server.state() == RpcServer.State.DRAINING);
				// 推空保护让消费端仍保留这个地址：DRAINING 期间到达的请求被拒绝，业务没执行
				RpcOverloadedException rejected = assertThrows(RpcOverloadedException.class, echo::ping);
				System.out.println("[A0-2] new request during drain: " + rejected.getMessage());
				assertTrue(rejected.getMessage().contains("shutting down"), rejected.getMessage());

				for (CompletableFuture<String> call : slowCalls) {
					assertEquals("slow-300", call.get(5, TimeUnit.SECONDS), "in-flight call must complete normally");
				}
				closing.get(5, TimeUnit.SECONDS);
				long closeMillis = millisSince(closeStart);

				long unregisteredAt = watch.goneAt();
				assertTrue(unregisteredAt != 0, "the watcher never saw the provider disappear");
				long unregisteredAfter = TimeUnit.NANOSECONDS.toMillis(unregisteredAt - closeStart);
				long firstSlowDoneAfter = TimeUnit.NANOSECONDS.toMillis(firstSlowDoneAt.get() - closeStart);
				System.out.printf("[A0-2] close took %dms; provider gone from the registry %dms after close() began, "
						+ "first in-flight call completed at %dms; server state=%s, inFlight=%d%n",
						closeMillis, unregisteredAfter, firstSlowDoneAfter, server.state(), server.inFlight());
				assertTrue(unregisteredAt - firstSlowDoneAt.get() < 0,
						"the registry node must disappear before the first in-flight call completes");
				assertTrue(closeMillis < 300 + 1000, "close took " + closeMillis + "ms");
				assertEquals(RpcServer.State.CLOSED, server.state());
				assertEquals(0, server.inFlight());
			}
			await("no rpc- threads after close").atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
					.until(() -> ZkFixture.rpcThreads().isEmpty());
		}
	}

	/**
	 * deregisterGrace：摘注册之后仍处于 CLOSING、照常服务一段时间，再进入 DRAINING。断言节点消失先于 DRAINING、
	 * 两者间隔不短于宽限期，且宽限期内到达的请求正常返回（不是 SERVER_OVERLOADED）。
	 */
	@Test
	void deregisterGraceKeepsServingBetweenUnregisterAndDraining() throws Exception {
		Duration grace = Duration.ofMillis(800);
		try (ZkFixture zk = new ZkFixture()) {
			RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry())
					.deregisterGrace(grace).build();
			server.export(SlowEcho.class, new SlowEchoImpl());
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			String address = "127.0.0.1:" + server.port();

			try (RpcClient client = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(5)).build();
					DisappearanceWatch watch = new DisappearanceWatch(zk, ServiceKey.of(SlowEcho.class), address)) {
				SlowEcho echo = client.reference(SlowEcho.class);
				assertEquals("pong", echo.ping());

				long closeStart = System.nanoTime();
				CompletableFuture<Void> closing = CompletableFuture.runAsync(server::close);
				await("provider disappears from the registry").atMost(3, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(5))
						.until(() -> watch.goneAt() != 0);
				assertEquals(RpcServer.State.CLOSING, server.state(), "still serving during deregisterGrace");
				// 推空保护让消费端仍保留这个地址：模拟"推送还没传播到"的消费端，它的请求在宽限期内照常成功
				assertEquals("pong", echo.ping());
				await("server enters DRAINING after the grace period").atMost(3, TimeUnit.SECONDS)
						.pollInterval(Duration.ofMillis(5)).until(() -> server.state() != RpcServer.State.CLOSING);
				long drainingAt = System.nanoTime();
				closing.get(5, TimeUnit.SECONDS);

				long goneAfter = TimeUnit.NANOSECONDS.toMillis(watch.goneAt() - closeStart);
				long drainingAfter = TimeUnit.NANOSECONDS.toMillis(drainingAt - closeStart);
				System.out.printf("[deregisterGrace=%dms] provider gone at %dms, CLOSING -> DRAINING at %dms, close finished at %dms%n",
						grace.toMillis(), goneAfter, drainingAfter, millisSince(closeStart));
				assertTrue(drainingAt - watch.goneAt() >= grace.toNanos() - TimeUnit.MILLISECONDS.toNanos(100),
						"DRAINING must start at least deregisterGrace after the node disappeared");
				assertEquals(RpcServer.State.CLOSED, server.state());
			}
		}
	}

	@Test
	void inFlightCallsLongerThanShutdownTimeoutFailOnTheClientWhenCloseGivesUp() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			SlowEchoImpl impl = new SlowEchoImpl();
			RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry())
					.shutdownTimeout(Duration.ofMillis(500)).build();
			server.export(SlowEcho.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();

			try (RpcClient client = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(10)).build()) {
				SlowEcho echo = client.reference(SlowEcho.class);
				assertEquals("pong", echo.ping());
				impl.started = new CountDownLatch(1);
				CompletableFuture<String> longCall = CompletableFuture.supplyAsync(() -> echo.slow(2000));
				assertTrue(impl.started.await(5, TimeUnit.SECONDS));

				long start = System.nanoTime();
				server.close();
				long closeMillis = millisSince(start);
				ExecutionException failure = assertThrows(ExecutionException.class, () -> longCall.get(5, TimeUnit.SECONDS));
				long failedAfter = millisSince(start);
				System.out.printf("[A0-2] shutdownTimeout=500ms: close returned after %dms; the 2s call failed %dms after close() "
						+ "began with %s%n", closeMillis, failedAfter, failure.getCause());
				assertTrue(closeMillis >= 500 && closeMillis < 1500, "close took " + closeMillis + "ms");
				RpcException cause = assertInstanceOf(RpcException.class, failure.getCause());
				assertTrue(failedAfter < 1900, "the call must fail when the connection closes, not when it would have finished");
				assertTrue(cause.getMessage().contains("closed"), cause.getMessage());
				assertEquals(0, client.pendingRequests());
			}
		}
	}

	@Test
	void bootProviderContextCloseRunsTheSameGracefulShutdown() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			ConfigurableApplicationContext provider = ProviderApplication.builder()
					.sources(BootSlowEcho.class)
					.run("--rpc.registry.address=" + zk.connectString(), "--rpc.server.port=0", "--rpc.server.host=127.0.0.1",
							// provider.yml 设了 1s 宽限期；这里关掉，让慢调用在 DRAINING 期间完成（宽限期本身另有用例）
							"--rpc.server.deregister-grace=0ms");
			RpcServer server = provider.getBean(RpcServer.class);
			BootSlowEcho impl = provider.getBean(BootSlowEcho.class);
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			String address = "127.0.0.1:" + server.port();
			assertEquals(List.of(address), zk.providers(ServiceKey.of(SlowEcho.class)));

			try (RpcClient client = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(5)).build();
					DisappearanceWatch watch = new DisappearanceWatch(zk, ServiceKey.of(SlowEcho.class), address)) {
				SlowEcho echo = client.reference(SlowEcho.class);
				assertEquals("pong", echo.ping());
				impl.started = new CountDownLatch(1);
				AtomicLong slowDoneAt = new AtomicLong();
				CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> echo.slow(500));
				slow.whenComplete((r, e) -> slowDoneAt.set(System.nanoTime()));
				assertTrue(impl.started.await(5, TimeUnit.SECONDS));

				long closeStart = System.nanoTime();
				CompletableFuture<Void> closing = CompletableFuture.runAsync(provider::close);
				await("Boot shutdown reaches DRAINING").atMost(2, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(5))
						.until(() -> server.state() == RpcServer.State.DRAINING);
				RpcOverloadedException rejected = assertThrows(RpcOverloadedException.class, echo::ping);
				assertTrue(rejected.getMessage().contains("shutting down"), rejected.getMessage());

				assertEquals("slow-500", slow.get(5, TimeUnit.SECONDS), "in-flight call completes during context close");
				closing.get(10, TimeUnit.SECONDS);
				long goneAfter = TimeUnit.NANOSECONDS.toMillis(watch.goneAt() - closeStart);
				long slowDoneAfter = TimeUnit.NANOSECONDS.toMillis(slowDoneAt.get() - closeStart);
				System.out.printf("[A0-2 Boot] context.close(): provider gone from the registry at %dms, in-flight call done at %dms, "
						+ "close finished at %dms; server state=%s%n", goneAfter, slowDoneAfter, millisSince(closeStart), server.state());
				assertTrue(watch.goneAt() != 0 && watch.goneAt() - slowDoneAt.get() < 0,
						"the registry node must disappear before the in-flight call completes");
				assertEquals(RpcServer.State.CLOSED, server.state());
				assertTrue(zk.providers(ServiceKey.of(SlowEcho.class)).isEmpty());
			}
			await().atMost(5, TimeUnit.SECONDS).until(() -> ZkFixture.rpcThreads().isEmpty());
		}
	}
}
