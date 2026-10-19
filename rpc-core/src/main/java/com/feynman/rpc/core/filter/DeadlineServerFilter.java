package com.feynman.rpc.core.filter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.RpcDeadlineExceededException;
import com.feynman.rpc.core.protocol.AttachmentKeys;

/**
 * 内置服务端 Filter：请求开始执行前检查是否已过期，过期直接返回 DEADLINE_EXCEEDED，<b>不调用业务方法</b>。
 *
 * <p>判定：{@code 现在 - 到达时刻 >= rpc.timeout}。到达时刻是 IO 线程解码该帧的单调时钟读数，所以在业务线程池里
 * 排队的时间也算在内——这正是要防的情况：调用方早已放弃（超时返回）的请求，不该在队列里熬到之后再占一个线程去执行。
 * 没有 {@code rpc.timeout}（老客户端、未设超时）或值非法时放行。
 *
 * <p>位置：服务端链从外到内 Tracing 恢复(-6000，预留) → Metrics(-5000，rpc-metrics-micrometer 已实现) → Deadline(-4000)
 * → 并发限制(-3000，规划) → 用户 Filter(0) → 反射调用。它外面只有观测类 Filter（它们要看到被拒绝的请求，指标的错误码分布
 * 才有 DEADLINE_EXCEEDED；Tracing 在 Metrics 之外，以便将来记录指标时已有 trace 上下文）；在其余 Filter 中排第一，
 * 被拒绝的请求不消耗后面任何 Filter 的成本，参数也不会被反序列化（服务端参数是惰性的）。
 */
public final class DeadlineServerFilter implements ServerFilter {

	public static final int ORDER = -4000;

	private static final Logger log = LoggerFactory.getLogger(DeadlineServerFilter.class);

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		String timeout = invocation.getAttachment(AttachmentKeys.TIMEOUT);
		if (timeout != null) {
			long timeoutMillis;
			try {
				timeoutMillis = Long.parseLong(timeout);
			} catch (NumberFormatException e) {
				return next.invoke(invocation);
			}
			long waitedNanos = System.nanoTime() - invocation.startNanos();
			if (waitedNanos >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
				long waitedMillis = TimeUnit.NANOSECONDS.toMillis(waitedNanos);
				log.debug("Rejecting expired request {}: waited {}ms, rpc.timeout={}ms", invocation, waitedMillis, timeoutMillis);
				return CompletableFuture.completedFuture(Result.exception(new RpcDeadlineExceededException(
						"deadline exceeded before execution: waited " + waitedMillis + "ms on server, rpc.timeout=" + timeoutMillis + "ms")));
			}
		}
		return next.invoke(invocation);
	}

	@Override
	public int order() {
		return ORDER;
	}
}
