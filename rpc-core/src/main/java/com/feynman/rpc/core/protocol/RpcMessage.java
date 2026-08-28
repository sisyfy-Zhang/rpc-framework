package com.feynman.rpc.core.protocol;

/**
 * 协议信封：{@code RpcEncoder}/{@code RpcMessageDecoder} 读写的正是这个对象。{@code payload}
 * 在这里只是不透明字节——只有知道 {@code messageType} 之后，才会被解读成 {@link RpcRequest}/
 * {@link RpcResponse}/协商用的字节列表，或者干脆什么都不是。
 */
public class RpcMessage {

	private long requestId;
	private MessageType messageType;
	private byte serializeType;
	private Status status;
	private byte[] payload;

	public RpcMessage() {
	}

	public RpcMessage(long requestId, MessageType messageType, byte serializeType, Status status, byte[] payload) {
		this.requestId = requestId;
		this.messageType = messageType;
		this.serializeType = serializeType;
		this.status = status;
		this.payload = payload;
	}

	public static RpcMessage heartbeatPing(long requestId) {
		return new RpcMessage(requestId, MessageType.HEARTBEAT_PING, (byte) 0, Status.OK, new byte[0]);
	}

	public static RpcMessage heartbeatPong(long requestId) {
		return new RpcMessage(requestId, MessageType.HEARTBEAT_PONG, (byte) 0, Status.OK, new byte[0]);
	}

	public long getRequestId() {
		return requestId;
	}

	public void setRequestId(long requestId) {
		this.requestId = requestId;
	}

	public MessageType getMessageType() {
		return messageType;
	}

	public void setMessageType(MessageType messageType) {
		this.messageType = messageType;
	}

	public byte getSerializeType() {
		return serializeType;
	}

	public void setSerializeType(byte serializeType) {
		this.serializeType = serializeType;
	}

	public Status getStatus() {
		return status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public byte[] getPayload() {
		return payload;
	}

	public void setPayload(byte[] payload) {
		this.payload = payload;
	}
}
