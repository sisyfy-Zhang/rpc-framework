package io.github.sisyfyzhang.steadyrpc.core.codec;

import java.util.List;
import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.protocol.MessageType;
import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolConstants;
import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolException;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * 把 {@link RpcFrameDecoder} 切好的一帧解析成 {@link RpcMessage}，在 IO 线程上运行：
 * <ul>
 *   <li>头字段逐个校验，magic / version / messageType / status / 长度字段任一非法就抛 {@link ProtocolException}——
 *       帧边界已不可信，handler 关闭连接。version 只接受 2：v1 不兼容，宁可明确拒绝也不要猜着解。</li>
 *   <li>attachments 立即解成 Map（Filter 要在不碰 body 的前提下读到 deadline / trace）。attachments 本身格式错误或超限
 *       不关连接：帧边界是可信的，把原因记在消息上，由服务端带着 requestId 回 BAD_REQUEST。</li>
 *   <li>body 只做 {@code readRetainedSlice}：不拷贝、不反序列化，交给业务线程再解；处理者负责 release。</li>
 * </ul>
 */
public class RpcMessageDecoder extends MessageToMessageDecoder<ByteBuf> {

	private final int maxAttachmentsLength;

	public RpcMessageDecoder() {
		this(ProtocolConstants.DEFAULT_MAX_ATTACHMENTS_LENGTH);
	}

	public RpcMessageDecoder(int maxAttachmentsLength) {
		this.maxAttachmentsLength = maxAttachmentsLength;
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		long receivedNanos = System.nanoTime();
		if (in.readableBytes() < ProtocolConstants.HEADER_LENGTH) {
			throw new ProtocolException("frame shorter than header: " + in.readableBytes() + " bytes");
		}
		short magic = in.readShort();
		if (magic != ProtocolConstants.MAGIC) {
			throw new ProtocolException("bad magic 0x" + Integer.toHexString(magic & 0xFFFF));
		}
		byte version = in.readByte();
		if (version != ProtocolConstants.VERSION) {
			throw new ProtocolException("unsupported protocol version " + version + " (only v" + ProtocolConstants.VERSION
					+ " is accepted" + (version == 1 ? "; v1 is no longer supported" : "") + ")");
		}
		byte typeCode = in.readByte();
		MessageType type = MessageType.find(typeCode);
		if (type == null) {
			throw new ProtocolException("unknown message type " + typeCode);
		}
		byte serializer = in.readByte();
		byte flags = in.readByte();
		byte statusCode = in.readByte();
		Status status = Status.find(statusCode);
		if (status == null) {
			throw new ProtocolException("unknown status " + statusCode);
		}
		long requestId = in.readLong();
		int frameLength = in.readInt();
		int attachmentsLength = in.readInt();
		if (frameLength != in.readableBytes()) {
			throw new ProtocolException("frameLength " + frameLength + " does not match frame (" + in.readableBytes() + " bytes after header)");
		}
		if (attachmentsLength < 0 || attachmentsLength > frameLength) {
			throw new ProtocolException("attachmentsLength " + attachmentsLength + " out of range [0, " + frameLength + "]");
		}

		Map<String, String> attachments = Map.of();
		String malformed = null;
		if (attachmentsLength > maxAttachmentsLength) {
			malformed = "attachments too large: " + attachmentsLength + " bytes > limit " + maxAttachmentsLength;
		} else {
			try {
				attachments = AttachmentsCodec.decode(in.slice(in.readerIndex(), attachmentsLength), maxAttachmentsLength);
			} catch (ProtocolException e) {
				malformed = e.getMessage();
			}
		}
		in.skipBytes(attachmentsLength);
		ByteBuf body = in.readRetainedSlice(frameLength - attachmentsLength);
		out.add(RpcMessage.inbound(requestId, type, serializer, flags, status, attachments, body, malformed, receivedNanos));
	}
}
