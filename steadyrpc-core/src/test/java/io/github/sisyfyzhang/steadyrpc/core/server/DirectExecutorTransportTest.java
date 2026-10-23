package io.github.sisyfyzhang.steadyrpc.core.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.client.NettyClient;
import io.github.sisyfyzhang.steadyrpc.core.client.RequestWriter;
import io.github.sisyfyzhang.steadyrpc.core.codec.EnvelopeCodec;
import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadStats;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MethodSignature;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializers;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;
import io.github.sisyfyzhang.steadyrpc.core.testutil.ThreadRecordingAllocator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.buffer.ByteBufUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ExecutorConfig.direct()} 的传输层断言（与 {@link ServerThreadAllocationTest} 同一种驱动方式：真实 socket、给已接受的连接注入
 * 记录分配线程的分配器）：业务方法在 {@code rpc-server-worker-*} 上执行；参数的惰性解码、回包（正常结果与错误信封）都在同一个
 * 事件循环线程上完成，帧缓冲区只在事件循环上分配；不泄漏。
 */
class DirectExecutorTransportTest {

	public interface Echo {
		byte[] echo(byte[] payload);

		String fail(String message);
	}

	static final Set<String> BUSINESS_THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();

	static final class EchoImpl implements Echo {
		@Override
		public byte[] echo(byte[] payload) {
			BUSINESS_THREADS.add(Thread.currentThread().getName());
			return payload;
		}

		@Override
		public String fail(String message) {
			BUSINESS_THREADS.add(Thread.currentThread().getName());
			throw new IllegalStateException(message);
		}
	}

	private static final ServiceKey KEY = ServiceKey.of(Echo.class);
	private static final SimpleTestSerializer SERIALIZER = new SimpleTestSerializer();

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
		server = new NettyServer(dispatcher, ExecutorConfig.direct(), SERIALIZER.code(), allocator);
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
		return client.send(address, writer, Map.of(), TimeUnit.SECONDS.toNanos(30)).orTimeout(10, TimeUnit.SECONDS).join();
	}

	@Test
	void businessMethodsRepliesAndAllocationsStayOnTheEventLoop() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			allocator.reset();
			BUSINESS_THREADS.clear();
			byte[] payload = new byte[4096];
			for (int i = 0; i < payload.length; i++) {
				payload[i] = (byte) i;
			}
			try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
				List<CompletableFuture<Void>> done = new ArrayList<>();
				for (int c = 0; c < 50; c++) {
					int caller = c;
					done.add(CompletableFuture.runAsync(() -> {
						for (int i = 0; i < 10; i++) {
							RpcMessage response = send(call("echo", byte[].class, payload));
							try {
								assertEquals(Status.OK, response.status());
								ByteBuf value = EnvelopeCodec.readValue(response.body());
								assertArrayEquals(payload, (byte[]) SERIALIZER.deserialize(ByteBufUtil.getBytes(value), byte[].class));
							} finally {
								response.release();
							}
						}
						RpcMessage error = send(call("fail", String.class, "no-" + caller));
						try {
							assertEquals(Status.BIZ_EXCEPTION, error.status());
							assertEquals("no-" + caller, EnvelopeCodec.readError(error.body()).message());
						} finally {
							error.release();
						}
					}, users));
				}
				CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
			}
			assertTrue(allocator.allocations() > 0);
			assertEquals(Set.of(), allocator.offendingThreads(), "frames must only be allocated on rpc-server-worker-* threads");
			assertTrue(!BUSINESS_THREADS.isEmpty() && BUSINESS_THREADS.stream().allMatch(n -> n.startsWith("rpc-server-worker-")),
					"business methods must run on the IO threads: " + BUSINESS_THREADS);
			assertEquals(0, server.inFlight());
			assertEquals(0, server.queuedBusinessTasks());
			probe.assertNoLeaks();
		}
	}
}
