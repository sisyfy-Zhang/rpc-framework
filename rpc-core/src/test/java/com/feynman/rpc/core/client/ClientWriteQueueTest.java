package com.feynman.rpc.core.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.internal.ConnectionWriteQueue;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.MethodSignature;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.channel.Channel;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端写出路径在写队列开 / 关两种配置下保持同样的逐条语义（{@code RpcClientConfig.writeQueue}）：排队期间已超时的请求不写出；
 * 排队期间连接断开时队列里的请求立即失败（不挂到超时）、在途表与连接上的 requestId 集合不残留；客户端关闭时排队的请求以
 * "RpcClient closed" 失败。用一个占住连接事件循环的任务制造"排队中"的窗口。
 */
class ClientWriteQueueTest {

	public interface Echo {
		String echo(String s);
	}

	private static final ServiceKey KEY = ServiceKey.of(Echo.class);
	private static final String PARAM_SIG = MethodSignature.paramSig(echoMethod());

	private final AtomicInteger invocations = new AtomicInteger();
	private RpcServer server;
	private NettyClient client;
	private String address;
	private Channel channel;

	private static Method echoMethod() {
		try {
			return Echo.class.getMethod("echo", String.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private void start(boolean writeQueue) {
		server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.executor(ExecutorConfig.fixed(4)).build();
		server.export(Echo.class, s -> {
			invocations.incrementAndGet();
			return s;
		});
		server.start();
		client = new NettyClient(Serializers.load(SimpleTestSerializer.NAME, Map.of()), 1, new LoadStats(), null, writeQueue);
		ServiceInstance instance = new ServiceInstance(KEY, "127.0.0.1", server.port(), Map.of());
		client.updateConnections(List.of(instance));
		address = instance.address();
		await().atMost(10, TimeUnit.SECONDS).until(() -> client.activeConnections(address) == 1);
		channel = client.activeChannels(address).get(0);
		if (writeQueue) {
			assertNotNull(channel.attr(NettyClient.WRITE_QUEUE).get(), "writeQueue(true) installs a queue on every connection");
		} else {
			assertNull(channel.attr(NettyClient.WRITE_QUEUE).get(), "writeQueue(false) leaves it out");
		}
		// 先完成一次调用：协商结束、连接空闲
		assertEquals("warm", call("warm", TimeUnit.SECONDS.toNanos(10)).join());
		invocations.set(0);
	}

	@AfterEach
	void stop() {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.close();
		}
	}

	private CompletableFuture<String> call(String value, long timeoutNanos) {
		RequestWriter writer = serializer -> EnvelopeCodec.request(KEY.interfaceName(), KEY.version(), KEY.group(), "echo", PARAM_SIG,
				List.of(serializer.serialize(value, String.class)));
		return client.send(address, writer, Map.of(), timeoutNanos).thenApply(response -> {
			try {
				return (String) new SimpleTestSerializer().deserialize(
						io.netty.buffer.ByteBufUtil.getBytes(EnvelopeCodec.readValue(response.body())), String.class);
			} finally {
				response.release();
			}
		});
	}

	/** 占住连接的事件循环；{@code thenOnLoop} 在放行后、仍在这个任务里执行（例如关连接）。 */
	private CountDownLatch blockEventLoop(Runnable thenOnLoop) throws InterruptedException {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch blocked = new CountDownLatch(1);
		channel.eventLoop().execute(() -> {
			blocked.countDown();
			try {
				release.await(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			thenOnLoop.run();
		});
		assertTrue(blocked.await(5, TimeUnit.SECONDS));
		return release;
	}

	private int queued() {
		ConnectionWriteQueue queue = channel.attr(NettyClient.WRITE_QUEUE).get();
		return queue == null ? 0 : queue.size();
	}

	private static Throwable failure(CompletableFuture<?> future) {
		CompletionException e = assertThrows(CompletionException.class, future::join);
		return e.getCause();
	}

	@ParameterizedTest(name = "writeQueue={0}")
	@ValueSource(booleans = { true, false })
	void aRequestThatTimesOutWhileQueuedIsNeverWritten(boolean writeQueue) throws Exception {
		start(writeQueue);
		CountDownLatch release = blockEventLoop(() -> {
		});
		CompletableFuture<String> late = call("late", TimeUnit.MILLISECONDS.toNanos(100));
		assertInstanceOf(TimeoutException.class, failure(late));
		if (writeQueue) {
			assertEquals(1, queued(), "still queued behind the busy event loop");
		}
		release.countDown();
		// 之后的请求照常；排在它前面的超时请求没有被写出（服务端只执行了这一次）
		assertEquals("next", call("next", TimeUnit.SECONDS.toNanos(10)).join());
		TimeUnit.MILLISECONDS.sleep(200);
		assertEquals(1, invocations.get(), "the timed-out request must not reach the server");
		assertEquals(0, queued());
		assertEquals(0, client.pendingRequests());
		assertTrue(ChannelRequests.of(channel).isEmpty(), "no request id left on the connection");
	}

	@ParameterizedTest(name = "writeQueue={0}")
	@ValueSource(booleans = { true, false })
	void queuedRequestsFailImmediatelyWhenTheConnectionClosesBeforeTheyAreWritten(boolean writeQueue) throws Exception {
		start(writeQueue);
		Channel ch = channel;
		CountDownLatch release = blockEventLoop(ch::close);
		List<CompletableFuture<String>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			calls.add(call("q" + i, TimeUnit.SECONDS.toNanos(30)));
		}
		if (writeQueue) {
			assertEquals(10, queued());
		}
		long start = System.nanoTime();
		release.countDown();
		for (CompletableFuture<String> c : calls) {
			Throwable cause = failure(c);
			assertInstanceOf(RpcException.class, cause);
			assertTrue(cause.getMessage().contains("closed before"), cause.getMessage());
		}
		assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "failed right away, not at the 30s timeout");
		assertFalse(ch.isActive());
		assertEquals(0, invocations.get(), "none of them was written");
		await().atMost(5, TimeUnit.SECONDS).until(() -> client.pendingRequests() == 0);
		ConnectionWriteQueue queue = ch.attr(NettyClient.WRITE_QUEUE).get();
		assertEquals(0, queue == null ? 0 : queue.size(), "queue drained");
		await().atMost(5, TimeUnit.SECONDS).until(() -> ChannelRequests.of(ch).isEmpty());
	}

	@ParameterizedTest(name = "writeQueue={0}")
	@ValueSource(booleans = { true, false })
	void closingTheClientFailsQueuedRequests(boolean writeQueue) throws Exception {
		start(writeQueue);
		CountDownLatch release = blockEventLoop(() -> {
		});
		List<CompletableFuture<String>> calls = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			calls.add(call("c" + i, TimeUnit.SECONDS.toNanos(30)));
		}
		Thread closer = new Thread(client::close, "closer");
		closer.start();
		try {
			for (CompletableFuture<String> c : calls) {
				Throwable cause = failure(c);
				assertInstanceOf(RpcException.class, cause);
				assertEquals("RpcClient closed", cause.getMessage());
			}
		} finally {
			release.countDown();
		}
		closer.join(15_000);
		assertFalse(closer.isAlive());
		assertEquals(0, client.pendingRequests());
		assertEquals(0, invocations.get(), "nothing queued was written after the client failed it");
		assertEquals(0, queued());
		client = null;
	}
}
