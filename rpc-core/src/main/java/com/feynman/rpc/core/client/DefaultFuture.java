package com.feynman.rpc.core.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.feynman.rpc.core.protocol.RpcMessage;

/**
 * 以 requestId 关联一次请求和它最终的响应，底层是一个 {@link CompletableFuture}。
 * 超时靠 {@link CompletableFuture#orTimeout}，不需要一个专用线程每隔固定间隔轮询扫描所有
 * 未完成请求——JDK 自带的定时完成机制就能做同样的事。
 *
 * <p>{@link #future()} 是真正对外的异步句柄：声明返回 {@code CompletableFuture<T>} 的方法，
 * InvokeProxy 直接把它返回；普通同步方法签名则在它上面阻塞等待——同步调用只是异步路径上的
 * 语法糖，不是另一套独立实现。
 */
public class DefaultFuture {

	private static final ConcurrentHashMap<Long, DefaultFuture> PENDING = new ConcurrentHashMap<>();
	private static final AtomicLong REQUEST_ID = new AtomicLong(1);

	private final long requestId;
	private final CompletableFuture<RpcMessage> future = new CompletableFuture<>();

	public static long nextRequestId() {
		return REQUEST_ID.getAndIncrement();
	}

	public DefaultFuture(long requestId, long timeout, TimeUnit unit) {
		this.requestId = requestId;
		PENDING.put(requestId, this);
		future.orTimeout(timeout, unit)
				.whenComplete((response, error) -> PENDING.remove(requestId));
	}

	public long getRequestId() {
		return requestId;
	}

	public CompletableFuture<RpcMessage> future() {
		return future;
	}

	// 线程安全已处理：Netty 事件循环线程调用，ConcurrentHashMap.remove + CompletableFuture.complete 都是线程安全的
	public static void receive(RpcMessage resp) {
		DefaultFuture df = PENDING.remove(resp.getRequestId());
		if (df != null) {
			df.future.complete(resp);
		}
	}

	public static boolean isTimeout(Throwable error) {
		return error instanceof TimeoutException
				|| (error != null && error.getCause() instanceof TimeoutException);
	}
}
