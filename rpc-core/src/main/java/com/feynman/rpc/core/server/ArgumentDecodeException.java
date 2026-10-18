package com.feynman.rpc.core.server;

import com.feynman.rpc.core.RpcSerializationException;

/**
 * 服务端惰性反序列化参数失败（字节不合法、类型不符、基本类型参数收到 null、Protostuff 白名单拒绝等）。
 * 分发器据此回 SERIALIZATION_ERROR，而不是把它当成业务异常。
 */
final class ArgumentDecodeException extends RpcSerializationException {

	private static final long serialVersionUID = 1L;

	ArgumentDecodeException(String message, Throwable cause) {
		super(message, cause);
	}
}
