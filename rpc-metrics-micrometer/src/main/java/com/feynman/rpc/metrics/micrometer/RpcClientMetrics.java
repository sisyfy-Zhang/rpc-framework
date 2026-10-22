package com.feynman.rpc.metrics.micrometer;

import java.util.List;

import com.feynman.rpc.core.RpcClient;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * 把一个 {@link RpcClient} 的读数绑定成 Gauge：
 * <ul>
 *   <li>{@value #INFLIGHT}：经代理发起、尚未完成的调用数（{@link RpcClient#inFlightCalls()}，含重试中的）；</li>
 *   <li>{@value #CONNECTIONS}：到全部提供者已激活的连接总数（{@link RpcClient#activeConnections()}，含排空中的）；</li>
 *   <li>{@value #SEND_BUFFER_REJECTIONS}（FunctionCounter）：连接待写字节超过出站硬上限、没有写出就失败的累计请求数
 *       （{@link RpcClient#sendBufferRejections()}）；</li>
 *   <li>{@value #UNWRITABLE_SWITCHES}（FunctionCounter）：取连接时跳过不可写连接、改用另一条的累计次数
 *       （{@link RpcClient#unwritableConnectionSwitches()}）；</li>
 *   <li>{@value #OUTLIER_EJECTIONS} / {@value #OUTLIER_RESTORES}（FunctionCounter）：离群摘除的累计摘除 / 放回次数
 *       （{@link RpcClient#outlierEjections()} / {@link RpcClient#outlierRestores()}）；</li>
 *   <li>{@value #OUTLIER_EJECTED}：当前处于离群摘除期的提供者地址数（{@link RpcClient#ejectedNodes()} 的大小）。</li>
 * </ul>
 * Gauge 与 FunctionCounter 以<b>弱引用</b>持有客户端（Micrometer 缺省）：registry 不会让一个已经不用的客户端无法回收，被回收后读数为 NaN。
 * close 之后读数为 0。{@link #close()} 把这些 Gauge 与 FunctionCounter 从 registry 移除（同一个 registry 要换绑另一个客户端时先 close）。
 *
 * <p>同一个 registry 绑定多个客户端时，用 {@link #bind(RpcClient, MeterRegistry, Iterable)} 给每个加区分 tag（如
 * {@code client=orders}）；同名同 tag 的 Gauge 在 Micrometer 里只注册第一次，后绑的会被静默忽略。
 */
public final class RpcClientMetrics implements AutoCloseable {

	public static final String INFLIGHT = "rpc.client.inflight";
	public static final String CONNECTIONS = "rpc.client.connections";
	public static final String SEND_BUFFER_REJECTIONS = "rpc.client.send.buffer.rejections";
	public static final String UNWRITABLE_SWITCHES = "rpc.client.unwritable.switches";
	public static final String OUTLIER_EJECTIONS = "rpc.client.outlier.ejections";
	public static final String OUTLIER_RESTORES = "rpc.client.outlier.restores";
	public static final String OUTLIER_EJECTED = "rpc.client.outlier.ejected";

	private final MeterRegistry registry;
	private final List<Gauge> gauges;
	private final List<FunctionCounter> counters;

	private RpcClientMetrics(MeterRegistry registry, List<Gauge> gauges, List<FunctionCounter> counters) {
		this.registry = registry;
		this.gauges = gauges;
		this.counters = counters;
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
		FunctionCounter rejections = FunctionCounter.builder(SEND_BUFFER_REJECTIONS, client, RpcClient::sendBufferRejections)
				.description("Requests failed without being sent because their connection's outbound buffer exceeded the hard limit")
				.tags(tags)
				.register(registry);
		FunctionCounter switches = FunctionCounter.builder(UNWRITABLE_SWITCHES, client, RpcClient::unwritableConnectionSwitches)
				.description("Times a call skipped an unwritable connection and used another connection to the same provider")
				.tags(tags)
				.register(registry);
		FunctionCounter ejections = FunctionCounter.builder(OUTLIER_EJECTIONS, client, RpcClient::outlierEjections)
				.description("Times a provider was ejected from load balancing by outlier detection")
				.tags(tags)
				.register(registry);
		FunctionCounter restores = FunctionCounter.builder(OUTLIER_RESTORES, client, RpcClient::outlierRestores)
				.description("Times an ejected provider returned to load balancing after its ejection expired")
				.tags(tags)
				.register(registry);
		Gauge ejected = Gauge.builder(OUTLIER_EJECTED, client, c -> c.ejectedNodes().size())
				.description("Providers currently ejected by outlier detection")
				.tags(tags)
				.register(registry);
		return new RpcClientMetrics(registry, List.of(inflight, connections, ejected), List.of(rejections, switches, ejections, restores));
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
