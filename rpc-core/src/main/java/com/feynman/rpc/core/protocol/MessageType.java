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

	public static MessageType fromCode(byte code) {
		for (MessageType type : values()) {
			if (type.code == code) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown message type code: " + code);
	}
}
