package com.feynman.rpc.metrics.micrometer;

import java.util.List;

import com.feynman.rpc.core.RpcServer;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * 把一个 {@link RpcServer} 的读数绑定成 Gauge：
 * <ul>
 *   <li>{@value #INFLIGHT}：已进入服务端（排队或执行中，含业务返回了未完成 future 的）、尚未回包的请求数
 *       （{@link RpcServer#inFlight()}）；</li>
 *   <li>{@value #CONNECTIONS}：已接受、尚未关闭的入站连接数（{@link RpcServer#activeConnections()}）；</li>
 *   <li>{@value #AUTO_READ_PAUSES}（FunctionCounter）：出站背压因连接不可写而暂停读的累计次数（{@link RpcServer#autoReadPauses()}）；</li>
 *   <li>{@value #OUTBOUND_OVERLOAD_REJECTIONS}（FunctionCounter）：连接待写字节超过出站硬上限而直接回 SERVER_OVERLOADED 的累计请求数
 *       （{@link RpcServer#outboundOverloadRejections()}）。</li>
 * </ul>
 * 未启动与已关闭时读数为 0。弱引用、{@link #close()} 与多实例 tag 的约定同 {@link RpcClientMetrics}。
 */
public final class RpcServerMetrics implements AutoCloseable {

	public static final String INFLIGHT = "rpc.server.inflight";
	public static final String CONNECTIONS = "rpc.server.connections";
	public static final String AUTO_READ_PAUSES = "rpc.server.autoread.pauses";
	public static final String OUTBOUND_OVERLOAD_REJECTIONS = "rpc.server.outbound.overload.rejections";

	private final MeterRegistry registry;
	private final List<Gauge> gauges;
	private final List<FunctionCounter> counters;

	private RpcServerMetrics(MeterRegistry registry, List<Gauge> gauges, List<FunctionCounter> counters) {
		this.registry = registry;
		this.gauges = gauges;
		this.counters = counters;
	}

	public static RpcServerMetrics bind(RpcServer server, MeterRegistry registry) {
		return bind(server, registry, Tags.empty());
	}

	public static RpcServerMetrics bind(RpcServer server, MeterRegistry registry, Iterable<Tag> tags) {
		if (server == null || registry == null) {
			throw new IllegalArgumentException("server and registry must not be null");
		}
		Gauge inflight = Gauge.builder(INFLIGHT, server, RpcServer::inFlight)
				.description("Requests accepted by this server (queued or executing, including pending async results) not yet answered")
				.tags(tags)
				.register(registry);
		Gauge connections = Gauge.builder(CONNECTIONS, server, RpcServer::activeConnections)
				.description("Inbound connections currently open on this server")
				.tags(tags)
				.register(registry);
		FunctionCounter pauses = FunctionCounter.builder(AUTO_READ_PAUSES, server, RpcServer::autoReadPauses)
				.description("Times this server paused reading a connection because its outbound buffer went above the high water mark")
				.tags(tags)
				.register(registry);
		FunctionCounter rejections = FunctionCounter.builder(OUTBOUND_OVERLOAD_REJECTIONS, server, RpcServer::outboundOverloadRejections)
				.description("Requests answered SERVER_OVERLOADED because their connection's outbound buffer exceeded the hard limit")
				.tags(tags)
				.register(registry);
		return new RpcServerMetrics(registry, List.of(inflight, connections), List.of(pauses, rejections));
	}

	/** 绑定的 Gauge（测试与诊断用）。 */
	public List<Gauge> gauges() {
		return gauges;
	}

	/** 绑定的 FunctionCounter（测试与诊断用）。 */
	public List<FunctionCounter> counters() {
		return counters;
	}

	/** 从 registry 移除这些 Gauge 与 FunctionCounter。可重复调用。 */
	@Override
	public void close() {
		for (Meter meter : gauges) {
			registry.remove(meter);
		}
		for (Meter meter : counters) {
			registry.remove(meter);
		}
	}
}
