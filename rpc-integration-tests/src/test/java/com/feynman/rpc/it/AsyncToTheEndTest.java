package com.feynman.rpc.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcContext;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ExecutorConfig;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-7：服务端异步到底。业务方法返回未完成的 future 时业务线程立即释放，future 完成时（在完成它的线程上）回包。
 * <ul>
 *   <li>{@code ExecutorConfig.fixed(1)} 下并发 5 个"200ms 后完成"的调用，总耗时 &lt; 600ms（join 阻塞时是 5 × 200ms 串行）；</li>
 *   <li>5 个 future 都未完成期间，业务执行器上运行中的任务数为 0（在途数为 5）；</li>
 *   <li>future 异常完成按异常映射规则回包；声明为 {@code CompletionStage<T>} 的方法同样异步；</li>
 *   <li>入站 attachments 在业务方法返回时解绑：同一个业务线程上的下一个请求看不到上一个请求的 attachments。</li>
 * </ul>
 */
class AsyncToTheEndTest {

	public interface Delayed {
		CompletableFuture<String> later(String tag, int delayMillis);

		CompletionStage<String> stage(String tag);

		CompletableFuture<String> failLater(String reason);

		String peekTag();
	}

	/** 完成 future 的线程不叫 rpc-*：结果在它上面序列化、回包。 */
	private static final ScheduledExecutorService COMPLETER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "test-completer");
		thread.setDaemon(true);
		return thread;
	});

	static final class DelayedImpl implements Delayed {
		@Override
		public CompletableFuture<String> later(String tag, int delayMillis) {
			String seenInSyncPart = RpcContext.getServerAttachment("tag"); // 同步部分可见
			CompletableFuture<String> future = new CompletableFuture<>();
			COMPLETER.schedule(() -> future.complete(tag + "|" + seenInSyncPart + "|" + Thread.currentThread().getName()),
					delayMillis, TimeUnit.MILLISECONDS);
			return future;
		}

		@Override
		public CompletionStage<String> stage(String tag) {
			return CompletableFuture.supplyAsync(() -> "stage:" + tag, CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS, COMPLETER));
		}

		@Override
		public CompletableFuture<String> failLater(String reason) {
			CompletableFuture<String> future = new CompletableFuture<>();
			COMPLETER.schedule(() -> future.completeExceptionally(new IllegalStateException(reason)), 50, TimeUnit.MILLISECONDS);
			return future;
		}

		@Override
		public String peekTag() {
			return RpcContext.getServerAttachment("tag");
		}
	}

	private static ZkFixture zk;
	private static RpcServer server;
	private static RpcClient client;
	private static Delayed delayed;

	@BeforeAll
	static void start() throws Exception {
		zk = new ZkFixture();
		server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).executor(ExecutorConfig.fixed(1)).build();
		server.export(Delayed.class, new DelayedImpl());
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join();
		client = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(5)).build();
		delayed = client.reference(Delayed.class);
		assertTrue(delayed.later("warmup", 1).get(5, TimeUnit.SECONDS).startsWith("warmup|"));
	}

	@AfterAll
	static void stop() throws Exception {
		client.close();
		server.close();
		zk.close();
	}

	@Test
	void businessThreadIsReleasedWhileFuturesArePending() throws Exception {
		long start = System.nanoTime();
		List<CompletableFuture<String>> calls = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			calls.add(delayed.later("c" + i, 200));
		}
		// 5 个请求都进入了服务端，而唯一的业务线程上没有任何任务在跑：它们都在等各自的 future
		await().atMost(2, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(5)).until(() -> server.inFlight() == 5);
		int running = server.runningBusinessTasks();
		int inFlight = server.inFlight();
		for (int i = 0; i < 5; i++) {
			String value = calls.get(i).get(5, TimeUnit.SECONDS);
			assertTrue(value.startsWith("c" + i + "|"), value);
			assertTrue(value.endsWith("|test-completer"), "the future was completed (and replied) on the completer thread: " + value);
		}
		long totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		System.out.printf("[A0-7] fixed(1): 5 concurrent 200ms async calls took %dms; while pending: inFlight=%d, running on business threads=%d%n",
				totalMillis, inFlight, running);
		assertEquals(0, running, "business thread must not be held by pending futures");
		assertTrue(totalMillis < 600, "5 × 200ms on one business thread took " + totalMillis + "ms: the thread was blocked");
		await().atMost(2, TimeUnit.SECONDS).until(() -> server.inFlight() == 0);
	}

	@Test
	void exceptionalCompletionAndCompletionStageAreMappedLikeSyncResults() {
		ExecutionException e = assertThrows(ExecutionException.class, () -> delayed.failLater("out of stock").get(5, TimeUnit.SECONDS));
		IllegalStateException cause = assertInstanceOf(IllegalStateException.class, e.getCause());
		assertEquals("out of stock", cause.getMessage());
		assertEquals("stage:x", delayed.stage("x").toCompletableFuture().join());
	}

	@Test
	void inboundAttachmentsAreUnboundWhenTheBusinessMethodReturns() throws Exception {
		RpcContext.setAttachment("tag", "t-1");
		CompletableFuture<String> pending = delayed.later("a", 300);
		// 同一个（唯一的）业务线程上的下一个请求：上一个请求的 attachments 必须已经解绑，哪怕它的 future 还没完成
		assertNull(delayed.peekTag(), "attachments of the previous request leaked to the next one on the same thread");
		assertEquals("a|t-1|test-completer", pending.get(5, TimeUnit.SECONDS), "but they were visible in the synchronous part");
	}
}
