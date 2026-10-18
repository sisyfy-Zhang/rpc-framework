package com.feynman.rpc.core;

import com.feynman.rpc.core.protocol.Status;

/**
 * 服务端拒绝了请求（SERVER_OVERLOADED）：业务执行器的队列已满 / 并发已达上限，或服务端正在优雅停机。
 * 两种情况下业务方法都<b>没有</b>执行。
 *
 * <p>为什么它和传输失败走同一个通道（让 Cluster 的 future 失败）而不是装进 Result：被拒绝的请求一定没有执行，
 * 换一个节点重试是安全的，这一点与"连接被拒绝"完全一样；而"该节点此刻不可用"正是 failover 排除节点的依据。
 * 是否真的重试仍由引用的 {@code retries} 决定（默认 0 直接抛出），deadline 耗尽后也不再重试（D-009）。
 *
 * <p>服务端停机时 {@link #getMessage()} 含 {@code "server shutting down"}，过载时含 {@code "server overloaded"}，
 * 便于日志与告警区分"在发布"与"扛不住"。
 */
public class RpcOverloadedException extends RpcException {

	private static final long serialVersionUID = 1L;

	public RpcOverloadedException(String message) {
		super(message);
	}

	public RpcOverloadedException(String message, Throwable cause) {
		super(message, cause);
	}

	@Override
	public Status status() {
		return Status.SERVER_OVERLOADED;
	}
}
