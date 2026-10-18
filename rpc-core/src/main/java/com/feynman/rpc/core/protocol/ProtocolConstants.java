package com.feynman.rpc.core.protocol;

/**
 * 协议 v2 的帧布局常量（DESIGN §3.5）。整数一律大端。
 *
 * <pre>
 * 偏移 长度 字段
 *  0    2   magic 0xCAFE
 *  2    1   version = 2（收到其他值直接拒绝，不兼容 v1）
 *  3    1   messageType
 *  4    1   serializer（协商结果的编码）
 *  5    1   flags（bit0 压缩，bit1 单向，其余保留）
 *  6    1   status（请求固定 0）
 *  7    8   requestId
 * 15    4   frameLength：头之后的总字节数 = attachmentsLength + bodyLength
 * 19    4   attachmentsLength
 * 23    -   attachments 段，随后是 body 段
 * </pre>
 *
 * 为什么把 attachmentsLength 放进定长头：IO 线程只读头就能切出 attachments 段解码，body 原样保留成
 * slice 交给业务线程，不需要先解析 body 才知道元数据在哪。
 */
public final class ProtocolConstants {

	private ProtocolConstants() {
	}

	public static final short MAGIC = (short) 0xCAFE;
	public static final byte VERSION = 2;

	public static final int HEADER_LENGTH = 23;

	/** frameLength 字段的偏移与长度，供帧解码器使用；帧总长 = 19 + frameLength + 4（attachmentsLength 字段）。 */
	public static final int FRAME_LENGTH_OFFSET = 15;
	public static final int FRAME_LENGTH_FIELD_LENGTH = 4;
	/** LengthFieldBasedFrameDecoder 的 lengthAdjustment：frameLength 之后还有 4 字节的 attachmentsLength 属于头。 */
	public static final int FRAME_LENGTH_ADJUSTMENT = HEADER_LENGTH - FRAME_LENGTH_OFFSET - FRAME_LENGTH_FIELD_LENGTH;

	/** 整帧（含头）上限。 */
	public static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;

	/** attachments 段默认上限：元数据不是载荷，超限视为 BAD_REQUEST，而不是撑大每个请求。 */
	public static final int DEFAULT_MAX_ATTACHMENTS_LENGTH = 64 * 1024;

	/** flags bit0：body 已压缩（预留，当前实现不支持，收到即 BAD_REQUEST）。 */
	public static final byte FLAG_COMPRESSED = 0x01;
	/** flags bit1：单向调用，服务端不回包（预留，当前客户端不发送）。 */
	public static final byte FLAG_ONEWAY = 0x02;

	/**
	 * 一帧（23 字节头 + attachments 段 + body 段）是否超过 {@link #MAX_FRAME_LENGTH}。
	 *
	 * <p>为什么集中成一个判定：编码器（最后一道闸）、客户端发送前检查、服务端回包前检查必须用同一个阈值——
	 * 任何一处比编码器宽松，超限的帧就会在编码器里才失败，表现为"写失败"（客户端被当成传输失败重试、
	 * 服务端回包丢失让对端等到超时）；比编码器严格则会拒绝本可以发出的帧。接收方的帧解码器用同一个上限。
	 */
	public static boolean exceedsMaxFrame(long attachmentsLength, long bodyLength) {
		return HEADER_LENGTH + attachmentsLength + bodyLength > MAX_FRAME_LENGTH;
	}
}
