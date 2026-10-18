package com.feynman.rpc.core.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.feynman.rpc.core.protocol.RpcMessage;

/**
 * 以 requestId 关联一次请求和它最终的响应。
 *
 * <p><b>每个请求只有这一个定时器</b>：{@link #register} 在请求开始时（等连接之前）就登记，超时取本次调用 deadline 的
 * 剩余时间，覆盖"等连接 + 写出 + 等响应"的全过程；到期后 future 以 {@link TimeoutException} 完成并从表中移除。
 * 旧实现在发送路径外层还有一层 orTimeout，同一个请求挂两个定时器、两种超时语义。超时靠
 * {@link CompletableFuture#orTimeout}（JDK 共享的延迟调度线程），不需要专用线程轮询扫描。
 *
 * <p>响应消息的 body 是引用计数的：交给了等待者就由等待者释放；没有等待者（已超时、已失败）时在这里释放，
 * 迟到的响应不会泄漏。
 *
 * <p>归单个客户端实例所有：requestId 只需在一个客户端的连接上唯一，客户端关闭时可以一次性让所有在途请求失败；
 * "在途表为空"也因此能成为每个测试结束时可断言的泄漏指标。
 */
final class PendingRequests {

	private final ConcurrentHashMap<Long, CompletableFuture<RpcMessage>> pending = new ConcurrentHashMap<>();
	private final AtomicLong nextId = new AtomicLong(1);

	long nextRequestId() {
		return nextId.getAndIncrement();
	}

	/** 登记一个等待中的请求；超时或完成后自动从表中移除。 */
	CompletableFuture<RpcMessage> register(long requestId, long timeout, TimeUnit unit) {
		CompletableFuture<RpcMessage> future = new CompletableFuture<>();
		pending.put(requestId, future);
		future.orTimeout(timeout, unit).whenComplete((response, error) -> pending.remove(requestId, future));
		return future;
	}

	/** 请求是否仍在等待（未超时、未失败、未完成）。 */
	boolean isWaiting(long requestId) {
		CompletableFuture<RpcMessage> future = pending.get(requestId);
		return future != null && !future.isDone();
	}

	/** Netty IO 线程调用：交给等待者；没有等待者时释放消息。 */
	void complete(RpcMessage response) {
		CompletableFuture<RpcMessage> future = pending.remove(response.requestId());
		if (future == null || !future.complete(response)) {
			response.release();
		}
	}

	void fail(long requestId, Throwable cause) {
		CompletableFuture<RpcMessage> future = pending.remove(requestId);
		if (future != null) {
			future.completeExceptionally(cause);
		}
	}

	void failAll(Throwable cause) {
		for (Long id : pending.keySet()) {
			fail(id, cause);
		}
	}

	int size() {
		return pending.size();
	}

	static boolean isTimeout(Throwable error) {
		return error instanceof TimeoutException
				|| (error != null && error.getCause() instanceof TimeoutException);
	}
}
