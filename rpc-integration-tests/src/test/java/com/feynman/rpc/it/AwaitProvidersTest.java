package com.feynman.rpc.it;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.config.ReferenceOptions;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ReferenceOptions.awaitProviders}（D-015）在真实 ZooKeeper 上：消费者先订阅（目录为空、reference 阻塞），
 * 提供者随后注册，reference 由 watch 推送唤醒返回，返回后第一次调用即成功——这正是 pitfalls 第 29 条里
 * DeadlinePropagationTest 预热失败的场景（registered ≠ discovered）。
 */
class AwaitProvidersTest {

	public interface Echo {
		String echo(String text);
	}

	/** ZK watch 推送（TestingServer、同机）留出的上限：远大于实际的几十毫秒，又远小于 awaitProviders 的 30s 预算。 */
	private static final Duration PUSH_LATENCY_BOUND = Duration.ofSeconds(5);

	@Test
	void referenceWaitsForTheWatchPushThenTheFirstCallSucceeds() throws Exception {
		try (ZkFixture zk = new ZkFixture();
				RpcClient client = RpcClient.builder().registry(zk.registry()).build();
				RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).build()) {
			CompletableFuture<Echo> reference = new CompletableFuture<>();
			Thread waiter = Thread.ofPlatform().name("it-await-providers").start(() -> {
				try {
					reference.complete(client.reference(Echo.class,
							ReferenceOptions.defaults().awaitProviders(Duration.ofSeconds(30))));
				} catch (Throwable t) {
					reference.completeExceptionally(t);
				}
			});
			await("reference blocks on an empty directory").atMost(10, TimeUnit.SECONDS)
					.until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
			assertFalse(reference.isDone());

			server.export(Echo.class, text -> "echo:" + text);
			server.start();
			server.registered().get(10, TimeUnit.SECONDS);
			long registeredAt = System.nanoTime();

			Echo echo = reference.get(PUSH_LATENCY_BOUND.toMillis(), TimeUnit.MILLISECONDS);
			long afterRegister = System.nanoTime() - registeredAt;
			assertTrue(afterRegister < PUSH_LATENCY_BOUND.toNanos(), "woke " + afterRegister / 1_000_000 + "ms after registration");
			assertFalse(client.providers(ServiceKey.of(Echo.class)).isEmpty());
			assertEquals("echo:x", echo.echo("x"));
			waiter.join(5_000);
		}
	}
}
