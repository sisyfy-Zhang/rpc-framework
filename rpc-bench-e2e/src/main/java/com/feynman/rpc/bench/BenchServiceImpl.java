package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 原样返回入参，不做任何业务计算；{@link #environment()} 返回构造时采集好的服务端环境，{@link #stats()} 返回此刻的 GC 统计
 * （与系统调用 / 上下文切换，以及 rpc 服务端的出站背压计数）。
 */
public final class BenchServiceImpl implements BenchService {

	private final Map<String, String> environment;
	/** 附加进 stats() 的服务端计数（rpc 服务端：出站背压，见 {@link BackpressureStats}）；没有时返回空表。 */
	private final Supplier<Map<String, String>> extraStats;

	public BenchServiceImpl(Map<String, String> environment) {
		this(environment, Map::of);
	}

	public BenchServiceImpl(Map<String, String> environment, Supplier<Map<String, String>> extraStats) {
		this.environment = new LinkedHashMap<>(environment);
		this.extraStats = extraStats;
	}

	@Override
	public byte[] echo(byte[] payload) {
		return payload;
	}

	@Override
	public Order echoOrder(Order order) {
		return order;
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
