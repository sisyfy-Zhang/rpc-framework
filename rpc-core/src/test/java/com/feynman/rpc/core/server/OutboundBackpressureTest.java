package com.feynman.rpc.core.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcFrameDecoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.internal.FlushConsolidation;
import com.feynman.rpc.core.internal.InFlightGate;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.MethodSignature;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutboundBackpressure} 与 ServerHandler 的出站硬上限，在一条"套接字写不出去"的 EmbeddedChannel 上
 * （{@link StalledChannel}：冲刷时什么也不写走，待写字节一直留在出站缓冲区里，等价于对端不读）：
 * <ul>
 *   <li>不可写时停读、恢复可写时再读，重复的不可写事件只计一次暂停；停读期间已读到的 ping 照常回 pong，顺序不乱；</li>
 *   <li>按当前可写状态对账：FlushConsolidationHandler 在"变为不可写"里冲刷、嵌套触发"恢复可写"时，读不会停在错误的状态；</li>
 *   <li>硬上限：停读之前已经读进来的一大批帧（一个读缓冲区里的 200 个请求）——待写字节越过硬上限之后的请求不分发、
 *       回 SERVER_OVERLOADED 并计数，全部请求都有回包，待写字节被封顶；</li>
 *   <li>读空闲：停读持续超过读空闲阈值时连接被关闭（这里用 1s 的 IdleStateHandler 构造，生产配置是 30s，不可配）。</li>
 * </ul>
 */
class OutboundBackpressureTest {

	public interface Blob {
		byte[] blob(int size);
	}

	private static final ServiceKey KEY = ServiceKey.of(Blob.class);
	private static final SimpleTestSerializer SERIALIZER = new SimpleTestSerializer();

	/** 冲刷时（{@code stalled} 为真）不写走任何东西：出站缓冲区里的数据一直算作待写字节。 */
	static final class StalledChannel extends EmbeddedChannel {
		boolean stalled = true;

		StalledChannel(int low, int high) {
			super();
			config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
		}

		@Override
		protected void doWrite(ChannelOutboundBuffer in) throws Exception {
			if (!stalled) {
				super.doWrite(in);
			}
		}

		/** 让套接字"恢复"：把积压全部写走。 */
		void drain() {
			stalled = false;
			flush();
			runPendingTasks();
		}

		long pending() {
			return OutboundBackpressure.pendingWriteBytes(this);
		}
	}

	@Test
	void pausesReadingWhileUnwritableAndResumesOnceDrained() {
		LongAdder pauses = new LongAdder();
		StalledChannel channel = new StalledChannel(1024, 2048);
		OutboundBackpressure backpressure = new OutboundBackpressure(pauses);
		channel.pipeline().addLast(backpressure);
		assertTrue(channel.config().isAutoRead());

		channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[1500]));
		assertTrue(channel.isWritable(), "below the high water mark");
		assertTrue(channel.config().isAutoRead());

		channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[1500]));
		assertFalse(channel.isWritable());
		assertFalse(channel.config().isAutoRead(), "unwritable: stop reading this connection");
		assertTrue(backpressure.paused());
		assertEquals(1, pauses.sum());

		channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[1500]));
		assertEquals(1, pauses.sum(), "still unwritable: no second pause");

		channel.drain();
		assertTrue(channel.isWritable());
		assertTrue(channel.config().isAutoRead(), "drained below the low water mark: read again");
		assertFalse(backpressure.paused());
		assertEquals(1, pauses.sum());
		releaseOutbound(channel);
		assertFalse(channel.finishAndReleaseAll());
	}

	@Test
	void aPingReadBeforeThePauseIsStillAnsweredInOrder() {
		LongAdder pauses = new LongAdder();
		StalledChannel channel = new StalledChannel(1024, 2048);
		channel.pipeline().addLast(new OutboundBackpressure(pauses));
		channel.pipeline().addLast(new ServerHandler(dispatcher(new AtomicInteger()), new BusinessExecutor(ExecutorConfig.direct()),
				new InFlightGate(), SERIALIZER.code()));
		ByteBuf backlog = Unpooled.wrappedBuffer(new byte[4096]);
		channel.writeAndFlush(backlog.retain());
		assertFalse(channel.config().isAutoRead());

		// 停读之前已经读进来的 ping：照常回 pong，排在积压之后
		channel.writeInbound(RpcMessage.inbound(42, MessageType.HEARTBEAT_PING, (byte) 0, (byte) 0, Status.OK, Map.of(),
				Unpooled.EMPTY_BUFFER, null, System.nanoTime()));
		channel.drain();
		ByteBuf first = channel.readOutbound();
		try {
			assertTrue(first == backlog, "the backlog leaves first");
		} finally {
			first.release();
			backlog.release();
		}
		RpcMessage pong = channel.readOutbound();
		try {
			assertEquals(MessageType.HEARTBEAT_PONG, pong.messageType());
			assertEquals(42, pong.requestId());
		} finally {
			pong.release();
		}
		assertTrue(channel.config().isAutoRead());
		assertFalse(channel.finishAndReleaseAll());
	}

	/**
	 * FlushConsolidationHandler 收到"变为不可写"时先冲刷积压；套接字一次收下时冲刷会嵌套触发"恢复可写"，它先于外层的
	 * "不可写"到达 OutboundBackpressure。按事件方向切换会在可写的连接上停读，按当前状态对账则不会。
	 */
	@Test
	void reconcilesWithTheCurrentStateWhenFlushConsolidationDrainsInsideTheEvent() {
		LongAdder pauses = new LongAdder();
		StalledChannel channel = new StalledChannel(1024, 2048);
		channel.stalled = false; // 套接字写得出去
		FlushConsolidation.addFirst(channel.pipeline());
		channel.pipeline().addLast(new OutboundBackpressure(pauses));
		channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[100])); // 读循环里：flush 被推迟
				ctx.write(Unpooled.wrappedBuffer(new byte[3000])); // 越过高水位 → FlushConsolidationHandler 冲刷 → 嵌套恢复可写
			}
		});
		channel.writeInbound("go");
		assertTrue(channel.isWritable());
		assertTrue(channel.config().isAutoRead(), "the connection is writable: reading must not stay paused");
		releaseOutbound(channel);
		assertFalse(channel.finishAndReleaseAll());
	}

	/**
	 * 一个读缓冲区里的 200 个请求（每个要 1KB 回包，direct 执行器在读循环内回包）：水位线 8KiB / 16KiB、硬上限 32KiB。
	 * 越过高水位时停读（之后不会再有新的读），但这一批帧照常解码；待写字节越过硬上限之后的请求不分发、回 SERVER_OVERLOADED。
	 */
	@Test
	void framesAlreadyReadWhenTheConnectionTurnsUnwritableAreRejectedPastTheHardLimit() throws Exception {
		int requests = 200;
		long hardLimit = 32 * 1024;
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			AtomicInteger invocations = new AtomicInteger();
			LongAdder pauses = new LongAdder();
			LongAdder rejections = new LongAdder();
			InFlightGate gate = new InFlightGate();
			StalledChannel channel = new StalledChannel(8 * 1024, 16 * 1024);
			FlushConsolidation.addFirst(channel.pipeline());
			channel.pipeline().addLast(new OutboundBackpressure(pauses));
			channel.pipeline().addLast(new RpcFrameDecoder(), new RpcMessageDecoder(), new RpcEncoder());
			channel.pipeline().addLast(new ServerHandler(dispatcher(invocations), new BusinessExecutor(ExecutorConfig.direct()), gate,
					SERIALIZER.code(), new LongAdder(), hardLimit, rejections));

			channel.writeInbound(requestFrames(requests, 1024));

			assertEquals(1, pauses.sum(), "paused once, when the replies crossed the high water mark");
			assertFalse(channel.config().isAutoRead());
			int dispatched = invocations.get();
			assertEquals(requests - dispatched, rejections.sum(), "every request past the hard limit was rejected, not dispatched");
			assertTrue(dispatched > 16 && dispatched < requests, "dispatched " + dispatched);
			long pending = channel.pending();
			// 封顶：越过硬上限的那一个回包 + 之后每个拒绝回包（原因是固定短常量，帧 + 记账开销 < 192 字节）
			long bound = hardLimit + 2048 + rejections.sum() * 192;
			assertTrue(pending >= hardLimit && pending <= bound, "pending " + pending + " not in [" + hardLimit + ", " + bound + "]");
			System.out.printf("[OutboundBackpressureTest] 200 frames in one read: dispatched %d, rejected %d, pending %d bytes (bound %d)%n",
					dispatched, rejections.sum(), pending, bound);

			channel.drain();
			assertTrue(channel.config().isAutoRead(), "drained: reading resumes");
			int[] statuses = countReplies(channel);
			assertEquals(dispatched, statuses[0], "OK replies");
			assertEquals(requests - dispatched, statuses[1], "SERVER_OVERLOADED replies");
			assertEquals(0, gate.inFlight());
			assertFalse(channel.finishAndReleaseAll());
			probe.assertNoLeaks();
		}
	}

	@Test
	void aConnectionPausedLongerThanTheReaderIdleTimeIsClosed() throws Exception {
		LongAdder pauses = new LongAdder();
		BusinessExecutor executor = new BusinessExecutor(ExecutorConfig.direct());
		StalledChannel channel = new StalledChannel(1024, 2048);
		channel.pipeline().addLast(new OutboundBackpressure(pauses));
		channel.pipeline().addLast(new IdleStateHandler(1, 0, 0, TimeUnit.SECONDS));
		channel.pipeline().addLast(new ServerHandler(dispatcher(new AtomicInteger()), executor, new InFlightGate(), SERIALIZER.code()));
		channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[4096]));
		assertFalse(channel.config().isAutoRead());
		long start = System.nanoTime();
		await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
			channel.runScheduledPendingTasks();
			return !channel.isOpen();
		});
		long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		assertTrue(waitedMs >= 900, "closed by the reader-idle timeout, not earlier: " + waitedMs + "ms");
		assertNull(channel.readOutbound(), "the stalled backlog was released when the connection closed");
		channel.finishAndReleaseAll();
	}

	private static ServiceDispatcher dispatcher(AtomicInteger invocations) {
		ServiceDispatcher dispatcher = new ServiceDispatcher(List.of(), Serializers.load(SimpleTestSerializer.NAME, Map.of()), true);
		dispatcher.register(KEY, Blob.class, (Blob) size -> {
			invocations.incrementAndGet();
			return new byte[size];
		});
		return dispatcher;
	}

	/** {@code n} 个 blob(size) 请求编码成帧，拼成一个缓冲区（一次读到的那一批）。 */
	private static ByteBuf requestFrames(int n, int size) throws Exception {
		String paramSig = MethodSignature.paramSig(Blob.class.getMethod("blob", int.class));
		EmbeddedChannel encoder = new EmbeddedChannel(new RpcEncoder());
		CompositeByteBuf all = Unpooled.compositeBuffer(n);
		for (int i = 0; i < n; i++) {
			encoder.writeOutbound(RpcMessage.request(i + 1, SERIALIZER.code(), Map.of(), EnvelopeCodec.request(KEY.interfaceName(),
					KEY.version(), KEY.group(), "blob", paramSig, List.of(SERIALIZER.serialize(size, Integer.class)))));
			all.addComponent(true, encoder.<ByteBuf>readOutbound());
		}
		encoder.finishAndReleaseAll();
		return all;
	}

	/** 解码写出的全部帧：[OK 数, SERVER_OVERLOADED 数]；其它状态让测试失败。 */
	private static int[] countReplies(EmbeddedChannel channel) {
		EmbeddedChannel decoder = new EmbeddedChannel(new RpcFrameDecoder(), new RpcMessageDecoder());
		Object out;
		while ((out = channel.readOutbound()) != null) {
			decoder.writeInbound(out);
		}
		int[] counts = new int[2];
		List<Status> others = new ArrayList<>();
		RpcMessage reply;
		while ((reply = decoder.readInbound()) != null) {
			try {
				switch (reply.status()) {
					case OK -> counts[0]++;
					case SERVER_OVERLOADED -> {
						counts[1]++;
						assertEquals(ServerHandler.OUTBOUND_OVERLOAD_REASON, EnvelopeCodec.readError(reply.body()).message(),
								"the reply carries only the short constant reason; details go to the log");
					}
					default -> others.add(reply.status());
				}
			} finally {
				reply.release();
			}
		}
		decoder.finishAndReleaseAll();
		assertTrue(others.isEmpty(), "unexpected statuses " + others);
		return counts;
	}

	private static void releaseOutbound(EmbeddedChannel channel) {
		Object out;
		while ((out = channel.readOutbound()) != null) {
			io.netty.util.ReferenceCountUtil.release(out);
		}
	}

}
