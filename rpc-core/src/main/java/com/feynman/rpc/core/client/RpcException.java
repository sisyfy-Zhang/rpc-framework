package com.feynman.rpc.core.client;

/** 调用失败/超时的非受检异常，由同步调用路径 {@link TCPClient#send} 抛出。 */
public class RpcException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RpcException(String message) {
		super(message);
	}

	public RpcException(String message, Throwable cause) {
		super(message, cause);
	}
}
