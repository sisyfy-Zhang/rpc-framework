package com.feynman.rpc.it;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.serialize.fastjson2.Fastjson2Serializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 TCP socket 上用<b>手工拼装的协议 v2 字节</b>验证服务端协议栈（分帧 → 解码 → 分发 → 反射调用 → 编码回包）。
 * 客户端刻意不用 RpcClient，也不用框架的编解码器：头的每个字节、信封的每个长度字段都在这里按 DESIGN §3.5 写死，
 * 编解码器实现的改动不会掩盖线上格式的回归。服务端经进程内 TestingServer 注册，顺带断言注册节点就绪。
 */
class DirectSocketRpcTest {

	public interface Echo {
		String echo(String s);
	}

	private final Fastjson2Serializer serializer = new Fastjson2Serializer();
	private final AtomicInteger echoCalls = new AtomicInteger();
	private final BlockingQueue<ByteBuf> frames = new LinkedBlockingQueue<>();
	private ZkFixture zk;
	private RpcServer server;
	private EventLoopGroup clientGroup;
	private Channel clientChannel;

	@BeforeEach
	void setUp() throws Exception {
		zk = new ZkFixture();
		server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).build();
		server.export(Echo.class, s -> {
			echoCalls.incrementAndGet();
			return "echo:" + s;
		});
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求

		clientGroup = new NioEventLoopGroup(1);
		clientChannel = new Bootstrap()
				.group(clientGroup)
				.channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						// frameLength 在偏移 15、4 字节；帧总长 = 23 + frameLength
						ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(1 << 20, 15, 4, 4, 0));
						ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
							@Override
							protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
								if (frame.getByte(3) == 1) { // RESPONSE
									frames.add(frame.retain());
								}
							}
						});
					}
				})
				.connect("127.0.0.1", server.port()).sync().channel();
	}

	@AfterEach
	void tearDown() throws Exception {
		frames.forEach(ByteBuf::release);
		if (clientChannel != null) clientChannel.close().sync();
		if (clientGroup != null) clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
		if (server != null) server.close();
		if (zk != null) zk.close();
	}

	// ------------------------------------------------------------------ 手工拼装

	private static void str(ByteBuf out, String s) {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.writeShort(bytes.length).writeBytes(bytes);
	}

	private static ByteBuf attachments(Map<String, String> map) {
		ByteBuf out = Unpooled.buffer();
		if (!map.isEmpty()) {
			out.writeShort(map.size());
			map.forEach((k, v) -> {
				str(out, k);
				str(out, v);
			});
		}
		return out;
	}

	private ByteBuf requestBody(String service, String method, String paramSig, String arg) {
		ByteBuf body = Unpooled.buffer();
		str(body, service);
		str(body, "");        // version
		str(body, "default"); // group
		str(body, method);
		str(body, paramSig);
		body.writeByte(1);    // argCount
		byte[] argBytes = serializer.serialize(arg, String.class);
		body.writeByte(1).writeInt(argBytes.length).writeBytes(argBytes);
		return body;
	}

	private static ByteBuf frame(int version, long requestId, byte serializerCode, ByteBuf attachments, ByteBuf body) {
		ByteBuf frame = Unpooled.buffer();
		frame.writeShort(0xCAFE);
		frame.writeByte(version);
		frame.writeByte(0); // REQUEST
		frame.writeByte(serializerCode);
		frame.writeByte(0); // flags
		frame.writeByte(0); // status
		frame.writeLong(requestId);
		frame.writeInt(attachments.readableBytes() + body.readableBytes());
		frame.writeInt(attachments.readableBytes());
		frame.writeBytes(attachments).writeBytes(body);
		attachments.release();
		body.release();
		return frame;
	}

	private ByteBuf send(ByteBuf frame) throws Exception {
		clientChannel.writeAndFlush(frame).sync();
		ByteBuf response = frames.poll(5, TimeUnit.SECONDS);
		assertNotNull(response, "no response");
		return response;
	}

	/** 解析错误信封里的 message（className / message / remoteStack 三个 str）。 */
	private static String errorMessage(ByteBuf response) {
		int attachmentsLength = response.getInt(19);
		ByteBuf body = response.slice(23 + attachmentsLength, response.readableBytes() - 23 - attachmentsLength);
		int classLen = body.readUnsignedShort();
		body.skipBytes(classLen);
		int msgLen = body.readUnsignedShort();
		return body.toString(body.readerIndex(), msgLen, StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ 用例

	@Test
	void requestIsDispatchedAndResponseRoundTrips() throws Exception {
		assertEquals(1, zk.providers(ServiceKey.of(Echo.class)).size(), "server should be registered under its interface");

		ByteBuf response = send(frame(2, 77, serializer.code(), attachments(Map.of()),
				requestBody(Echo.class.getName(), "echo", "java.lang.String", "world")));
		try {
			assertEquals(0xCAFE, response.getUnsignedShort(0));
			assertEquals(2, response.getByte(2), "version");
			assertEquals(1, response.getByte(3), "RESPONSE");
			assertEquals(serializer.code(), response.getByte(4), "replies with the request's serializer");
			assertEquals(0, response.getByte(6), "status OK");
			assertEquals(77, response.getLong(7), "requestId echoed");
			assertEquals(response.readableBytes() - 23, response.getInt(15), "frameLength");
			int attachmentsLength = response.getInt(19);
			ByteBuf body = response.slice(23 + attachmentsLength, response.getInt(15) - attachmentsLength);
			assertEquals(1, body.readByte(), "present");
			byte[] value = new byte[body.readInt()];
			body.readBytes(value);
			assertEquals("echo:world", serializer.deserialize(value, String.class));
		} finally {
			response.release();
		}
	}

	@Test
	void unknownServiceReturnsServiceNotFound() throws Exception {
		ByteBuf response = send(frame(2, 5, serializer.code(), attachments(Map.of()),
				requestBody("no.such.Service", "echo", "java.lang.String", "x")));
		try {
			assertEquals(2, response.getByte(6), "SERVICE_NOT_FOUND");
			assertTrue(errorMessage(response).contains("no.such.Service"), errorMessage(response));
		} finally {
			response.release();
		}
	}

	@Test
	void wrongParamSigIsNotFoundRatherThanMisrouted() throws Exception {
		ByteBuf response = send(frame(2, 6, serializer.code(), attachments(Map.of()),
				requestBody(Echo.class.getName(), "echo", "java.lang.Object", "x")));
		try {
			assertEquals(2, response.getByte(6), "SERVICE_NOT_FOUND");
			assertTrue(errorMessage(response).contains("echo(java.lang.Object)"), errorMessage(response));
			assertEquals(0, echoCalls.get());
		} finally {
			response.release();
		}
	}

	@Test
	void malformedAttachmentsGetBadRequestAndTheConnectionStaysUsable() throws Exception {
		ByteBuf badAttachments = Unpooled.buffer().writeShort(3).writeShort(1).writeByte('k'); // 声称 3 对，只有半对
		ByteBuf response = send(frame(2, 9, serializer.code(), badAttachments,
				requestBody(Echo.class.getName(), "echo", "java.lang.String", "x")));
		try {
			assertEquals(9, response.getLong(7));
			assertEquals(1, response.getByte(6), "BAD_REQUEST");
			assertEquals(0, echoCalls.get());
		} finally {
			response.release();
		}
		ByteBuf next = send(frame(2, 10, serializer.code(), attachments(Map.of()),
				requestBody(Echo.class.getName(), "echo", "java.lang.String", "again")));
		try {
			assertEquals(0, next.getByte(6), "the same connection still serves requests");
		} finally {
			next.release();
		}
	}

	@Test
	void expiredRequestIsRejectedWithoutRunningTheMethod() throws Exception {
		ByteBuf response = send(frame(2, 11, serializer.code(), attachments(Map.of("rpc.timeout", "0")),
				requestBody(Echo.class.getName(), "echo", "java.lang.String", "late")));
		try {
			assertEquals(5, response.getByte(6), "DEADLINE_EXCEEDED");
			assertEquals(0, echoCalls.get(), "the business method must not run");
		} finally {
			response.release();
		}
	}

	@Test
	void version1FramesAreRejectedByClosingTheConnection() throws Exception {
		clientChannel.writeAndFlush(frame(1, 12, serializer.code(), attachments(Map.of()),
				requestBody(Echo.class.getName(), "echo", "java.lang.String", "v1"))).sync();
		assertTrue(clientChannel.closeFuture().await(5, TimeUnit.SECONDS), "server must close a connection that speaks v1");
		assertEquals(0, echoCalls.get());
		assertTrue(frames.isEmpty(), "no response for a v1 frame: " + frames.stream().map(ByteBufUtil::hexDump).toList());
	}
}
