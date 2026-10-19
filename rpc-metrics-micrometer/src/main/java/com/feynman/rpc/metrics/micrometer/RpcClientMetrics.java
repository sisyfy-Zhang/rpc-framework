package com.feynman.rpc.metrics.micrometer;

import java.util.List;

import com.feynman.rpc.core.RpcClient;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * 把一个 {@link RpcClient} 的读数绑定成 Gauge：
 * <ul>
 *   <li>{@value #INFLIGHT}：经代理发起、尚未完成的调用数（{@link RpcClient#inFlightCalls()}，含重试中的）；</li>
 *   <li>{@value #CONNECTIONS}：到全部提供者已激活的连接总数（{@link RpcClient#activeConnections()}，含排空中的）。</li>
 * </ul>
 * Gauge 以<b>弱引用</b>持有客户端（Micrometer 缺省）：registry 不会让一个已经不用的客户端无法回收，被回收后读数为 NaN。
 * close 之后读数为 0。{@link #close()} 把这两个 Gauge 从 registry 移除（同一个 registry 要换绑另一个客户端时先 close）。
 *
 * <p>同一个 registry 绑定多个客户端时，用 {@link #bind(RpcClient, MeterRegistry, Iterable)} 给每个加区分 tag（如
 * {@code client=orders}）；同名同 tag 的 Gauge 在 Micrometer 里只注册第一次，后绑的会被静默忽略。
 */
public final class RpcClientMetrics implements AutoCloseable {

	public static final String INFLIGHT = "rpc.client.inflight";
	public static final String CONNECTIONS = "rpc.client.connections";

	private final MeterRegistry registry;
	private final List<Gauge> gauges;

	private RpcClientMetrics(MeterRegistry registry, List<Gauge> gauges) {
		this.registry = registry;
		this.gauges = gauges;
	}

	public static RpcClientMetrics bind(RpcClient client, MeterRegistry registry) {
		return bind(client, registry, Tags.empty());
	}

	public static RpcClientMetrics bind(RpcClient client, MeterRegistry registry, Iterable<Tag> tags) {
		if (client == null || registry == null) {
			throw new IllegalArgumentException("client and registry must not be null");
		}
		Gauge inflight = Gauge.builder(INFLIGHT, client, RpcClient::inFlightCalls)
				.description("Calls issued through this client's proxies that have not completed yet (including retries)")
				.tags(tags)
				.register(registry);
		Gauge connections = Gauge.builder(CONNECTIONS, client, RpcClient::activeConnections)
				.description("Active connections from this client to all providers (including draining ones)")
				.tags(tags)
				.register(registry);
		return new RpcClientMetrics(registry, List.of(inflight, connections));
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
