package io.github.sisyfyzhang.steadyrpc.core.protocol;

import java.util.Map;
import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCounted;

/**
 * 一帧协议 v2 消息：定长头的字段 + 已解码的 attachments + 未解码的 body。
 *
 * <p>为什么 body 是 {@link ByteBuf} 而不是 byte[]：入站时 body 是帧缓冲区上的一个 retained slice，
 * IO 线程不拷贝、不反序列化，交给业务线程再解（DESIGN §3.5）。框架自己的出站请求与响应不走 ByteBuf body：
 * 调用方线程与业务线程（常是虚拟线程）都不碰池化分配器，消息只携带备好的字节（{@link OutboundBody}），
 * 由编码器在事件循环上一次写成整帧。ByteBuf body 的出站消息只剩心跳、协商（在事件循环上构造，用非池化的包装数组）。
 *
 * <p>引用计数委托给 body：谁拿到消息谁负责 {@link #release()}（入站由处理者释放，出站交给 Netty 后由编码器释放）。
 * 没有 body 的消息（心跳）用 {@link Unpooled#EMPTY_BUFFER}，释放是空操作。
 */
public final class RpcMessage implements ReferenceCounted {

	private final long requestId;
	private final MessageType messageType;
	private final byte serializer;
	private final byte flags;
	private final Status status;
	private final Map<String, String> attachments;
	private final ByteBuf body;
	private final OutboundBody outboundBody;
	private final String malformedAttachments;
	private final long receivedNanos;

	private RpcMessage(long requestId, MessageType messageType, byte serializer, byte flags, Status status,
			Map<String, String> attachments, ByteBuf body, OutboundBody outboundBody, String malformedAttachments,
			long receivedNanos) {
		this.requestId = requestId;
		this.messageType = messageType;
		this.serializer = serializer;
		this.flags = flags;
		this.status = status;
		this.attachments = attachments == null || attachments.isEmpty() ? Map.of() : attachments;
		this.body = body == null ? Unpooled.EMPTY_BUFFER : body;
		this.outboundBody = outboundBody;
		this.malformedAttachments = malformedAttachments;
		this.receivedNanos = receivedNanos;
	}

	/** 出站消息。{@code body} 的所有权随消息转移。 */
	public static RpcMessage outbound(long requestId, MessageType messageType, byte serializer, byte flags, Status status,
			Map<String, String> attachments, ByteBuf body) {
		return new RpcMessage(requestId, messageType, serializer, flags, status, attachments, body, null, null, 0L);
	}

	/**
	 * 解码器产出的入站消息。
	 *
	 * @param malformedAttachments attachments 段解码失败（格式错误 / 超限）时的原因，正常为 null——
	 *        这类错误不关连接，由服务端带着 requestId 回 BAD_REQUEST
	 * @param receivedNanos IO 线程解码该帧时的 {@link System#nanoTime()}，服务端 deadline 判定的起点
	 */
	public static RpcMessage inbound(long requestId, MessageType messageType, byte serializer, byte flags, Status status,
			Map<String, String> attachments, ByteBuf body, String malformedAttachments, long receivedNanos) {
		return new RpcMessage(requestId, messageType, serializer, flags, status, attachments, body, null,
				malformedAttachments, receivedNanos);
	}

	public static RpcMessage request(long requestId, byte serializer, Map<String, String> attachments, ByteBuf body) {
		return outbound(requestId, MessageType.REQUEST, serializer, (byte) 0, Status.OK, attachments, body);
	}

	/**
	 * 客户端发出的请求：body 只是备好的字节（{@link OutboundBody}），由编码器在事件循环上分配缓冲区写出——
	 * 调用方线程不碰池化分配器。这样的消息没有引用计数的资源，{@link #release()} 是空操作。
	 */
	public static RpcMessage request(long requestId, byte serializer, Map<String, String> attachments, OutboundBody body) {
		return new RpcMessage(requestId, MessageType.REQUEST, serializer, (byte) 0, Status.OK, attachments, null,
				Objects.requireNonNull(body, "body"), null, 0L);
	}

	public static RpcMessage response(long requestId, byte serializer, Status status, Map<String, String> attachments,
			ByteBuf body) {
		return outbound(requestId, MessageType.RESPONSE, serializer, (byte) 0, status, attachments, body);
	}

	/**
	 * 服务端回包：body 只是备好的字节（{@link OutboundBody}，OK 的返回值或错误信封），由编码器在事件循环上分配缓冲区写出——
	 * 业务线程（含虚拟线程执行器）不碰池化分配器。这样的消息没有引用计数的资源，{@link #release()} 是空操作。
	 */
	public static RpcMessage response(long requestId, byte serializer, Status status, Map<String, String> attachments,
			OutboundBody body) {
		return new RpcMessage(requestId, MessageType.RESPONSE, serializer, (byte) 0, status, attachments, null,
				Objects.requireNonNull(body, "body"), null, 0L);
	}

	public static RpcMessage heartbeatPing() {
		return outbound(0, MessageType.HEARTBEAT_PING, (byte) 0, (byte) 0, Status.OK, Map.of(), Unpooled.EMPTY_BUFFER);
	}

	public static RpcMessage heartbeatPong(long requestId) {
		return outbound(requestId, MessageType.HEARTBEAT_PONG, (byte) 0, (byte) 0, Status.OK, Map.of(), Unpooled.EMPTY_BUFFER);
	}

	/** 协商请求：body 为本端支持的序列化编码，按优先级排列。 */
	public static RpcMessage negotiateRequest(byte[] codesByPriority) {
		return outbound(0, MessageType.NEGOTIATE_REQUEST, (byte) 0, (byte) 0, Status.OK, Map.of(),
				Unpooled.wrappedBuffer(codesByPriority.clone()));
	}

	/** 协商响应：body 为 1 字节，选中的序列化编码。 */
	public static RpcMessage negotiateResponse(byte chosen) {
		return outbound(0, MessageType.NEGOTIATE_RESPONSE, (byte) 0, (byte) 0, Status.OK, Map.of(),
				Unpooled.wrappedBuffer(new byte[] { chosen }));
	}

	public long requestId() {
		return requestId;
	}

	public MessageType messageType() {
		return messageType;
	}

	public byte serializer() {
		return serializer;
	}

	public byte flags() {
		return flags;
	}

	public boolean isOneway() {
		return (flags & ProtocolConstants.FLAG_ONEWAY) != 0;
	}

	public Status status() {
		return status;
	}

	/** 只读；从不为 null。 */
	public Map<String, String> attachments() {
		return attachments;
	}

	/**
	 * 从不为 null；读取请用 {@code duplicate()} / {@code slice()}，不要移动它的读指针以外的状态。
	 * 携带 {@link #outboundBody()} 的出站请求 / 响应，这里是空缓冲区。
	 */
	public ByteBuf body() {
		return body;
	}

	/** 由编码器在事件循环上写出的 body（见 {@link OutboundBody}）；其它消息为 null。 */
	public OutboundBody outboundBody() {
		return outboundBody;
	}

	/** attachments 段解码失败的原因；正常为 null。 */
	public String malformedAttachments() {
		return malformedAttachments;
	}

	public long receivedNanos() {
		return receivedNanos;
	}

	@Override
	public int refCnt() {
		return body.refCnt();
	}

	@Override
	public RpcMessage retain() {
		body.retain();
		return this;
	}

	@Override
	public RpcMessage retain(int increment) {
		body.retain(increment);
		return this;
	}

	@Override
	public RpcMessage touch() {
		body.touch();
		return this;
	}

	@Override
	public RpcMessage touch(Object hint) {
		body.touch(hint);
		return this;
	}

	@Override
	public boolean release() {
		return body.release();
	}

	@Override
	public boolean release(int decrement) {
		return body.release(decrement);
	}

	@Override
	public String toString() {
		return "RpcMessage{" + messageType + ", id=" + requestId + ", status=" + status + ", serializer=" + serializer
				+ ", attachments=" + attachments.size() + ", body="
				+ (outboundBody != null ? outboundBody.length() : body.readableBytes()) + "B}";
	}
}
