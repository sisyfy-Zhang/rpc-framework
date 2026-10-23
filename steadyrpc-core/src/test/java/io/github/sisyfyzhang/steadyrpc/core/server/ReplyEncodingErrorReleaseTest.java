package io.github.sisyfyzhang.steadyrpc.core.server;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.codec.EnvelopeCodec;
import io.github.sisyfyzhang.steadyrpc.core.codec.RpcEncoder;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MessageType;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MethodSignature;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializers;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SerializationBomb;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.buffer.ExplodingAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回包路径上的 Error（不是 RuntimeException）：
 * <ul>
 *   <li>编码时（事件循环上，例如直接内存耗尽）：编码器已分配的整帧必须先释放再让这次写失败；</li>
 *   <li>序列化返回值时（业务线程上，例如对象图过深的 StackOverflowError）：回 SERIALIZATION_ERROR，而不是让 future 异常完成、
 *       客户端挂到超时；</li>
 *   <li>组装错误回包时（例如异常的 getMessage 本身抛 Error）：退回只带类名的 SERVER_ERROR。</li>
 * </ul>
 * 每种情况下请求都恰好释放一次、没有泄漏。旧实现在业务线程上分配回包 body，只 {@code catch (RuntimeException)}，
 * Error 带着已分配的 body 一起丢失；现在业务线程不再分配（回包携带 OutboundBody），Error 只可能在编码器里遇到缓冲区。
 * 端到端（真实连接、虚拟线程执行器、连接仍可用）见 {@link ServerThreadAllocationTest}。
 */
class ReplyEncodingErrorReleaseTest {

	public interface Echo {
		byte[] echo(byte[] payload);

		Object detonate(String kind);

		String rude(String message);
	}

	private static final ServiceKey KEY = ServiceKey.of(Echo.class);
	private static final SimpleTestSerializer SERIALIZER = new SimpleTestSerializer();

	/** getMessage 本身抛 Error 的业务异常：组装错误信封（取消息、打印堆栈）时就会失败。 */
	static final class RudeException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		@Override
		public String getMessage() {
			throw new StackOverflowError("simulated: message too deep");
		}
	}

	static final class EchoImpl implements Echo {
		@Override
		public byte[] echo(byte[] payload) {
			return payload;
		}

		@Override
		public Object detonate(String kind) {
			return new SerializationBomb(kind.equals("oom")
					? new OutOfMemoryError("simulated: direct buffer memory exhausted while serializing")
					: new StackOverflowError("simulated: object graph too deep"));
		}

		@Override
		public String rude(String message) {
			throw new RudeException();
		}
	}

	private static ServiceDispatcher dispatcher() {
		ServiceDispatcher dispatcher = new ServiceDispatcher(List.of(), Serializers.load(SimpleTestSerializer.NAME, Map.of()), true);
		dispatcher.register(KEY, Echo.class, new EchoImpl());
		return dispatcher;
	}

	private static RpcMessage request(String service, String method, Class<?> paramType, Object arg) throws NoSuchMethodException {
		ByteBuf body = Unpooled.buffer();
		EnvelopeCodec.writeRequest(body, service, KEY.version(), KEY.group(), method,
				MethodSignature.paramSig(Echo.class.getMethod(method, paramType)), List.of(SERIALIZER.serialize(arg, paramType)));
		return RpcMessage.inbound(1, MessageType.REQUEST, SERIALIZER.code(), (byte) 0, Status.OK, Map.of(), body, null,
				System.nanoTime());
	}

	private static RpcMessage echoRequest(String service) throws NoSuchMethodException {
		return request(service, "echo", byte[].class, new byte[1024]);
	}

	/** 在一条分配器会在写入时抛 OutOfMemoryError 的连接上编码回包：写必须失败，且没有缓冲区留在外面。 */
	private static void assertEncodingFailsWithoutLeak(RpcMessage reply) {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.config().setAllocator(new ExplodingAllocator());
		channel.pipeline().addLast(new RpcEncoder());
		ChannelFuture write = channel.writeAndFlush(reply);
		assertFalse(write.isSuccess(), "encoding must fail");
		Throwable cause = write.cause();
		while (cause != null && !(cause instanceof OutOfMemoryError)) {
			cause = cause.getCause();
		}
		assertNotNull(cause, () -> "expected the simulated OutOfMemoryError, got " + write.cause());
		assertNull(channel.readOutbound(), "nothing may be written after a failed encode");
		assertFalse(channel.finishAndReleaseAll());
	}

	/** 正常连接上编码回包、解出错误信封（同时释放编码出的帧）。 */
	private static EnvelopeCodec.ErrorEnvelope encodeAndReadError(RpcMessage reply) {
		EmbeddedChannel channel = new EmbeddedChannel(new RpcEncoder());
		assertTrue(channel.writeOutbound(reply));
		ByteBuf frame = channel.readOutbound();
		try {
			assertNull(channel.readOutbound(), "a deferred body is encoded as one frame");
			return EnvelopeCodec.readError(frame.slice(23, frame.readableBytes() - 23));
		} finally {
			frame.release();
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void anErrorWhileEncodingTheResultReleasesTheFrame() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			RpcMessage request = echoRequest(KEY.interfaceName());
			RpcMessage reply = dispatcher().handle(request).join();
			assertEquals(Status.OK, reply.status());
			assertNotNull(reply.outboundBody(), "the business thread only prepares bytes; the encoder allocates the frame");
			assertEquals(0, request.refCnt(), "the request is released exactly once");
			assertEncodingFailsWithoutLeak(reply);
			probe.assertNoLeaks();
		}
	}

	@Test
	void anErrorWhileEncodingAFrameworkErrorReleasesTheFrame() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			RpcMessage request = echoRequest("com.example.NoSuchService"); // SERVICE_NOT_FOUND → frameworkError
			RpcMessage reply = dispatcher().handle(request).join();
			assertEquals(Status.SERVICE_NOT_FOUND, reply.status());
			assertNotNull(reply.outboundBody());
			assertEquals(0, request.refCnt(), "the request is released exactly once");
			assertEncodingFailsWithoutLeak(reply);
			probe.assertNoLeaks();
		}
	}

	@Test
	void anErrorWhileSerializingTheResultRepliesSerializationError() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			for (String kind : List.of("stack", "oom")) {
				RpcMessage request = request(KEY.interfaceName(), "detonate", String.class, kind);
				RpcMessage reply = dispatcher().handle(request).join(); // 不是异常完成：那样客户端只能等到超时
				assertEquals(Status.SERIALIZATION_ERROR, reply.status(), kind);
				assertEquals(0, request.refCnt(), "the request is released exactly once");
				EnvelopeCodec.ErrorEnvelope error = encodeAndReadError(reply);
				assertTrue(error.message().contains(kind.equals("oom") ? "OutOfMemoryError" : "StackOverflowError"), error.message());
			}
			probe.assertNoLeaks();
		}
	}

	@Test
	void anErrorWhileBuildingTheErrorReplyFallsBackToServerError() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			RpcMessage request = request(KEY.interfaceName(), "rude", String.class, "x");
			RpcMessage reply = dispatcher().handle(request).join();
			assertEquals(Status.SERVER_ERROR, reply.status());
			assertEquals(0, request.refCnt(), "the request is released exactly once");
			EnvelopeCodec.ErrorEnvelope error = encodeAndReadError(reply);
			assertTrue(error.message().startsWith("failed to build reply: java.lang.StackOverflowError"), error.message());
			probe.assertNoLeaks();
		}
	}
}
