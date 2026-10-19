package com.feynman.rpc.metrics.micrometer;

import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.filter.DeadlineServerFilter;
import com.feynman.rpc.core.filter.Invocation;
import com.feynman.rpc.core.filter.Invoker;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.filter.ServerFilter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 服务端调用指标：每个进入服务端 Filter 链的请求记一次 Timer {@value #METER_NAME}，tag 同客户端
 * （{@code service} 接口全限定名、{@code method}、{@code status}）。
 *
 * <p><b>异步计时</b>：从进入本 Filter（请求已在业务执行器上拿到线程）到链返回的 future 完成为止。业务方法返回未完成的
 * {@code CompletableFuture} 时，计时延续到它完成，业务线程不被占用。在执行器里排队的时间不在其内。
 *
 * <p><b>位置 {@value #ORDER}</b>：比 {@link DeadlineServerFilter#ORDER}（-4000）与全部用户 Filter 都更靠外。理由：DeadlineServerFilter
 * 拒绝过期请求（DEADLINE_EXCEEDED）、以后的并发限制 Filter 拒绝（SERVER_OVERLOADED）都发生在它之内，指标才看得到这些拒绝；
 * 排在它们之内，被拒绝的请求在错误码分布里就不存在了。再外层只预留给 Tracing 恢复（-6000）。
 *
 * <p>直方图下界 1µs（客户端是 100µs）：轻量方法的服务端耗时只有几微秒，见 CallTimers。
 *
 * <p>看不到的部分（不经过 Filter 链）：IO 线程上的执行器满载 / 停机拒绝、BAD_REQUEST、SERVICE_NOT_FOUND；链完成之后的回包改写
 * （结果序列化失败 → SERIALIZATION_ERROR、回程 attachments 非法 → SERVER_ERROR）。见 docs/observability.md。
 */
public final class MicrometerServerFilter implements ServerFilter {

	public static final String METER_NAME = "rpc.server.calls";
	public static final int ORDER = -5000;

	private final CallTimers timers;

	public MicrometerServerFilter(MeterRegistry registry) {
		this.timers = new CallTimers(registry, METER_NAME,
				"RPC requests handled by this server, timed from the outermost server filter until the chain completes",
				CallTimers.SERVER_MIN_EXPECTED);
	}

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		long start = timers.now();
		CompletableFuture<Result> future;
		try {
			future = next.invoke(invocation);
		} catch (RuntimeException | Error e) {
			timers.record(invocation.serviceKey().interfaceName(), invocation.method(), CallStatus.server(invocation, null, e), start);
			return CompletableFuture.failedFuture(e);
		}
		future.whenComplete((result, error) -> timers.record(invocation.serviceKey().interfaceName(), invocation.method(),
				CallStatus.server(invocation, result, error), start));
		return future;
	}

	@Override
	public int order() {
		return ORDER;
	}
}
