package com.feynman.rpc.core.codec;

import java.util.List;

import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * 写出协议 v2 帧：23 字节定长头 + attachments 段（在这里编码）+ body（业务线程已编码好的缓冲区，原样挂上）。
 *
 * <p>为什么是 MessageToMessageEncoder 而不是 MessageToByteEncoder：body 已经是一个 ByteBuf，按"头缓冲区 + body
 * 缓冲区"两段写出就不用把 body 再拷贝一次。消息本身由父类在编码后 release，这里对 body 额外 retain 一次交给下游。
 */
@Sharable
public class RpcEncoder extends MessageToMessageEncoder<RpcMessage> {

	private final int maxAttachmentsLength;

	public RpcEncoder() {
		this(ProtocolConstants.DEFAULT_MAX_ATTACHMENTS_LENGTH);
	}

	public RpcEncoder(int maxAttachmentsLength) {
		this.maxAttachmentsLength = maxAttachmentsLength;
	}

	@Override
	protected void encode(ChannelHandlerContext ctx, RpcMessage msg, List<Object> out) {
		ByteBuf body = msg.body();
		int bodyLength = body.readableBytes();
		ByteBuf header = ctx.alloc().buffer(ProtocolConstants.HEADER_LENGTH + (msg.attachments().isEmpty() ? 0 : 128));
		try {
			header.writeShort(ProtocolConstants.MAGIC);
			header.writeByte(ProtocolConstants.VERSION);
			header.writeByte(msg.messageType().code());
			header.writeByte(msg.serializer());
			header.writeByte(msg.flags());
			header.writeByte(msg.status().code());
			header.writeLong(msg.requestId());
			int lengthIndex = header.writerIndex();
			header.writeInt(0);
			header.writeInt(0);
			int attachmentsLength = AttachmentsCodec.encode(msg.attachments(), header, maxAttachmentsLength);
			long frameLength = (long) attachmentsLength + bodyLength;
			// 最后一道闸：客户端发送前（NettyClient）与服务端回包前（ServiceDispatcher）已按同一判定拦下超限的帧，
			// 能走到这里失败的只有绕过它们的调用方
			if (ProtocolConstants.exceedsMaxFrame(attachmentsLength, bodyLength)) {
				throw new EncoderException("frame too large: " + (ProtocolConstants.HEADER_LENGTH + frameLength)
						+ " bytes > " + ProtocolConstants.MAX_FRAME_LENGTH);
			}
			header.setInt(lengthIndex, (int) frameLength);
			header.setInt(lengthIndex + 4, attachmentsLength);
		} catch (RuntimeException e) {
			header.release();
			throw e instanceof EncoderException ee ? ee : new EncoderException(e);
		}
		out.add(header);
		if (bodyLength > 0) {
			out.add(body.retainedSlice());
		}
	}
}
