package com.feynman.rpc.core.client;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcSerializationException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.MethodSignature;
import com.feynman.rpc.core.protocol.OutboundBody;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用方线程不碰池化分配器（docs/pitfalls.md 第 32 条）：请求只在调用方线程上序列化，帧缓冲区由编码器在事件循环上分配；
 * 发送路径上任何 Throwable 都让请求立即失败，不泄漏缓冲区、不挂到超时。
 *
 * <p>直接驱动 {@link NettyClient}（真实 socket、真实 RpcServer），给连接注入一个记录"在哪个线程上分配"的分配器。
 * RpcClient / ReferenceProxy 一侧不分配缓冲区由 {@code CoreArchitectureTest} 的静态规则保证。
 */
class CallerThreadAllocationTest {

	public interface Echo {
		byte[] echo(byte[] payload);
	}

	static final AtomicInteger INVOCATIONS = new AtomicInteger();

	static final class EchoImpl implements Echo {
		@Override
		public byte[] echo(byte[] payload) {
			INVOCATIONS.incrementAndGet();
			return payload;
		}
	}

	private static final ServiceKey KEY = ServiceKey.of(Echo.class);
	private static final String PARAM_SIG = MethodSignature.paramSig(echoMethod());
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private static RpcServer server;
	private static RecordingAllocator allocator;
	private static NettyClient client;
	private static String address;

	@BeforeAll
	static void start() {
		server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.executor(ExecutorConfig.fixed(4)).build();
		server.export(Echo.class, new EchoImpl());
		server.start();
		allocator = new RecordingAllocator();
		client = new NettyClient(Serializers.load(SimpleTestSerializer.NAME, Map.of()), 2, new LoadStats(), allocator);
		ServiceInstance instance = new ServiceInstance(KEY, "127.0.0.1", server.port(), Map.of());
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

	private static Method echoMethod() {
		try {
			return Echo.class.getMethod("echo", byte[].class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static RequestWriter echoWriter(byte[] payload) {
		return serializer -> EnvelopeCodec.request(KEY.interfaceName(), KEY.version(), KEY.group(), "echo", PARAM_SIG,
				List.of(serializer.serialize(payload, byte[].class)));
	}

	private static byte[] echoed(RpcMessage response) {
		try {
			assertEquals(Status.OK, response.status(), () -> "unexpected status: " + response);
			ByteBuf value = EnvelopeCodec.readValue(response.body());
			return (byte[]) new SimpleTestSerializer().deserialize(ByteBufUtil.getBytes(value), byte[].class);
		} finally {
			response.release();
		}
	}

	@Test
	void virtualThreadCallersNeverAllocateNettyBuffers() throws Exception {
		allocator.reset();
		byte[] payload = new byte[4096]; // 比默认初始容量（256）大：旧实现会在调用方线程上扩容（PoolArena.reallocate）
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) i;
		}
		int callers = 200;
		int callsPerCaller = 10;
		try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Void>> done = new ArrayList<>();
			for (int c = 0; c < callers; c++) {
				done.add(CompletableFuture.runAsync(() -> {
					for (int i = 0; i < callsPerCaller; i++) {
						RpcMessage response = client.send(address, echoWriter(payload), Map.of(), TIMEOUT.toNanos()).join();
						assertArrayEquals(payload, echoed(response));
					}
				}, users));
			}
			CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
		}
		assertTrue(allocator.allocations() > 0, "the recording allocator must have been used by the connection");
		assertEquals(Set.of(), allocator.offendingThreads(),
				"buffers must only be allocated on the client's event-loop threads (rpc-client-io-*), never on caller threads");
		assertEquals(0, client.pendingRequests());
	}

	@Test
	void anErrorThrownWhileSerializingFailsTheRequestImmediately() {
		CompletableFuture<RpcMessage> response = client.send(address, serializer -> {
			throw new StackOverflowError("simulated: object graph too deep");
		}, Map.of(), TIMEOUT.toNanos());
		CompletionException e = assertThrows(CompletionException.class, () -> response.orTimeout(5, TimeUnit.SECONDS).join());
		assertInstanceOf(RpcSerializationException.class, e.getCause(), "not a timeout: the request must fail at once");
		assertInstanceOf(StackOverflowError.class, e.getCause().getCause());
		assertEquals(0, client.pendingRequests());
	}

	@Test
	void anErrorWhileEncodingOnTheEventLoopFailsTheRequestAndReleasesTheFrame() {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			CompletableFuture<RpcMessage> response = client.send(address, serializer -> new OutboundBody() {
				@Override
				public int length() {
					return 64;
				}

				@Override
				public void writeTo(ByteBuf out) {
					out.writeLong(42);
					throw new OutOfMemoryError("simulated: direct buffer memory exhausted");
				}
			}, Map.of(), TIMEOUT.toNanos());
			CompletionException e = assertThrows(CompletionException.class, () -> response.orTimeout(5, TimeUnit.SECONDS).join());
			assertInstanceOf(RpcException.class, e.getCause(), "a write failure, not a timeout");
			assertEquals(0, client.pendingRequests());
			// 连接没有因此关闭：下一次调用照常
			assertArrayEquals(new byte[] { 7 },
					echoed(client.send(address, echoWriter(new byte[] { 7 }), Map.of(), TIMEOUT.toNanos()).join()));
			probe.assertNoLeaks();
		}
	}

	@Test
	void aRequestThatTimedOutBeforeReachingTheEventLoopIsNotWritten() {
		// 先确保连接已建立，下面的请求走"已连接、在调用方线程上序列化"的路径
		echoed(client.send(address, echoWriter(new byte[] { 1 }), Map.of(), TIMEOUT.toNanos()).join());
		int before = INVOCATIONS.get();
		CompletableFuture<RpcMessage> response = client.send(address, serializer -> {
			sleep(200); // 序列化慢到超过本次请求的超时
			return echoWriter(new byte[] { 2 }).write(serializer);
		}, Map.of(), TimeUnit.MILLISECONDS.toNanos(20));
		CompletionException e = assertThrows(CompletionException.class, () -> response.orTimeout(5, TimeUnit.SECONDS).join());
		assertInstanceOf(TimeoutException.class, e.getCause());
		sleep(300);
		assertEquals(before, INVOCATIONS.get(), "an expired request must not be encoded and sent to the server");
		assertEquals(0, client.pendingRequests());
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	/** 委托给池化分配器，记录每次分配所在的线程；不是客户端事件循环线程的记为违规。 */
	static final class RecordingAllocator implements ByteBufAllocator {
		private final ByteBufAllocator delegate = PooledByteBufAllocator.DEFAULT;
		private final Set<String> offending = ConcurrentHashMap.newKeySet();
		private final AtomicLong allocations = new AtomicLong();

		void reset() {
			offending.clear();
			allocations.set(0);
		}

		Set<String> offendingThreads() {
			return Set.copyOf(offending);
		}

		long allocations() {
			return allocations.get();
		}

		private <T> T record(T buf) {
			allocations.incrementAndGet();
			Thread t = Thread.currentThread();
			if (t.isVirtual() || !t.getName().startsWith("rpc-client-io")) {
				offending.add((t.isVirtual() ? "virtual " : "") + t + " @ " + caller());
			}
			return buf;
		}

		private static String caller() {
			StackTraceElement[] stack = new Throwable().getStackTrace();
			StringBuilder sb = new StringBuilder();
			for (int i = 3; i < Math.min(stack.length, 9); i++) {
				sb.append(stack[i]).append(" <- ");
			}
			return sb.toString();
		}

		@Override
		public ByteBuf buffer() {
			return record(delegate.buffer());
		}

		@Override
		public ByteBuf buffer(int initialCapacity) {
			return record(delegate.buffer(initialCapacity));
		}

		@Override
		public ByteBuf buffer(int initialCapacity, int maxCapacity) {
			return record(delegate.buffer(initialCapacity, maxCapacity));
		}

		@Override
		public ByteBuf ioBuffer() {
			return record(delegate.ioBuffer());
		}

		@Override
		public ByteBuf ioBuffer(int initialCapacity) {
			return record(delegate.ioBuffer(initialCapacity));
		}

		@Override
		public ByteBuf ioBuffer(int initialCapacity, int maxCapacity) {
			return record(delegate.ioBuffer(initialCapacity, maxCapacity));
		}

		@Override
		public ByteBuf heapBuffer() {
			return record(delegate.heapBuffer());
		}

		@Override
		public ByteBuf heapBuffer(int initialCapacity) {
			return record(delegate.heapBuffer(initialCapacity));
		}

		@Override
		public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
			return record(delegate.heapBuffer(initialCapacity, maxCapacity));
		}

		@Override
		public ByteBuf directBuffer() {
			return record(delegate.directBuffer());
		}

		@Override
		public ByteBuf directBuffer(int initialCapacity) {
			return record(delegate.directBuffer(initialCapacity));
		}

		@Override
		public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
			return record(delegate.directBuffer(initialCapacity, maxCapacity));
		}

		@Override
		public CompositeByteBuf compositeBuffer() {
			return record(delegate.compositeBuffer());
		}

		@Override
		public CompositeByteBuf compositeBuffer(int maxNumComponents) {
			return record(delegate.compositeBuffer(maxNumComponents));
		}

		@Override
		public CompositeByteBuf compositeHeapBuffer() {
			return record(delegate.compositeHeapBuffer());
		}

		@Override
		public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
			return record(delegate.compositeHeapBuffer(maxNumComponents));
		}

		@Override
		public CompositeByteBuf compositeDirectBuffer() {
			return record(delegate.compositeDirectBuffer());
		}

		@Override
		public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
			return record(delegate.compositeDirectBuffer(maxNumComponents));
		}

		@Override
		public boolean isDirectBufferPooled() {
			return delegate.isDirectBufferPooled();
		}

		@Override
		public int calculateNewCapacity(int minNewCapacity, int maxCapacity) {
			return delegate.calculateNewCapacity(minNewCapacity, maxCapacity);
		}
	}
}
