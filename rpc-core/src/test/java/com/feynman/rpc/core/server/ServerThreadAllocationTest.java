package com.feynman.rpc.core.server;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.client.NettyClient;
import com.feynman.rpc.core.client.RequestWriter;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.MethodSignature;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SerializationBomb;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;
import com.feynman.rpc.core.testutil.ThreadRecordingAllocator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.buffer.ByteBufUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端业务线程不碰池化分配器（docs/pitfalls.md 第 32 条，响应侧）：业务执行器是虚拟线程时，回包（正常结果与错误信封）
 * 只在业务线程上序列化成字节，整帧由编码器在连接的事件循环（{@code rpc-server-worker-*}）上分配；序列化返回值时抛出的
 * Error 回 SERIALIZATION_ERROR、不泄漏、连接仍可用。
 *
 * <p>直接驱动 {@link NettyServer}（真实 socket），给已接受的连接注入一个记录"在哪个线程上分配"的分配器；
 * 业务线程一侧的类不调用分配器由 {@code CoreArchitectureTest} 的静态规则保证。客户端一侧见 CallerThreadAllocationTest。
 */
class ServerThreadAllocationTest {

	public interface Echo {
		byte[] echo(byte[] payload);

		String fail(String message);

		Object detonate(String kind);

		String rude(String message);
	}

	static final AtomicInteger VIRTUAL_INVOCATIONS = new AtomicInteger();
	static final AtomicInteger PLATFORM_INVOCATIONS = new AtomicInteger();

	/** getMessage 本身抛 Error 的业务异常：组装错误信封时就会失败。 */
	static final class RudeException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		@Override
		public String getMessage() {
			throw new StackOverflowError("simulated: message too deep");
		}
	}

	static final class EchoImpl implements Echo {
		private static void count() {
			(Thread.currentThread().isVirtual() ? VIRTUAL_INVOCATIONS : PLATFORM_INVOCATIONS).incrementAndGet();
		}

		@Override
		public byte[] echo(byte[] payload) {
			count();
			return payload;
		}

		@Override
		public String fail(String message) {
			count();
			throw new IllegalStateException(message);
		}

		@Override
		public Object detonate(String kind) {
			count();
			return new SerializationBomb(kind.equals("oom")
					? new OutOfMemoryError("simulated: direct buffer memory exhausted while serializing")
					: new StackOverflowError("simulated: object graph too deep"));
		}

		@Override
		public String rude(String message) {
			count();
			throw new RudeException();
		}
	}

	private static final ServiceKey KEY = ServiceKey.of(Echo.class);
	private static final SimpleTestSerializer SERIALIZER = new SimpleTestSerializer();
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private static NettyServer server;
	private static ThreadRecordingAllocator allocator;
	private static NettyClient client;
	private static String address;

	@BeforeAll
	static void start() {
		Serializers serializers = Serializers.load(SimpleTestSerializer.NAME, Map.of());
		ServiceDispatcher dispatcher = new ServiceDispatcher(List.of(), serializers, true);
		dispatcher.register(KEY, Echo.class, new EchoImpl());
		allocator = new ThreadRecordingAllocator("rpc-server-worker");
		server = new NettyServer(dispatcher, ExecutorConfig.virtualThreads(1000), SERIALIZER.code(), allocator);
		int port = server.bind(0).getPort();
		client = new NettyClient(Serializers.load(SimpleTestSerializer.NAME, Map.of()), 2, new LoadStats());
		ServiceInstance instance = new ServiceInstance(KEY, "127.0.0.1", port, Map.of());
		client.updateConnections(List.of(instance));
		address = instance.address();
	}

	@AfterAll
	static void stop() {
		if (client != null) {
			client.close();
		}
		if (server != null) {
			server.close();
		}
	}

	private static RequestWriter call(String method, Class<?> paramType, Object arg) {
		String paramSig;
		try {
			Method m = Echo.class.getMethod(method, paramType);
			paramSig = MethodSignature.paramSig(m);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
		return serializer -> EnvelopeCodec.request(KEY.interfaceName(), KEY.version(), KEY.group(), method, paramSig,
				List.of(serializer.serialize(arg, paramType)));
	}

	private static RpcMessage send(RequestWriter writer) {
		return client.send(address, writer, Map.of(), TIMEOUT.toNanos()).orTimeout(10, TimeUnit.SECONDS).join();
	}

	private static byte[] echoed(RpcMessage response) {
		try {
			assertEquals(Status.OK, response.status(), () -> "unexpected status: " + response);
			ByteBuf value = EnvelopeCodec.readValue(response.body());
			return (byte[]) SERIALIZER.deserialize(ByteBufUtil.getBytes(value), byte[].class);
		} finally {
			response.release();
		}
	}

	private static EnvelopeCodec.ErrorEnvelope error(RpcMessage response, Status expected) {
		try {
			assertEquals(expected, response.status(), () -> "unexpected status: " + response);
			return EnvelopeCodec.readError(response.body());
		} finally {
			response.release();
		}
	}

	private static void assertEchoStillWorks() {
		assertArrayEquals(new byte[] { 7 }, echoed(send(call("echo", byte[].class, new byte[] { 7 }))),
				"the connection must stay usable");
	}

	private static void assertOnlyEventLoopAllocated() {
		assertTrue(allocator.allocations() > 0, "the recording allocator must have been used by the server's connections");
		assertEquals(Set.of(), allocator.offendingThreads(),
				"replies must only be allocated on the server's event-loop threads (rpc-server-worker-*), never on business threads");
	}

	@Test
	void virtualThreadBusinessExecutorNeverAllocatesNettyBuffers() throws Exception {
		allocator.reset();
		int virtualBefore = VIRTUAL_INVOCATIONS.get();
		byte[] payload = new byte[4096]; // 比默认初始容量（256）大：旧实现会在业务线程上扩容（PoolArena.reallocate）
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) i;
		}
		int callers = 200;
		int callsPerCaller = 10;
		AtomicInteger bizExceptions = new AtomicInteger();
		try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Void>> done = new ArrayList<>();
			for (int c = 0; c < callers; c++) {
				int caller = c;
				done.add(CompletableFuture.runAsync(() -> {
					for (int i = 0; i < callsPerCaller; i++) {
						assertArrayEquals(payload, echoed(send(call("echo", byte[].class, payload))));
					}
					if (caller % 10 == 0) {
						// 若干业务异常：错误信封（类名 + 远端堆栈）同样不在业务线程上分配
						EnvelopeCodec.ErrorEnvelope e = error(send(call("fail", String.class, "库存不足-" + caller)), Status.BIZ_EXCEPTION);
						assertEquals(IllegalStateException.class.getName(), e.className());
						assertEquals("库存不足-" + caller, e.message());
						assertTrue(e.remoteStack().contains("EchoImpl.fail"), e.remoteStack());
						bizExceptions.incrementAndGet();
					}
				}, users));
			}
			CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
		}
		assertEquals(callers / 10, bizExceptions.get());
		assertEquals(callers * callsPerCaller + callers / 10, VIRTUAL_INVOCATIONS.get() - virtualBefore,
				"every business method must have run on a virtual thread");
		assertEquals(0, PLATFORM_INVOCATIONS.get());
		assertOnlyEventLoopAllocated();
		assertEquals(0, client.pendingRequests());
	}

	@Test
	void anErrorWhileSerializingTheResultRepliesSerializationErrorAndKeepsTheConnection() {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			allocator.reset();
			for (String kind : List.of("stack", "oom")) {
				// 以前：Error 让回包 future 异常完成，ServerHandler 没有可写的回包，客户端只能等到超时
				EnvelopeCodec.ErrorEnvelope e = error(send(call("detonate", String.class, kind)), Status.SERIALIZATION_ERROR);
				assertTrue(e.message().contains(kind.equals("oom") ? "OutOfMemoryError" : "StackOverflowError"), e.message());
				assertEchoStillWorks();
			}
			assertOnlyEventLoopAllocated();
			assertEquals(0, client.pendingRequests());
			probe.assertNoLeaks();
		}
	}

	@Test
	void anErrorWhileBuildingTheErrorReplyFallsBackToServerErrorAndKeepsTheConnection() {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			allocator.reset();
			EnvelopeCodec.ErrorEnvelope e = error(send(call("rude", String.class, "x")), Status.SERVER_ERROR);
			assertTrue(e.message().startsWith("failed to build reply: java.lang.StackOverflowError"), e.message());
			assertFalse(e.message().contains("simulated"), "the fallback must not touch the failing exception again");
			assertEchoStillWorks();
			assertOnlyEventLoopAllocated();
			assertEquals(0, client.pendingRequests());
			probe.assertNoLeaks();
		}
	}
}
