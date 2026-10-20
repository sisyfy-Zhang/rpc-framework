package com.feynman.rpc.core;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 F3：异步方法的回调（{@code thenApply} 等）缺省在客户端 IO 线程上执行；在里面调同步方法会阻塞事件循环，
 * 嵌套调用的响应若也由这条事件循环读取就永远读不到（以前要等到 20s 超时）。同步入口现在直接抛 {@link IllegalStateException}，
 * 事件循环不被卡住；异步方法（链式发起）与普通线程上的同步调用不受影响。
 *
 * <p>客户端只有 1 个 IO 线程、1 条连接：嵌套调用一定落在同一条事件循环上，正是会卡死的形态。
 */
class SyncCallOnClientEventLoopTest {

	public interface Svc {
		String echo(String s);

		CompletableFuture<String> echoAsync(String s);
	}

	private RpcServer server;
	private RpcClient client;
	private Svc svc;

	@BeforeEach
	void start() {
		String ns = "ns-" + UUID.randomUUID();
		server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).build();
		server.export(Svc.class, new Svc() {
			@Override
			public String echo(String s) {
				return s;
			}

			@Override
			public CompletableFuture<String> echoAsync(String s) {
				// 晚一点完成：保证客户端的 thenApply 在响应到达之前已经挂上，回调确实在 IO 线程上执行
				return CompletableFuture.supplyAsync(() -> s, CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS));
			}
		});
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join();
		client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).ioThreads(1).connectionsPerNode(1)
				.defaultTimeout(Duration.ofSeconds(20)).registry(InMemoryRegistryFactory.config(ns)).build();
		svc = client.reference(Svc.class);
		assertEquals("warm", svc.echo("warm"));
	}

	@AfterEach
	void stop() {
		client.close();
		server.close();
	}

	@Test
	void aSyncCallInsideAnAsyncCallbackOnTheEventLoopFailsFast() throws Exception {
		AtomicReference<String> callbackThread = new AtomicReference<>();
		long start = System.nanoTime();
		CompletableFuture<String> nested = svc.echoAsync("a").thenApply(v -> {
			callbackThread.set(Thread.currentThread().getName());
			return svc.echo(v + "b");
		});
		ExecutionException e = assertThrows(ExecutionException.class, () -> nested.get(5, TimeUnit.SECONDS));
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertTrue(callbackThread.get().startsWith("rpc-client-io-"), "the callback ran on " + callbackThread.get());
		IllegalStateException cause = assertInstanceOf(IllegalStateException.class, e.getCause());
		assertTrue(cause.getMessage().contains("event-loop thread"), cause.getMessage());
		assertTrue(cause.getMessage().contains("async"), cause.getMessage());
		assertTrue(elapsedMs < 5000, "must fail fast instead of waiting for the 20s timeout: " + elapsedMs + "ms");
		assertEquals(0, client.inFlightCalls(), "the rejected call never entered the in-flight gate");
		// 事件循环没有被卡住：同一条连接上的调用照常完成
		assertEquals("still alive", svc.echo("still alive"));
	}

	@Test
	void asyncCallsChainedOnTheEventLoopStillWork() {
		String result = svc.echoAsync("a").thenCompose(v -> svc.echoAsync(v + "b")).orTimeout(5, TimeUnit.SECONDS).join();
		assertEquals("ab", result);
	}

	@Test
	void switchingToAnotherExecutorMakesTheSyncCallLegal() {
		String result = svc.echoAsync("a").thenApplyAsync(v -> svc.echo(v + "b")).orTimeout(5, TimeUnit.SECONDS).join();
		assertEquals("ab", result);
	}

	@Test
	void syncCallsOnOrdinaryAndVirtualThreadsAreUnaffected() throws Exception {
		assertEquals("plain", svc.echo("plain"));
		try (var virtual = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			assertEquals("virtual", CompletableFuture.supplyAsync(() -> svc.echo("virtual"), virtual).get(5, TimeUnit.SECONDS));
		}
	}
}
