package io.github.sisyfyzhang.steadyrpc.core.codec;

import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolConstants;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * 分帧：只看头里的 frameLength（偏移 15，4 字节），帧总长 = 23 + frameLength。不解析任何其他字段——
 * magic / version 的校验在 {@link RpcMessageDecoder}，这里保持纯粹的"切出完整一帧"。超过上限抛
 * TooLongFrameException（一种 DecoderException），handler 据此关闭连接。
 */
public class RpcFrameDecoder extends LengthFieldBasedFrameDecoder {

	public RpcFrameDecoder() {
		super(ProtocolConstants.MAX_FRAME_LENGTH, ProtocolConstants.FRAME_LENGTH_OFFSET,
				ProtocolConstants.FRAME_LENGTH_FIELD_LENGTH, ProtocolConstants.FRAME_LENGTH_ADJUSTMENT, 0);
	}
}
