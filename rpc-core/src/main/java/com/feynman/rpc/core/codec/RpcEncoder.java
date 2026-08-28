package com.feynman.rpc.core.codec;

import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 写入固定 18 字节协议头（见 {@link ProtocolConstants}），随后是 payload 字节。用定长头部
 * 而不是按换行符分帧的文本协议，是为了避免 payload 里恰好出现换行符导致解析错乱，
 * 同时能对帧长度做真正的校验。
 */
public class RpcEncoder extends MessageToByteEncoder<RpcMessage> {

	@Override
	protected void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) {
		byte[] payload = msg.getPayload() == null ? new byte[0] : msg.getPayload();

		out.writeShort(ProtocolConstants.MAGIC);
		out.writeByte(ProtocolConstants.VERSION);
		out.writeByte(msg.getSerializeType());
		out.writeByte(msg.getMessageType().code());
		out.writeByte(msg.getStatus() == null ? 0 : msg.getStatus().code());
		out.writeLong(msg.getRequestId());
		out.writeInt(payload.length);
		out.writeBytes(payload);
	}
}
