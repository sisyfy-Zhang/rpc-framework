package com.feynman.rpc.core.codec;

import java.util.List;

import com.feynman.rpc.core.protocol.OutboundBody;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * 写出协议 v2 帧：23 字节定长头 + attachments 段（在这里编码）+ body。运行在连接的事件循环线程上。
 *
 * <p>两种 body：
 * <ul>
 *   <li>{@link RpcMessage#outboundBody()}（客户端请求）：字节已备好、还没有缓冲区。这里用 {@code ctx.alloc()}
 *       按确切长度<b>一次</b>分配，写入头 + attachments + body，单个缓冲区交给下游——分配发生在事件循环上
 *       （有线程缓存、不扩容），调用方线程从不碰池化分配器（理由见 {@link OutboundBody}）。</li>
 *   <li>{@link RpcMessage#body()}（其余出站消息，如服务端响应）：已经是一个 ByteBuf，按"头缓冲区 + body 缓冲区"两段写出，
 *       不再拷贝一次。消息本身由父类在编码后 release，这里对 body 额外 retain 一次（retainedSlice）交给下游。</li>
 * </ul>
 * 任何失败都先释放本方法已分配的缓冲区再抛出；父类把异常包成 EncoderException 让这次写失败，并释放消息。
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
		OutboundBody deferred = msg.outboundBody();
		if (deferred != null) {
			out.add(encodeWhole(ctx, msg, deferred));
			return;
		}
		ByteBuf body = msg.body();
		int bodyLength = body.readableBytes();
		ByteBuf header = ctx.alloc().buffer(ProtocolConstants.HEADER_LENGTH + (msg.attachments().isEmpty() ? 0 : 128));
		try {
			int lengthIndex = writeFixedHeader(header, msg);
			int attachmentsLength = AttachmentsCodec.encode(msg.attachments(), header, maxAttachmentsLength);
			checkFrame(attachmentsLength, bodyLength);
			header.setInt(lengthIndex, attachmentsLength + bodyLength);
			header.setInt(lengthIndex + 4, attachmentsLength);
		} catch (Throwable e) {
			header.release();
			throw e instanceof EncoderException ee ? ee : new EncoderException(e);
		}
		out.add(header);
		if (bodyLength > 0) {
			out.add(body.retainedSlice());
		}
	}

	/** 整帧写进一个按确切长度分配的缓冲区。 */
	private ByteBuf encodeWhole(ChannelHandlerContext ctx, RpcMessage msg, OutboundBody body) {
		int attachmentsLength;
		try {
			attachmentsLength = AttachmentsCodec.encodedLength(msg.attachments(), maxAttachmentsLength);
		} catch (IllegalArgumentException e) {
			throw new EncoderException(e);
		}
		int bodyLength = body.length();
		checkFrame(attachmentsLength, bodyLength);
		int frameLength = ProtocolConstants.HEADER_LENGTH + attachmentsLength + bodyLength;
		ByteBuf frame = ctx.alloc().buffer(frameLength, frameLength);
		try {
			int lengthIndex = writeFixedHeader(frame, msg);
			frame.setInt(lengthIndex, attachmentsLength + bodyLength);
			frame.setInt(lengthIndex + 4, attachmentsLength);
			AttachmentsCodec.encode(msg.attachments(), frame, maxAttachmentsLength);
			body.writeTo(frame);
			if (frame.readableBytes() != frameLength) {
				throw new IllegalStateException("encoded " + frame.readableBytes() + " bytes, expected " + frameLength);
			}
			return frame;
		} catch (Throwable e) {
			frame.release();
			throw e instanceof EncoderException ee ? ee : new EncoderException(e);
		}
	}

	/** 写定长头，两个长度字段先占位为 0；返回 frameLength 字段的位置（attachmentsLength 紧随其后）。 */
	private static int writeFixedHeader(ByteBuf out, RpcMessage msg) {
		out.writeShort(ProtocolConstants.MAGIC);
		out.writeByte(ProtocolConstants.VERSION);
		out.writeByte(msg.messageType().code());
		out.writeByte(msg.serializer());
		out.writeByte(msg.flags());
		out.writeByte(msg.status().code());
		out.writeLong(msg.requestId());
		int lengthIndex = out.writerIndex();
		out.writeInt(0);
		out.writeInt(0);
		return lengthIndex;
	}

	/**
	 * 最后一道闸：客户端发送前（NettyClient）与服务端回包前（ServiceDispatcher）已按同一判定拦下超限的帧，
	 * 能走到这里失败的只有绕过它们的调用方。
	 */
	private static void checkFrame(int attachmentsLength, int bodyLength) {
		if (ProtocolConstants.exceedsMaxFrame(attachmentsLength, bodyLength)) {
			throw new EncoderException("frame too large: " + (ProtocolConstants.HEADER_LENGTH + (long) attachmentsLength + bodyLength)
					+ " bytes > " + ProtocolConstants.MAX_FRAME_LENGTH);
		}
	}
}
