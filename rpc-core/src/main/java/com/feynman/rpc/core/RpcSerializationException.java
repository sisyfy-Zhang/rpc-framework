package com.feynman.rpc.core;

import com.feynman.rpc.core.protocol.Status;

/**
 * 参数或返回值序列化 / 反序列化失败（含服务端返回 SERIALIZATION_ERROR）。确定性失败，不重试——
 * 换个节点同样的对象依然编不出来。
 */
public class RpcSerializationException extends RpcException {

	private static final long serialVersionUID = 1L;

	public RpcSerializationException(String message) {
		super(message);
	}

	public RpcSerializationException(String message, Throwable cause) {
		super(message, cause);
	}

	@Override
	public Status status() {
		return Status.SERIALIZATION_ERROR;
	}
}
