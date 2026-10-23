package io.github.sisyfyzhang.steadyrpc.core.protocol;

import io.netty.handler.codec.CorruptedFrameException;

/**
 * 字节流不符合协议 v2（magic / version / 长度字段 / 信封结构不合法）。
 *
 * <p>继承 Netty 的 {@link CorruptedFrameException}（一种 {@code DecoderException}）：在 pipeline 里由
 * 解码器抛出时，handler 统一按"解码失败、关闭连接"处理；纯函数编解码器（AttachmentsCodec / EnvelopeCodec）
 * 抛出时，调用方按需映射成 BAD_REQUEST。随机字节输入只会得到这一类异常（jqwik 模糊测试的断言）。
 */
public class ProtocolException extends CorruptedFrameException {

	private static final long serialVersionUID = 1L;

	public ProtocolException(String message) {
		super(message);
	}
}
