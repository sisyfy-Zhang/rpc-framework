package com.feynman.rpc.core.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 协议 v2 帧：头字段的字节级布局、编码器与解码器往返、非法头的拒绝方式。 */
class RpcCodecTest {

	/** 把出站消息编码成一整块字节（编码器产出头缓冲区 + body 两段）。 */
	static ByteBuf encode(RpcMessage message) {
		EmbeddedChannel out = new EmbeddedChannel(new RpcEncoder());
		out.writeOutbound(message);
		ByteBuf frame = Unpooled.buffer();
		ByteBuf part;
		while ((part = out.readOutbound()) != null) {
			frame.writeBytes(part);
			part.release();
		}
		out.finishAndReleaseAll();
		return frame;
	}

	static EmbeddedChannel decoder() {
		return new EmbeddedChannel(new RpcFrameDecoder(), new RpcMessageDecoder());
	}

	@Test
	void headerLayoutIsExactly23BytesBigEndian() {
		Map<String, String> attachments = new LinkedHashMap<>();
		attachments.put("k", "v");
		ByteBuf body = Unpooled.wrappedBuffer(new byte[] { 9, 8, 7 });
		ByteBuf frame = encode(RpcMessage.outbound(0x0102030405060708L, MessageType.RESPONSE, (byte) 2,
				ProtocolConstants.FLAG_ONEWAY, Status.BIZ_EXCEPTION, attachments, body));
		try {
			// attachments: count(2) + keyLen(2) + "k" + valLen(2) + "v" = 8
			assertEquals(ProtocolConstants.HEADER_LENGTH + 8 + 3, frame.readableBytes());
			assertEquals((short) 0xCAFE, frame.getShort(0));
			assertEquals(2, frame.getByte(2), "version");
			assertEquals(MessageType.RESPONSE.code(), frame.getByte(3));
			assertEquals(2, frame.getByte(4), "serializer");
			assertEquals(ProtocolConstants.FLAG_ONEWAY, frame.getByte(5), "flags");
			assertEquals(Status.BIZ_EXCEPTION.code(), frame.getByte(6));
			assertEquals(0x0102030405060708L, frame.getLong(7));
			assertEquals(8 + 3, frame.getInt(15), "frameLength = attachments + body");
			assertEquals(8, frame.getInt(19), "attachmentsLength");
			assertEquals(1, frame.getUnsignedShort(23), "attachment count");
			assertEquals(9, frame.getByte(23 + 8));
		} finally {
			frame.release();
		}
		assertEquals(0, body.refCnt(), "encoder releases the outbound body");
	}

	@Test
	void encodesAndDecodesRoundTrip() {
		Map<String, String> attachments = Map.of("traceparent", "00-abc-def-01", "rpc.timeout", "250");
		ByteBuf frame = encode(RpcMessage.request(42L, (byte) 1, attachments, Unpooled.wrappedBuffer("hello".getBytes())));

		EmbeddedChannel channel = decoder();
		channel.writeInbound(frame);
		RpcMessage decoded = channel.readInbound();
		try {
			assertEquals(42L, decoded.requestId());
			assertEquals(MessageType.REQUEST, decoded.messageType());
			assertEquals(1, decoded.serializer());
			assertEquals(Status.OK, decoded.status());
			assertEquals(attachments, decoded.attachments());
			assertNull(decoded.malformedAttachments());
			assertEquals("hello", decoded.body().toString(java.nio.charset.StandardCharsets.UTF_8));
			assertTrue(decoded.receivedNanos() > 0);
		} finally {
			decoded.release();
		}
		assertEquals(0, frame.refCnt(), "the frame buffer is released once the body slice is released");
		assertTrue(channel.finishAndReleaseAll() == false);
	}

	@Test
	void handlesEmptyBodyHeartbeat() {
		EmbeddedChannel channel = decoder();
		channel.writeInbound(encode(RpcMessage.heartbeatPing()));
		RpcMessage decoded = channel.readInbound();
		assertEquals(MessageType.HEARTBEAT_PING, decoded.messageType());
		assertEquals(0, decoded.body().readableBytes());
		assertTrue(decoded.attachments().isEmpty());
		decoded.release();
	}

	@Test
	void rejectsVersion1Frames() {
		ByteBuf frame = encode(RpcMessage.heartbeatPing());
		frame.setByte(2, 1);
		EmbeddedChannel channel = decoder();
		DecoderException e = assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
		assertTrue(e.getMessage().contains("unsupported protocol version 1"), e.getMessage());
		assertEquals(0, frame.refCnt());
	}

	@Test
	void rejectsBadMagicNumber() {
		ByteBuf frame = encode(RpcMessage.heartbeatPing());
		frame.setShort(0, 0x1234);
		EmbeddedChannel channel = decoder();
		assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
		assertEquals(0, frame.refCnt());
	}

	@Test
	void rejectsAttachmentsLengthBeyondFrame() {
		ByteBuf frame = encode(RpcMessage.request(1, (byte) 1, Map.of(), Unpooled.wrappedBuffer(new byte[] { 1, 2 })));
		frame.setInt(19, 3); // attachmentsLength > frameLength(2)
		EmbeddedChannel channel = decoder();
		assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
		assertEquals(0, frame.refCnt());
	}

	@Test
	void malformedAttachmentsAreReportedOnTheMessageWithoutClosingTheFrameStream() {
		ByteBuf frame = encode(RpcMessage.request(7, (byte) 1, Map.of("a", "b"), Unpooled.wrappedBuffer(new byte[] { 1 })));
		frame.setShort(23, 5); // 声称 5 对，实际只有 1 对
		EmbeddedChannel channel = decoder();
		channel.writeInbound(frame);
		RpcMessage decoded = channel.readInbound();
		try {
			assertEquals(7, decoded.requestId());
			assertNotNull(decoded.malformedAttachments());
			assertTrue(decoded.attachments().isEmpty());
			assertEquals(1, decoded.body().readableBytes(), "body is still delivered so the server can reply BAD_REQUEST");
		} finally {
			decoded.release();
		}
	}

	@Test
	void attachmentsOverLimitAreReportedAsMalformed() {
		ByteBuf frame = encode(RpcMessage.request(8, (byte) 1, Map.of("big", "x".repeat(200)), Unpooled.EMPTY_BUFFER));
		EmbeddedChannel channel = new EmbeddedChannel(new RpcFrameDecoder(), new RpcMessageDecoder(64));
		channel.writeInbound(frame);
		RpcMessage decoded = channel.readInbound();
		try {
			assertTrue(decoded.malformedAttachments().contains("too large"), decoded.malformedAttachments());
		} finally {
			decoded.release();
		}
	}

	@Test
	void encoderRefusesAttachmentsOverLimit() {
		EmbeddedChannel channel = new EmbeddedChannel(new RpcEncoder(16));
		ByteBuf body = Unpooled.wrappedBuffer(new byte[] { 1 });
		Exception e = assertThrows(Exception.class,
				() -> channel.writeOutbound(RpcMessage.request(1, (byte) 1, Map.of("k", "x".repeat(64)), body)));
		assertInstanceOf(io.netty.handler.codec.EncoderException.class, e);
		assertEquals(0, body.refCnt(), "a message that failed to encode is still released");
	}

	@Test
	void hexDumpOfAMinimalRequestStaysStable() {
		ByteBuf frame = encode(RpcMessage.request(1, (byte) 2, Map.of(), Unpooled.EMPTY_BUFFER));
		try {
			assertEquals("cafe" + "02" + "00" + "02" + "00" + "00" + "0000000000000001" + "00000000" + "00000000",
					ByteBufUtil.hexDump(frame));
		} finally {
			frame.release();
		}
	}

	@Test
	void requestEnvelopeAndValueRoundTrip() {
		ByteBuf body = Unpooled.buffer();
		EnvelopeCodec.writeRequest(body, "com.x.Svc", "1.0", "gray", "reserve", "long,java.lang.Integer",
				java.util.Arrays.asList(new byte[] { 1, 2 }, null));
		EnvelopeCodec.RequestEnvelope envelope = EnvelopeCodec.readRequest(body);
		assertEquals(List.of("com.x.Svc", "1.0", "gray", "reserve", "long,java.lang.Integer"),
				List.of(envelope.service(), envelope.version(), envelope.group(), envelope.method(), envelope.paramSig()));
		assertEquals(2, envelope.args().size());
		assertEquals(2, envelope.args().get(0).readableBytes());
		assertNull(envelope.args().get(1));
		body.release();
	}

	/**
	 * 客户端请求改为"调用方只备字节、编码器在事件循环上一次写整帧"后，线上字节必须与原来"调用方写 body 缓冲区、
	 * 编码器挂在头后面"的两段写法逐字节相同（协议格式不变）；含多字节 UTF-8、代理对、孤立代理、null 参数与 attachments。
	 */
	@Test
	void deferredRequestBodyEncodesToTheSameBytesAsABufferBody() {
		Map<String, String> attachments = new LinkedHashMap<>();
		attachments.put("rpc.timeout", "1500");
		attachments.put("trace", "库存-😀");
		String service = "com.x.库存Service";
		String lone = "m\uD800x"; // 孤立代理：Netty 写成 '?'
		List<byte[]> args = java.util.Arrays.asList(new byte[] { 1, 2, 3 }, null, new byte[0], new byte[300]);

		ByteBuf body = Unpooled.buffer();
		EnvelopeCodec.writeRequest(body, service, "1.0", "灰度", lone, "byte[],java.lang.String", args);
		ByteBuf viaBuffer = encode(RpcMessage.request(77, (byte) 3, attachments, body));

		EnvelopeCodec.OutboundRequest deferred = EnvelopeCodec.request(service, "1.0", "灰度", lone, "byte[],java.lang.String", args);
		ByteBuf viaDeferred = encode(RpcMessage.request(77, (byte) 3, attachments, deferred));
		try {
			assertEquals(ByteBufUtil.hexDump(viaBuffer), ByteBufUtil.hexDump(viaDeferred));
			int attachmentsLength = AttachmentsCodec.encodedLength(attachments, ProtocolConstants.DEFAULT_MAX_ATTACHMENTS_LENGTH);
			assertEquals(ProtocolConstants.HEADER_LENGTH + attachmentsLength + deferred.length(), viaDeferred.readableBytes(),
					"the precomputed envelope length is exact");
		} finally {
			viaBuffer.release();
			viaDeferred.release();
		}
	}

	@Test
	void deferredRequestValidatesLikeWriteRequest() {
		assertThrows(IllegalArgumentException.class,
				() -> EnvelopeCodec.request("s", "", "", "m", "", java.util.Collections.nCopies(256, (byte[]) null)));
		assertThrows(IllegalArgumentException.class, () -> EnvelopeCodec.request("x".repeat(65536), "", "", "m", "", List.of()));
	}
}
