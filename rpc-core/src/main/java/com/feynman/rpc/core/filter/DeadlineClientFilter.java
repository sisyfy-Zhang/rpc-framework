package com.feynman.rpc.core.filter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.protocol.AttachmentKeys;

/**
 * 内置客户端 Filter：把本次调用的截止时间写进 attachments，传给服务端。
 * <ul>
 *   <li>{@code rpc.timeout}：此刻的剩余毫秒（向上取整，至少 1——还没过期的调用不该因为取整被服务端判死）。
 *       服务端以"到达时刻 + 该值"判定，不依赖两台机器的时钟一致。</li>
 *   <li>{@code rpc.deadline}：绝对截止时间（epoch 毫秒），只用于日志与观测。</li>
 * </ul>
 * 截止时间本身随 Invocation 创建而确定（发起时刻 + 引用超时），Cluster 的重试共享这一份预算；
 * 每次实际发送时 RpcClient 会用那一刻的剩余时间刷新 {@code rpc.timeout}。
 *
 * <p>位置：客户端内置顺序 Tracing(-3000) → Deadline(-2000) → Metrics(-1000) → 用户 Filter(0) → Cluster。
 */
public final class DeadlineClientFilter implements ClientFilter {

	public static final int ORDER = -2000;

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		if (!invocation.timeout().isZero()) {
			long remainingMillis = remainingMillis(invocation.remainingNanos());
			invocation.setAttachment(AttachmentKeys.TIMEOUT, Long.toString(remainingMillis));
			invocation.setAttachment(AttachmentKeys.DEADLINE, Long.toString(System.currentTimeMillis() + remainingMillis));
		}
		return next.invoke(invocation);
	}

	/** 剩余纳秒 → 毫秒，向上取整；已过期为 0。 */
	public static long remainingMillis(long remainingNanos) {
		if (remainingNanos <= 0) {
			return 0;
		}
		return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos + 999_999));
	}

	@Override
	public int order() {
		return ORDER;
	}
}
