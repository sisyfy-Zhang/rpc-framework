package io.github.sisyfyzhang.steadyrpc.core.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.internal.InFlightGate;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MessageType;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializers;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServerHandler} 把两类 SERVER_OVERLOADED 拒绝分开计数：业务执行器过载（{@code executorRejections}）与停机
 * （在途闸门已关 / 执行器已关闭，{@code shutdownRejections}）。故障注入实验据此区分"突增被拒"与"优雅停机期间被拒"。
 */
class RejectionCountersTest {

	private final LongAdder executorRejections = new LongAdder();
	private final LongAdder shutdownRejections = new LongAdder();

	private ServerHandler handler(BusinessExecutor executor, InFlightGate gate) {
		return new ServerHandler(new ServiceDispatcher(List.of(), Serializers.load(SimpleTestSerializer.NAME, Map.of()), true),
				executor, gate, (byte) 10, new LongAdder(), 0, new LongAdder(), executorRejections, shutdownRejections);
	}

	private static RpcMessage request(long id) {
		return RpcMessage.inbound(id, MessageType.REQUEST, (byte) 10, (byte) 0, Status.OK, Map.of(), Unpooled.buffer(4).writeInt(0), null,
				System.nanoTime());
	}

	private static Status replyStatus(EmbeddedChannel channel) {
		RpcMessage response = channel.readOutbound();
		try {
			return response.status();
		} finally {
			response.release();
		}
	}

	@Test
	void saturatedExecutorCountsAsExecutorRejection() throws Exception {
		BusinessExecutor executor = new BusinessExecutor(ExecutorConfig.fixed(1, 0));
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		executor.execute(() -> {
			started.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			started.await(10, TimeUnit.SECONDS);
			EmbeddedChannel channel = new EmbeddedChannel(handler(executor, new InFlightGate()));
			channel.writeInbound(request(1));
			assertEquals(Status.SERVER_OVERLOADED, replyStatus(channel));
			assertEquals(1, executorRejections.sum());
			assertEquals(0, shutdownRejections.sum());
		} finally {
			release.countDown();
			executor.shutdown();
		}
	}

	/** 执行器过载拒绝的日志：每条连接只有第一次记 warn（之后 debug），计数每次照常；换一条连接重新记一次 warn。 */
	@Test
	void executorRejectionsWarnOncePerConnection() throws Exception {
		BusinessExecutor executor = new BusinessExecutor(ExecutorConfig.fixed(1, 0));
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		executor.execute(() -> {
			started.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			started.await(10, TimeUnit.SECONDS);
			ServerHandler first = handler(executor, new InFlightGate());
			EmbeddedChannel channel = new EmbeddedChannel(first);
			assertFalse(first.executorRejectionWarned());
			for (int i = 1; i <= 3; i++) {
				channel.writeInbound(request(i));
				assertEquals(Status.SERVER_OVERLOADED, replyStatus(channel));
				assertTrue(first.executorRejectionWarned(), "the first rejection on the connection is the one logged at WARN");
			}
			assertEquals(3, executorRejections.sum(), "every rejection is counted");
			ServerHandler second = handler(executor, new InFlightGate());
			EmbeddedChannel other = new EmbeddedChannel(second);
			assertFalse(second.executorRejectionWarned(), "a new connection gets its own first warning");
			other.writeInbound(request(4));
			assertEquals(Status.SERVER_OVERLOADED, replyStatus(other));
			assertTrue(second.executorRejectionWarned());
			assertEquals(4, executorRejections.sum());
		} finally {
			release.countDown();
			executor.shutdown();
		}
	}

	@Test
	void closedGateCountsAsShutdownRejection() {
		BusinessExecutor executor = new BusinessExecutor(ExecutorConfig.fixed(1));
		try {
			InFlightGate gate = new InFlightGate();
			gate.close();
			EmbeddedChannel channel = new EmbeddedChannel(handler(executor, gate));
			channel.writeInbound(request(1));
			channel.writeInbound(request(2));
			assertEquals(Status.SERVER_OVERLOADED, replyStatus(channel));
			assertEquals(Status.SERVER_OVERLOADED, replyStatus(channel));
			assertEquals(2, shutdownRejections.sum());
			assertEquals(0, executorRejections.sum());
		} finally {
			executor.shutdown();
		}
	}

	@Test
	void closedExecutorCountsAsShutdownRejection() {
		BusinessExecutor executor = new BusinessExecutor(ExecutorConfig.fixed(1));
		executor.shutdown();
		EmbeddedChannel channel = new EmbeddedChannel(handler(executor, new InFlightGate()));
		channel.writeInbound(request(1));
		assertEquals(Status.SERVER_OVERLOADED, replyStatus(channel));
		assertEquals(1, shutdownRejections.sum());
		assertEquals(0, executorRejections.sum());
	}
}
