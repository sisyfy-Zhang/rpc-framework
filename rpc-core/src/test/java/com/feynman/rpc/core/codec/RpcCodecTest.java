package com.feynman.rpc.core.codec;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import io.netty.channel.embedded.EmbeddedChannel;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RpcCodecTest {

	@Test
	void encodesAndDecodesRequestRoundTrip() {
		EmbeddedChannel channel = new EmbeddedChannel(new RpcEncoder(), new RpcMessageDecoder());

		byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
		RpcMessage original = new RpcMessage(42L, MessageType.REQUEST, (byte) 1, Status.OK, payload);

		assertEquals(true, channel.writeOutbound(original));
		Object encoded = channel.readOutbound();
		assertEquals(true, channel.writeInbound(encoded));

		RpcMessage decoded = channel.readInbound();
		assertEquals(original.getRequestId(), decoded.getRequestId());
		assertEquals(original.getMessageType(), decoded.getMessageType());
		assertEquals(original.getSerializeType(), decoded.getSerializeType());
		assertEquals(original.getStatus(), decoded.getStatus());
		assertArrayEquals(original.getPayload(), decoded.getPayload());
	}

	@Test
	void handlesEmptyPayloadHeartbeat() {
		EmbeddedChannel channel = new EmbeddedChannel(new RpcEncoder(), new RpcMessageDecoder());

		RpcMessage ping = RpcMessage.heartbeatPing(7L);
		channel.writeOutbound(ping);
		Object encoded = channel.readOutbound();
		channel.writeInbound(encoded);

		RpcMessage decoded = channel.readInbound();
		assertEquals(MessageType.HEARTBEAT_PING, decoded.getMessageType());
		assertEquals(0, decoded.getPayload().length);
	}

	@Test
	void rejectsBadMagicNumber() {
		EmbeddedChannel channel = new EmbeddedChannel(new RpcMessageDecoder());
		io.netty.buffer.ByteBuf garbage = io.netty.buffer.Unpooled.buffer();
		garbage.writeShort(0x1234); // 错误的 magic number
		garbage.writeByte(1);
		garbage.writeByte(1);
		garbage.writeByte(MessageType.REQUEST.code());
		garbage.writeByte(Status.OK.code());
		garbage.writeLong(1L);
		garbage.writeInt(0);

		assertThrows(Exception.class, () -> channel.writeInbound(garbage));
	}
}
