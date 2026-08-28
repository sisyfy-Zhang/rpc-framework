package com.feynman.rpc.core.protocol;

/** 响应结果，对应协议头中的 status 字节（只在 RESPONSE 帧里有意义）。 */
public enum Status {

	OK((byte) 0),
	SERVER_ERROR((byte) 1),
	NOT_FOUND((byte) 2),
	BAD_REQUEST((byte) 3);

	private final byte code;

	Status(byte code) {
		this.code = code;
	}

	public byte code() {
		return code;
	}

	public static Status fromCode(byte code) {
		for (Status status : values()) {
			if (status.code == code) {
				return status;
			}
		}
		throw new IllegalArgumentException("Unknown status code: " + code);
	}
}
