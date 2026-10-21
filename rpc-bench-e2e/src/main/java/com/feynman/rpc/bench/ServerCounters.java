package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;

import org.HdrHistogram.Histogram;

import com.feynman.rpc.core.RpcServer;

/**
 * rpc bench server 的轻量累计计数（{@code BenchService.counters()}，也并进 {@code stats()}）：
 * <ul>
 *   <li>{@value #EXECUTOR_REJECTIONS} / {@value #SHUTDOWN_REJECTIONS}：{@code RpcServer.executorRejections()} / {@code shutdownRejections()}；</li>
 *   <li>{@value #OUTBOUND_REJECTIONS}：{@code RpcServer.outboundOverloadRejections()}；</li>
 *   <li>{@value #INFLIGHT} / {@value #QUEUED}：此刻的在途与排队数（瞬时值，不做差）；</li>
 *   <li>{@value #QUEUE_WAIT}：排队等待的累计直方图（{@link QueueWaitFilter#encoded()}）。</li>
 * </ul>
 * 压测端对两个时刻做差：统计窗口起止（{@link #windowDelta} → {@code rounds[].server_executor}）与时间线每秒（{@link FaultTimeline}）。
 */
final class ServerCounters {

	static final String EXECUTOR_REJECTIONS = "executor.rejections";
	static final String SHUTDOWN_REJECTIONS = "executor.shutdown_rejections";
	static final String OUTBOUND_REJECTIONS = "executor.outbound_overload_rejections";
	static final String INFLIGHT = "executor.inflight";
	static final String QUEUED = "executor.queued";
	static final String QUEUE_WAIT = "executor.queue_wait_histogram";
	static final String SOURCE = "server counters (RpcServer.executorRejections / shutdownRejections / queuedBusinessTasks; "
			+ "queue wait = bench QueueWaitFilter: arrival (IO-thread decode) -> business thread starts the filter chain)";

	private ServerCounters() {
	}

	static Map<String, String> of(RpcServer server, QueueWaitFilter queueWait) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put(EXECUTOR_REJECTIONS, String.valueOf(server.executorRejections()));
		out.put(SHUTDOWN_REJECTIONS, String.valueOf(server.shutdownRejections()));
		out.put(OUTBOUND_REJECTIONS, String.valueOf(server.outboundOverloadRejections()));
		out.put(INFLIGHT, String.valueOf(server.inFlight()));
		out.put(QUEUED, String.valueOf(server.queuedBusinessTasks()));
		out.put(QUEUE_WAIT, queueWait.encoded());
		return out;
	}

	static Long number(Map<String, String> stats, String key) {
		if (stats == null) {
			return null;
		}
		String value = stats.get(key);
		if (value == null) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** after − before；任一取不到为 null。 */
	static Long diff(Map<String, String> before, Map<String, String> after, String key) {
		Long b = number(before, key);
		Long a = number(after, key);
		return a == null || b == null ? null : a - b;
	}

	/** 两次累计直方图之差；取不到 / 解不开（例如服务端重启过）为 null。 */
	static Histogram queueWait(Map<String, String> before, Map<String, String> after) {
		if (after == null || after.get(QUEUE_WAIT) == null) {
			return null;
		}
		try {
			return QueueWaitFilter.delta(before == null ? null : before.get(QUEUE_WAIT), after.get(QUEUE_WAIT));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 排队等待分位数（毫秒，3 位小数）：{count, p50, p99, max}。 */
	static Map<String, Object> quantiles(Histogram h) {
		Map<String, Object> q = new LinkedHashMap<>();
		long count = h == null ? 0 : h.getTotalCount();
		q.put("count", count);
		q.put("p50", count == 0 ? null : ms(h.getValueAtPercentile(50)));
		q.put("p99", count == 0 ? null : ms(h.getValueAtPercentile(99)));
		q.put("max", count == 0 ? null : ms(h.getMaxValue()));
		return q;
	}

	private static double ms(double nanos) {
		return RoundRecorder.round3(nanos / 1e6);
	}

	/**
	 * 统计窗口内的服务端执行器读数（{@code rounds[].server_executor}）：拒绝数差值与排队等待分位数。统计取不到为 unknown，
	 * 取到了但没有这些键（对比对象、HTTP 端点、旧服务端）为 n/a。
	 */
	static Object windowDelta(Map<String, String> before, Map<String, String> after) {
		if (before == null || after == null) {
			return EnvironmentProbe.UNKNOWN;
		}
		if (number(after, EXECUTOR_REJECTIONS) == null) {
			return EnvironmentProbe.NA;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", SOURCE);
		out.put("executor_rejections", diff(before, after, EXECUTOR_REJECTIONS));
		out.put("shutdown_rejections", diff(before, after, SHUTDOWN_REJECTIONS));
		out.put("queue_wait_ms", quantiles(queueWait(before, after)));
		return out;
	}
}
