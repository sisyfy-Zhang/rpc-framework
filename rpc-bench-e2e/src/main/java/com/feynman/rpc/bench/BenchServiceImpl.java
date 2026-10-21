package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * 原样返回入参，不做任何业务计算；{@link #environment()} 返回构造时采集好的服务端环境，{@link #stats()} 返回此刻的 GC 统计
 * （与系统调用 / 上下文切换，以及 rpc 服务端的出站背压计数）。
 */
public final class BenchServiceImpl implements BenchService {

	private final Map<String, String> environment;
	/** 附加进 stats() 的服务端计数（rpc 服务端：出站背压，见 {@link BackpressureStats}）；没有时返回空表。 */
	private final Supplier<Map<String, String>> extraStats;
	private final Supplier<Map<String, String>> counters;
	private final long workNanos;

	public BenchServiceImpl(Map<String, String> environment) {
		this(environment, Map::of);
	}

	public BenchServiceImpl(Map<String, String> environment, Supplier<Map<String, String>> extraStats) {
		this(environment, extraStats, Map::of, 0);
	}

	/**
	 * @param counters  {@link #counters()} 的内容（rpc bench server：{@link ServerCounters}）
	 * @param workNanos 每次 echo / echoOrder 返回前用 {@link LockSupport#parkNanos} 等待的时长（{@code server --work}，模拟 IO 等待，
	 *                  不占 CPU、占着业务线程）；0 = 原样立即返回
	 */
	BenchServiceImpl(Map<String, String> environment, Supplier<Map<String, String>> extraStats, Supplier<Map<String, String>> counters,
			long workNanos) {
		this.environment = new LinkedHashMap<>(environment);
		this.extraStats = extraStats;
		this.counters = counters;
		this.workNanos = workNanos;
	}

	/** 本次调用要"干活"的时长：parkNanos 可能提前返回（虚假唤醒），循环到截止时刻为止。 */
	private void work() {
		if (workNanos <= 0) {
			return;
		}
		long deadline = System.nanoTime() + workNanos;
		long remaining;
		while ((remaining = deadline - System.nanoTime()) > 0) {
			LockSupport.parkNanos(remaining);
		}
	}

	@Override
	public byte[] echo(byte[] payload) {
		work();
		return payload;
	}

	@Override
	public Order echoOrder(Order order) {
		work();
		return order;
	}

	@Override
	public Map<String, String> counters() {
		return counters.get();
	}

	@Override
	public Map<String, String> environment() {
		return new LinkedHashMap<>(environment);
	}

	@Override
	public Map<String, String> stats() {
		Map<String, String> stats = ServerProbes.stats();
		stats.putAll(extraStats.get());
		return stats;
	}
}
