package com.feynman.rpc.metrics.micrometer;

import java.util.List;

import com.feynman.rpc.core.RpcServer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * 把一个 {@link RpcServer} 的读数绑定成 Gauge：
 * <ul>
 *   <li>{@value #INFLIGHT}：已进入服务端（排队或执行中，含业务返回了未完成 future 的）、尚未回包的请求数
 *       （{@link RpcServer#inFlight()}）；</li>
 *   <li>{@value #CONNECTIONS}：已接受、尚未关闭的入站连接数（{@link RpcServer#activeConnections()}）。</li>
 * </ul>
 * 未启动与已关闭时读数为 0。弱引用、{@link #close()} 与多实例 tag 的约定同 {@link RpcClientMetrics}。
 */
public final class RpcServerMetrics implements AutoCloseable {

	public static final String INFLIGHT = "rpc.server.inflight";
	public static final String CONNECTIONS = "rpc.server.connections";

	private final MeterRegistry registry;
	private final List<Gauge> gauges;

	private RpcServerMetrics(MeterRegistry registry, List<Gauge> gauges) {
		this.registry = registry;
		this.gauges = gauges;
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
		return new RpcServerMetrics(registry, List.of(inflight, connections));
	}

	/** 绑定的 Gauge（测试与诊断用）。 */
	public List<Gauge> gauges() {
		return gauges;
	}

	/** 从 registry 移除这些 Gauge。可重复调用。 */
	@Override
	public void close() {
		for (Gauge gauge : gauges) {
			registry.remove(gauge);
		}
	}
}
