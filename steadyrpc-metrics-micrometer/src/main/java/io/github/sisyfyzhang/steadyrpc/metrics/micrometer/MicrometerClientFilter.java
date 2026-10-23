package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import java.util.concurrent.CompletableFuture;

import io.github.sisyfyzhang.steadyrpc.core.filter.ClientFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.DeadlineClientFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invoker;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 客户端调用指标：每次调用记一次 Timer {@value #METER_NAME}，tag {@code service}（接口全限定名）、{@code method}（方法名，
 * 重载合并）、{@code status}（有界取值，见 docs/observability.md）。
 *
 * <p><b>异步计时</b>：从进入本 Filter 到下游返回的 future 完成为止；在 future 上挂回调记录，原样返回同一个 future，
 * 调用线程不等待、结果不变。一次调用（含 Cluster 的全部重试）记一个样本。
 *
 * <p><b>位置 {@value #ORDER}</b>：比 {@link DeadlineClientFilter#ORDER}（-2000）与全部用户 Filter 都更靠外。理由：计时覆盖整条
 * 客户端链（含其它 Filter 自身的开销），任何内置 / 用户 Filter 的拒绝或短路也都被计入；排在它们之内就会漏掉这些结果。
 * 再外层只预留给 Tracing（-6000）：将来需要 exemplar 时，记录指标的那一刻已有 trace 上下文。
 */
public final class MicrometerClientFilter implements ClientFilter {

	public static final String METER_NAME = "rpc.client.calls";
	public static final int ORDER = -5000;

	private final CallTimers timers;

	public MicrometerClientFilter(MeterRegistry registry) {
		this.timers = new CallTimers(registry, METER_NAME,
				"RPC calls issued by this client, timed from the outermost client filter until the call completes",
				CallTimers.CLIENT_MIN_EXPECTED);
	}

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		long start = timers.now();
		CompletableFuture<Result> future;
		try {
			future = next.invoke(invocation);
		} catch (RuntimeException | Error e) {
			timers.record(invocation.serviceKey().interfaceName(), invocation.method(), CallStatus.client(null, e), start);
			return CompletableFuture.failedFuture(e);
		}
		future.whenComplete((result, error) -> timers.record(invocation.serviceKey().interfaceName(), invocation.method(),
				CallStatus.client(result, error), start));
		return future;
	}

	@Override
	public int order() {
		return ORDER;
	}
}
