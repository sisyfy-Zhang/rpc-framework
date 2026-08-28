package com.feynman.rpc.core.protocol;

public final class ProtocolConstants {

	private ProtocolConstants() {
	}

	public static final short MAGIC = (short) 0xCAFE;
	public static final byte VERSION = 1;

	/** magic(2) + version(1) + serializeType(1) + messageType(1) + status(1) + requestId(8) + bodyLength(4)，共 18 字节。 */
	public static final int HEADER_LENGTH = 18;

	/** bodyLength 字段（4 字节）在协议头中的偏移量，供 LengthFieldBasedFrameDecoder 使用。 */
	public static final int LENGTH_FIELD_OFFSET = 14;
	public static final int LENGTH_FIELD_LENGTH = 4;

	public static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;
}
