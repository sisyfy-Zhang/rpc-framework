package com.feynman.rpc.core;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExecutorConfig#direct()}（opt-in）：业务方法在收到请求的 IO 线程（{@code rpc-server-worker-*}）上执行。
 * 回显、异常映射、服务端上下文（attachments / deadline）的绑定与解绑、在途上限拒绝（SERVER_OVERLOADED）、优雅停机排空都与池化执行器一致。
 * 回包只在事件循环上分配的断言见 {@code server.DirectExecutorTransportTest}。
 */
class DirectExecutorTest {

	public interface Svc {
		String echo(String s);

		String threadName(String ignored);

		String fail(String message);

		String declared(String message) throws IOException;

		/** 服务端上下文：入站 attachment k 的值与是否有入站 deadline，形如 {@code v|true}。 */
		String context(String key);

		/** 返回测试持有的未完成 future：它在途但不占 IO 线程。 */
		CompletableFuture<String> held(String s);
	}

	private final AtomicReference<CompletableFuture<String>> holder = new AtomicReference<>(new CompletableFuture<>());
	private RpcServer server;
	private RpcClient client;

	private final class SvcImpl implements Svc {
		@Override
		public String echo(String s) {
			return s;
		}

		@Override
		public String threadName(String ignored) {
			return Thread.currentThread().getName();
		}

		@Override
		public String fail(String message) {
			throw new IllegalStateException(message);
		}

		@Override
		public String declared(String message) throws IOException {
			throw new IOException(message);
		}

		@Override
		public String context(String key) {
			return RpcContext.getServerAttachment(key) + "|" + RpcContext.serverDeadline().isPresent();
		}

		@Override
		public CompletableFuture<String> held(String s) {
			return holder.get();
		}
	}

	private Svc start(ExecutorConfig executor) {
		String ns = "ns-" + UUID.randomUUID();
		server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).executor(executor).ioThreads(1).build();
		server.export(Svc.class, new SvcImpl());
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join();
		client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(1)
				.defaultTimeout(Duration.ofSeconds(10)).registry(InMemoryRegistryFactory.config(ns)).build();
		return client.reference(Svc.class);
	}

	@AfterEach
	void stop() {
		holder.get().complete("released");
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.close();
		}
	}

	@Test
	void businessMethodsRunOnTheServerIoThreadAndEchoCorrectly() {
		Svc svc = start(ExecutorConfig.direct());
		for (int i = 0; i < 50; i++) {
			assertEquals("x" + i, svc.echo("x" + i));
		}
		String thread = svc.threadName("");
		assertTrue(thread.startsWith("rpc-server-worker-"), "direct runs the business method on the IO thread, got " + thread);
		assertEquals(0, server.runningBusinessTasks());
		assertEquals(0, server.inFlight());
	}

	@Test
	void exceptionsAreMappedAsWithAPool() {
		Svc svc = start(ExecutorConfig.direct());
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.fail("库存不足"));
		assertEquals("库存不足", e.getMessage());
		IOException io = assertThrows(IOException.class, () -> svc.declared("disk"));
		assertEquals("disk", io.getMessage());
		assertEquals("still fine", svc.echo("still fine"), "the IO thread survives business exceptions");
	}

	@Test
	void serverContextIsBoundForTheCallAndClearedFromTheIoThreadAfterwards() {
		Svc svc = start(ExecutorConfig.direct());
		RpcContext.setAttachment("k", "v");
		assertEquals("v|true", svc.context("k"), "attachments and the inbound deadline (DeadlineServerFilter) are visible");
		// 同一条连接、同一个 IO 线程（ioThreads = 1）：上一个请求的上下文不能残留在事件循环线程上
		assertEquals("null|true", svc.context("k"));
	}

	@Test
	void inFlightLimitRejectsWithServerOverloadedAndRecovers() throws Exception {
		Svc svc = start(ExecutorConfig.direct(1));
		CompletableFuture<String> first = svc.held("a");
		await().atMost(5, TimeUnit.SECONDS).until(() -> server.inFlight() == 1);
		assertEquals(0, server.runningBusinessTasks(), "an async method does not occupy the IO thread while its future is pending");
		RpcOverloadedException rejected = assertThrows(RpcOverloadedException.class, () -> svc.echo("b"));
		assertTrue(rejected.getMessage().contains("server overloaded"), rejected.getMessage());
		assertFalse(first.isDone());
		holder.get().complete("A");
		assertEquals("A", first.get(5, TimeUnit.SECONDS));
		await().atMost(5, TimeUnit.SECONDS).until(() -> server.inFlight() == 0);
		assertEquals("c", svc.echo("c"), "the permit is returned once the reply is handed to Netty");
	}

	@Test
	void gracefulShutdownStillDrainsInFlightRequests() throws Exception {
		Svc svc = start(ExecutorConfig.direct());
		CompletableFuture<String> pending = svc.held("a");
		await().atMost(5, TimeUnit.SECONDS).until(() -> server.inFlight() == 1);
		RpcServer closing = server;
		CompletableFuture<Void> closed = CompletableFuture.runAsync(closing::close);
		TimeUnit.MILLISECONDS.sleep(300);
		assertFalse(closed.isDone(), "close() waits for the in-flight request");
		holder.get().complete("drained");
		assertEquals("drained", pending.get(5, TimeUnit.SECONDS));
		closed.get(10, TimeUnit.SECONDS);
		assertFalse(closing.isRunning());
		CompletionException late = assertThrows(CompletionException.class, () -> svc.held("late").join());
		assertInstanceOf(RpcException.class, late.getCause());
	}
}
