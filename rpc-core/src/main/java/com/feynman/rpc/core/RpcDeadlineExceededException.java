package com.feynman.rpc.core;

import com.feynman.rpc.core.protocol.Status;

/**
 * 调用超过了截止时间：客户端在 deadline 前没等到响应，或服务端发现请求在开始执行前就已过期（DEADLINE_EXCEEDED，
 * 此时业务方法<b>没有</b>被执行）。
 *
 * <p>不进入 Cluster 重试：同一次调用的 deadline 是共享的预算，过期之后再换节点重试只会把一个注定失败的请求
 * 再发一遍、在下游再排一次队。
 */
public class RpcDeadlineExceededException extends RpcException {

	private static final long serialVersionUID = 1L;

	public RpcDeadlineExceededException(String message) {
		super(message);
	}

	public RpcDeadlineExceededException(String message, Throwable cause) {
		super(message, cause);
	}

	@Override
	public Status status() {
		return Status.DEADLINE_EXCEEDED;
	}
}
