package com.feynman.rpc.core.server;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcFrameDecoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.MethodSignature;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 F1 的"只发不读"对照：一个 autoRead=false 的原始 Netty 客户端按协议向真实的服务端（NettyServer，fixed 执行器、缺省水位线
 * 32KiB / 64KiB）发<b>固定总量</b>的请求（{@value #REQUESTS} 个，每个要 {@value #REPLY_BYTES} 字节的回包，回包总量约 21MB），
 * 期间完全不读。服务端那条连接的待写字节峰值（{@code ChannelOutboundBuffer.totalPendingWriteBytes}，在 pipeline 最靠近
 * transport 处的一个记录 handler 里每次写之后取）：
 * <ul>
 *   <li><b>背压开</b>：不超过 high + 在途上限 × 回包帧大小 + 余量（计算见 {@link #onBound()}），且至少暂停读一次；</li>
 *   <li><b>背压关</b>（对照）：显著更大——服务端读完全部请求、全部回包堆在出站缓冲区里，下界见 {@link #OFF_LOWER_BOUND}。
 *       总量受固定请求数约束，不会无界。</li>
 * </ul>
 * 之后客户端恢复读：{@value #REQUESTS} 个请求全部收到回包（OK 或执行器满时的 SERVER_OVERLOADED），服务端在途归零、
 * 待写字节归零、读恢复，后续请求正常；整个过程在 ByteBuf 泄漏探针（PARANOID）下没有泄漏。
 */
class NonReadingClientTest {

	public interface Blob {
		byte[] blob(int size);
	}

	static final int REQUESTS = 20_000;
	static final int REPLY_BYTES = 1024;
	/** 每批写出的请求数与批间隔：服务端读得到的每一批都远小于执行器容量，背压关时请求不会因执行器满被拒绝。 */
	static final int BATCH = 100;
	static final int THREADS = 4;
	static final int QUEUE = 1000;
	/** 回包帧上界：帧头 23 + 值信封 + 序列化的 byte[]（1 + 4 + 1024），再加 ChannelOutboundBuffer 每条目的记账开销 96。 */
	static final int REPLY_FRAME_BOUND = 23 + 16 + 5 + REPLY_BYTES + 96;
	/**
	 * 两端套接字缓冲区固定为 64KiB（服务端连接的 SO_SNDBUF、客户端的 SO_RCVBUF）：不设时由操作系统自动调整，内核吸收的回包量
	 * 随平台变化，出站缓冲区里的数字就部分取决于内核而不是框架。
	 */
	static final int SOCKET_BUFFER = 64 * 1024;
	/**
	 * 背压关时的下界：回包总量约 20000 × 1.06KB ≈ 21MB，减去两端内核缓冲区（各 64KiB，内核可能按 2 倍记账）后应在 20MB 左右；
	 * 下界取 15MB，仍是背压开时上界（约 1.2MB）的十倍以上。
	 */
	static final long OFF_LOWER_BOUND = 15L * 1024 * 1024;

	private static final ServiceKey KEY = ServiceKey.of(Blob.class);
	private static final SimpleTestSerializer SERIALIZER = new SimpleTestSerializer();

	/**
	 * 背压开时的上界。停读发生在回包写进出站缓冲区、待写字节越过 high 的那一刻（fixed 执行器下回包由事件循环上的写任务写出，
	 * 停读同步生效、之后不再读新请求）；那时已经读进来、还没回包的请求最多是执行器容量（threads + queue），它们的回包随后照常写出。
	 * 所以峰值 ≤ high + (threads + queue) × 回包帧 + 一个回包帧（越过 high 的那一个）。
	 */
	static long onBound() {
		return RpcServerConfig.DEFAULT_WRITE_BUFFER_HIGH + (long) (THREADS + QUEUE + 1) * REPLY_FRAME_BOUND;
	}

	@Test
	void withBackpressureTheOutboundBufferOfANonReadingClientIsBounded() throws Exception {
		Result on = run(true);
		System.out.printf("[NonReadingClientTest] backpressure on : peak pending %d bytes (bound %d), pauses %d, hard-limit rejections %d,"
				+ " replies OK %d / OVERLOADED %d%n", on.peakPending, onBound(), on.pauses, on.hardLimitRejections, on.ok, on.overloaded);
		assertTrue(on.pauses >= 1, "the server must pause reading the non-reading client at least once");
		assertTrue(on.peakPending <= onBound(), () -> "peak pending " + on.peakPending + " > bound " + onBound());
	}

	@Test
	void withoutBackpressureTheSameClientPilesUpTheWholeReplyStream() throws Exception {
		Result off = run(false);
		System.out.printf("[NonReadingClientTest] backpressure off: peak pending %d bytes (lower bound %d), pauses %d,"
				+ " replies OK %d / OVERLOADED %d%n", off.peakPending, OFF_LOWER_BOUND, off.pauses, off.ok, off.overloaded);
		assertEquals(0, off.pauses, "no handler, no pauses");
		assertTrue(off.peakPending >= OFF_LOWER_BOUND, () -> "peak pending " + off.peakPending + " < " + OFF_LOWER_BOUND);
		assertTrue(off.peakPending > 10 * onBound(), "the control must be clearly larger than the bound with backpressure on");
	}

	record Result(long peakPending, long pauses, long hardLimitRejections, long ok, long overloaded) {
	}

	private static Result run(boolean backpressure) throws Exception {
		Serializers serializers = Serializers.load(SimpleTestSerializer.NAME, Map.of());
		ServiceDispatcher dispatcher = new ServiceDispatcher(List.of(), serializers, true);
		java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		dispatcher.register(KEY, Blob.class, (Blob) size -> {
			calls.incrementAndGet();
			return new byte[size];
		});
		RpcServerConfig config = RpcServerConfig.builder().executor(ExecutorConfig.fixed(THREADS, QUEUE)).ioThreads(1)
				.outboundBackpressure(backpressure).build();
		NettyServer server = new NettyServer(dispatcher, SERIALIZER.code(), config);
		EventLoopGroup clientGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("test-raw-client"));
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			int port = server.bind(0).getPort();
			Responses responses = new Responses();
			Channel client = rawClient(clientGroup, port, responses);
			await().atMost(10, TimeUnit.SECONDS).until(() -> server.activeConnections() == 1);
			Channel accepted = server.acceptedChannels().get(0);
			accepted.config().setOption(ChannelOption.SO_SNDBUF, SOCKET_BUFFER);
			PeakPending peak = new PeakPending();
			accepted.pipeline().addFirst("peakPending", peak);
			await().atMost(5, TimeUnit.SECONDS).until(() -> accepted.pipeline().get("peakPending") != null);

			Method m = Blob.class.getMethod("blob", int.class);
			String paramSig = MethodSignature.paramSig(m);
			for (int sent = 0; sent < REQUESTS; sent += BATCH) {
				for (int i = sent; i < sent + BATCH; i++) {
					client.write(RpcMessage.request(i + 1, SERIALIZER.code(), Map.of(), EnvelopeCodec.request(KEY.interfaceName(),
							KEY.version(), KEY.group(), "blob", paramSig, List.of(SERIALIZER.serialize(REPLY_BYTES, Integer.class)))));
				}
				client.flush();
				Thread.sleep(1);
			}
			// 等服务端稳定：关时它读完全部请求、全部回包写进出站缓冲区（写满 REQUESTS 次）；开时它停读、在途归零、之后不再有写
			awaitSettled(peak, calls, server, accepted);
			long peakPending = peak.max.get();
			long writesBeforeRead = peak.writes.sum();
			long pauses = server.autoReadPauses();
			long rejections = server.outboundOverloadRejections();
			assertEquals(0, responses.total(), "the client has not read anything yet");

			// 客户端恢复读：全部回包都到，服务端在途与待写字节归零、读恢复
			client.config().setAutoRead(true);
			await().atMost(60, TimeUnit.SECONDS).until(() -> responses.total() == REQUESTS);
			await().atMost(10, TimeUnit.SECONDS).until(() -> server.inFlight() == 0
					&& OutboundBackpressure.pendingWriteBytes(accepted) == 0 && accepted.config().isAutoRead());
			OutboundBackpressure handler = (OutboundBackpressure) accepted.pipeline().get(OutboundBackpressure.HANDLER_NAME);
			if (backpressure) {
				assertTrue(handler != null && !handler.paused(), "reading resumed once the client drained the replies");
			}
			// 恢复之后的请求照常处理
			long before = responses.ok.sum();
			client.writeAndFlush(RpcMessage.request(REQUESTS + 1, SERIALIZER.code(), Map.of(), EnvelopeCodec.request(KEY.interfaceName(),
					KEY.version(), KEY.group(), "blob", paramSig, List.of(SERIALIZER.serialize(16, Integer.class)))));
			await().atMost(10, TimeUnit.SECONDS).until(() -> responses.ok.sum() == before + 1);

			client.close().sync();
			await().atMost(10, TimeUnit.SECONDS).until(() -> server.activeConnections() == 0);
			Result result = new Result(peakPending, pauses, rejections, responses.ok.sum() - 1, responses.overloaded.sum());
			System.out.printf("[NonReadingClientTest] backpressure %s: replies written before the client read anything: %d of %d%n",
					backpressure ? "on" : "off", writesBeforeRead, REQUESTS);
			probe.assertNoLeaks();
			return result;
		} finally {
			clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
			server.close();
		}
	}

	/**
	 * 全部 {@value #REQUESTS} 个回包都已写进出站缓冲区，或者：读已暂停、在途为零、写与业务调用次数 1s 内都不再变化（最多等 120s）。
	 * 只看"多久没有新的写"不够：PARANOID 泄漏检测下事件循环一轮读能花掉几百毫秒，期间写任务排着队、写次数不动。
	 */
	private static void awaitSettled(PeakPending peak, java.util.concurrent.atomic.AtomicInteger calls, NettyServer server, Channel accepted)
			throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
		long last = -1;
		long quietSince = System.nanoTime();
		while (System.nanoTime() < deadline) {
			if (peak.writes.sum() >= REQUESTS) {
				return;
			}
			long now = peak.writes.sum() * 31 + calls.get();
			if (now != last) {
				last = now;
				quietSince = System.nanoTime();
			} else if (!accepted.config().isAutoRead() && server.inFlight() == 0
					&& System.nanoTime() - quietSince > TimeUnit.SECONDS.toNanos(1)) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("server did not settle: writes " + peak.writes.sum() + ", calls " + calls.get());
	}

	private static Channel rawClient(EventLoopGroup group, int port, Responses responses) throws InterruptedException {
		return new Bootstrap().group(group).channel(NioSocketChannel.class)
				.option(ChannelOption.AUTO_READ, false)
				.option(ChannelOption.SO_RCVBUF, SOCKET_BUFFER)
				// 客户端自己的待写请求（最多约 3MB）不受水位线影响：这里测的是服务端
				.option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(64 * 1024 * 1024, 128 * 1024 * 1024))
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new RpcFrameDecoder(), new RpcMessageDecoder(), new RpcEncoder(), responses);
					}
				})
				.connect("127.0.0.1", port).sync().channel();
	}

	/** 在 pipeline 最靠近 transport 处：每次写交给 HeadContext 之后记一次待写字节。 */
	static final class PeakPending extends ChannelOutboundHandlerAdapter {
		final AtomicLong max = new AtomicLong();
		final LongAdder writes = new LongAdder();

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			ctx.write(msg, promise);
			writes.increment();
			long pending = OutboundBackpressure.pendingWriteBytes(ctx.channel());
			max.accumulateAndGet(pending, Math::max);
		}
	}

	/** 原始客户端：按状态数回包并释放。 */
	@io.netty.channel.ChannelHandler.Sharable
	static final class Responses extends ChannelInboundHandlerAdapter {
		final LongAdder ok = new LongAdder();
		final LongAdder overloaded = new LongAdder();
		final LongAdder other = new LongAdder();

		long total() {
			return ok.sum() + overloaded.sum() + other.sum();
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			RpcMessage message = (RpcMessage) msg;
			try {
				if (message.messageType() != MessageType.RESPONSE) {
					return;
				}
				if (message.status() == Status.OK) {
					ok.increment();
				} else if (message.status() == Status.SERVER_OVERLOADED) {
					overloaded.increment();
				} else {
					other.increment();
				}
			} finally {
				message.release();
			}
		}
	}
}
