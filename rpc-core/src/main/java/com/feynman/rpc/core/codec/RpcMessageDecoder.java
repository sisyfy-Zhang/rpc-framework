package com.feynman.rpc.core.codec;

import java.util.List;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * 把上游 {@code LengthFieldBasedFrameDecoder} 已经攒好的一帧完整数据解析成 {@link RpcMessage}。
 * magic number 不对会直接拒绝，而不是当垃圾数据继续往下解——避免把明显不合法的帧当成正常数据处理。
 */
public class RpcMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		short magic = in.readShort();
		if (magic != ProtocolConstants.MAGIC) {
			throw new IllegalStateException("Bad magic number: " + Integer.toHexString(magic & 0xFFFF));
		}
		in.readByte(); // version 字段：预留给以后协商用，目前忽略
		byte serializeType = in.readByte();
		MessageType messageType = MessageType.fromCode(in.readByte());
		Status status = Status.fromCode(in.readByte());
		long requestId = in.readLong();
		int bodyLength = in.readInt();

		byte[] payload = new byte[bodyLength];
		if (bodyLength > 0) {
			in.readBytes(payload);
		}

		out.add(new RpcMessage(requestId, messageType, serializeType, status, payload));
	}
}
