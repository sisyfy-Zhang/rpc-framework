package com.feynman.rpc.core.protocol;

/** 协议帧类型，对应协议头中的 messageType 字节。 */
public enum MessageType {

	REQUEST((byte) 0),
	RESPONSE((byte) 1),
	HEARTBEAT_PING((byte) 2),
	HEARTBEAT_PONG((byte) 3),
	NEGOTIATE_REQUEST((byte) 4),
	NEGOTIATE_RESPONSE((byte) 5);

	private final byte code;

	MessageType(byte code) {
		this.code = code;
	}

	public byte code() {
		return code;
	}

	/** 未知编码返回 null（解码器据此判定帧非法），不抛异常。 */
	public static MessageType find(byte code) {
		for (MessageType type : values()) {
			if (type.code == code) {
				return type;
			}
		}
		return null;
	}

	public static MessageType fromCode(byte code) {
		MessageType type = find(code);
		if (type == null) {
			throw new IllegalArgumentException("Unknown message type code: " + code);
		}
		return type;
	}
}
