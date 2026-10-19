package com.feynman.rpc.core.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.MethodSignature;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufLeakProbe;
import io.netty.buffer.ExplodingAllocator;
import io.netty.buffer.Unpooled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回包编码时抛出 Error（不是 RuntimeException，例如直接内存耗尽）：已分配的回包 body 必须先释放再抛出，
 * 请求本身也恰好释放一次。旧实现只 {@code catch (RuntimeException)}，Error 带着已分配的 body 一起丢失。
 */
class ReplyEncodingErrorReleaseTest {

	public interface Echo {
		byte[] echo(byte[] payload);
	}

	private static final ServiceKey KEY = ServiceKey.of(Echo.class);
	private static final SimpleTestSerializer SERIALIZER = new SimpleTestSerializer();

	private static ServiceDispatcher dispatcher() {
		ServiceDispatcher dispatcher = new ServiceDispatcher(List.of(), Serializers.load(SimpleTestSerializer.NAME, Map.of()), false);
		dispatcher.register(KEY, Echo.class, (Echo) payload -> payload);
		return dispatcher;
	}

	private static RpcMessage request(String service) throws NoSuchMethodException {
		ByteBuf body = Unpooled.buffer();
		EnvelopeCodec.writeRequest(body, service, KEY.version(), KEY.group(), "echo",
				MethodSignature.paramSig(Echo.class.getMethod("echo", byte[].class)),
				List.of(SERIALIZER.serialize(new byte[1024], byte[].class)));
		return RpcMessage.inbound(1, MessageType.REQUEST, SERIALIZER.code(), (byte) 0, Status.OK, Map.of(), body, null,
				System.nanoTime());
	}

	@Test
	void anErrorWhileWritingTheResultReleasesTheReplyBody() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			RpcMessage request = request(KEY.interfaceName());
			CompletableFuture<RpcMessage> reply = dispatcher().handle(request, new ExplodingAllocator());
			CompletionException e = assertThrows(CompletionException.class, reply::join);
			assertInstanceOf(OutOfMemoryError.class, e.getCause());
			assertEquals(0, request.refCnt(), "the request is released exactly once");
			probe.assertNoLeaks();
		}
	}

	@Test
	void anErrorWhileWritingAFrameworkErrorReleasesItsBody() throws Exception {
		try (ByteBufLeakProbe probe = ByteBufLeakProbe.start()) {
			RpcMessage request = request("com.example.NoSuchService"); // SERVICE_NOT_FOUND → frameworkError
			OutOfMemoryError error = assertThrows(OutOfMemoryError.class, () -> dispatcher().handle(request, new ExplodingAllocator()));
			assertTrue(error.getMessage().startsWith("simulated"), error.toString());
			assertEquals(0, request.refCnt(), "the request is released exactly once");
			probe.assertNoLeaks();
		}
	}
}
