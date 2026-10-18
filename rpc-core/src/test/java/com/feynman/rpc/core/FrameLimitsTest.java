package com.feynman.rpc.core;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.filter.Filter;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.testutil.CountingLoadBalance;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧上限与回程 attachments 的边角（D-012 第 5 条）：超限的帧在发送方就被改写成明确的错误，而不是交给编码器写失败——
 * 客户端侧写失败会被当成传输失败重试，服务端侧回包丢失会让客户端等到超时。
 * <ul>
 *   <li>请求整帧超过 8 MiB：客户端不写出，立即以 RpcSerializationException 失败，failover + retries=2 也只尝试一次，
 *       服务端什么都没收到，连接照常可用；</li>
 *   <li>OK 回包整帧超过 8 MiB：SERIALIZATION_ERROR；恰好 8 MiB 的回包能正常送达（两端阈值一致的边界证据）；</li>
 *   <li>服务端 Filter 写出超限 / 非法的回程 attachments：改回不带 attachments 的 SERVER_ERROR，客户端立即收到并看到原因。</li>
 * </ul>
 */
class FrameLimitsTest {

	public interface Blob {
		int size(byte[] data);

		byte[] make(int length);

		String echo(String s);
	}

	static final class BlobImpl implements Blob {
		final AtomicInteger sizeCalls = new AtomicInteger();

		@Override
		public int size(byte[] data) {
			sizeCalls.incrementAndGet();
			return data.length;
		}

		@Override
		public byte[] make(int length) {
			return new byte[length];
		}

		@Override
		public String echo(String s) {
			return s;
		}
	}

	/** 远小于默认超时的上限：失败必须是"立即"的，而不是等到超时。 */
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final long FAST_MILLIS = 3000;

	@BeforeEach
	void resetCounter() {
		CountingLoadBalance.reset();
	}

	private static RpcServer server(String ns, Filter... filters) {
		return RpcServer.builder().serializer(SimpleTestSerializer.NAME)
				.port(0)
				.host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns))
				.executor(ExecutorConfig.fixed(2))
				.serverFilter(filters)
				.build();
	}

	private static RpcClient client(String ns) {
		return RpcClient.builder().serializer(SimpleTestSerializer.NAME)
				.registry(InMemoryRegistryFactory.config(ns))
				.cluster("failover")
				.loadBalance(CountingLoadBalance.NAME)
				.defaultTimeout(TIMEOUT)
				.build();
	}

	private static void startAll(RpcServer... servers) {
		for (RpcServer server : servers) {
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
		}
	}

	@Test
	void oversizedRequestFailsBeforeSendingAndIsNotRetried() {
		String ns = "ns-" + UUID.randomUUID();
		AtomicInteger arrivals = new AtomicInteger();
		Filter countArrivals = (next, inv) -> {
			arrivals.incrementAndGet();
			return next.invoke(inv);
		};
		BlobImpl a = new BlobImpl();
		BlobImpl b = new BlobImpl();
		BlobImpl c = new BlobImpl();
		try (RpcServer sa = server(ns, countArrivals); RpcServer sb = server(ns, countArrivals); RpcServer sc = server(ns, countArrivals)) {
			sa.export(Blob.class, a);
			sb.export(Blob.class, b);
			sc.export(Blob.class, c);
			startAll(sa, sb, sc);
			try (RpcClient client = client(ns)) {
				Blob blob = client.reference(Blob.class, ReferenceOptions.defaults().retries(2));
				assertEquals("warm", blob.echo("warm"));
				CountingLoadBalance.reset();
				arrivals.set(0);

				byte[] tooLarge = new byte[ProtocolConstants.MAX_FRAME_LENGTH + 1];
				long start = System.nanoTime();
				RpcSerializationException e = assertThrows(RpcSerializationException.class, () -> blob.size(tooLarge));
				long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				System.out.printf("[frame-limits] oversized request failed in %dms after %d attempt(s): %s%n",
						elapsedMillis, CountingLoadBalance.selections(), e.getMessage());

				assertTrue(e.getMessage().contains("Request frame too large"), e.getMessage());
				assertTrue(e.getMessage().contains("MAX_FRAME_LENGTH " + ProtocolConstants.MAX_FRAME_LENGTH), e.getMessage());
				assertTrue(elapsedMillis < FAST_MILLIS, "must fail before sending, not time out: " + elapsedMillis + "ms");
				assertEquals(1, CountingLoadBalance.selections(), "failover with retries=2 must try exactly once");
				assertEquals(0, arrivals.get(), "no server may receive the oversized request");
				assertEquals(0, a.sizeCalls.get() + b.sizeCalls.get() + c.sizeCalls.get());
				assertEquals(0, client.pendingRequests());

				// 连接没有被一个超限帧弄坏：同一客户端上的正常请求照常往返
				assertEquals(3, blob.size(new byte[3]));
				assertEquals(1, arrivals.get());
			}
		}
	}

	@Test
	void resultAtTheFrameLimitArrivesAndOneByteMoreIsSerializationError() {
		String ns = "ns-" + UUID.randomUUID();
		try (RpcServer server = server(ns)) {
			server.export(Blob.class, new BlobImpl());
			startAll(server);
			try (RpcClient client = client(ns)) {
				Blob blob = client.reference(Blob.class);
				// 回包整帧 = 23 头 + 0 attachments + body；body = present(1) + len(4) + SimpleTestSerializer 的 'B'(1) + 长度(4) + 数据
				int atLimit = ProtocolConstants.MAX_FRAME_LENGTH - ProtocolConstants.HEADER_LENGTH - 1 - 4 - 1 - 4;
				assertEquals(atLimit, blob.make(atLimit).length, "a frame of exactly MAX_FRAME_LENGTH must be delivered");

				long start = System.nanoTime();
				RpcSerializationException e = assertThrows(RpcSerializationException.class, () -> blob.make(atLimit + 1));
				long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				assertTrue(e.getMessage().contains("SERIALIZATION_ERROR"), e.getMessage());
				assertTrue(e.getMessage().contains("result too large"), e.getMessage());
				assertTrue(elapsedMillis < FAST_MILLIS, "must be answered, not time out: " + elapsedMillis + "ms");
				assertEquals("still-ok", blob.echo("still-ok"));
				assertEquals(0, client.pendingRequests());
			}
		}
	}

	@Test
	void oversizedResponseAttachmentsFallBackToServerErrorWithoutAttachments() {
		Map<String, String> huge = Map.of("big", "x".repeat(70 * 1024));
		assertFallsBackToServerError(huge, "longer than 65535");
	}

	@Test
	void responseAttachmentsOverTheTotalLimitFallBackToServerError() {
		// 单个值都合法（< 65535 字节），合计超过 64 KiB
		Map<String, String> tooMuch = Map.of("a", "x".repeat(40 * 1024), "b", "y".repeat(40 * 1024));
		assertFallsBackToServerError(tooMuch, "attachments too large");
	}

	@Test
	void nullResponseAttachmentValueFallsBackToServerError() {
		Map<String, String> withNull = new HashMap<>();
		withNull.put("k", null);
		assertFallsBackToServerError(withNull, "must not be null");
	}

	private static void assertFallsBackToServerError(Map<String, String> replyAttachments, String expectedReason) {
		String ns = "ns-" + UUID.randomUUID();
		Filter poison = (next, inv) -> "echo".equals(inv.methodName())
				? next.invoke(inv).thenApply(result -> result.withAttachments(replyAttachments))
				: next.invoke(inv);
		try (RpcServer server = server(ns, poison)) {
			server.export(Blob.class, new BlobImpl());
			startAll(server);
			try (RpcClient client = client(ns)) {
				Blob blob = client.reference(Blob.class);
				long start = System.nanoTime();
				RpcException e = assertThrows(RpcException.class, () -> blob.echo("hi"));
				long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				System.out.printf("[frame-limits] bad response attachments answered in %dms: %s%n", elapsedMillis, e.getMessage());

				assertEquals(Status.SERVER_ERROR, e.status());
				assertTrue(e.getMessage().contains("invalid response attachments"), e.getMessage());
				assertTrue(e.getMessage().contains(expectedReason), e.getMessage());
				assertTrue(elapsedMillis < FAST_MILLIS, "client must get the error, not wait for the timeout: " + elapsedMillis + "ms");
				// 只有这一次回包被改写；同一连接上其他方法照常
				assertEquals(2, blob.size(new byte[2]));
				assertEquals(0, client.pendingRequests());
			}
		}
	}
}
