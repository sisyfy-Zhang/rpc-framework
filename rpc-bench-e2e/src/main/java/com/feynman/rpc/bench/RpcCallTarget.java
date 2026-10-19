package com.feynman.rpc.bench;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.metrics.micrometer.MicrometerClientFilter;
import com.feynman.rpc.metrics.micrometer.RpcClientMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code transport = rpc}：一个 {@link RpcClient} + 一个 {@link BenchService} 代理，跨轮复用（连接预热算在 warmup 里）。
 * 每次调用走同步代理方法；超时 = {@code load.timeout}，不重试（重试会把一次失败藏进更长的延迟里）。
 *
 * <p>开始压之前用 {@code ReferenceOptions.awaitProviders} 等到提供者出现（registered ≠ discovered），再等到提供者
 * 数等于卡片的 {@code target.providers}——拓扑与卡片不符时不压，直接失败。
 */
final class RpcCallTarget implements CallTarget {

	static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(30);

	/** environment() 的超时：与压测超时无关（压测卡可能把 timeout 设到 1ms），只是一次性的元数据调用。 */
	static final Duration ENVIRONMENT_TIMEOUT = Duration.ofSeconds(30);

	private final RpcClient client;
	private final BenchService service;
	private final boolean bytes;
	private final byte[] payload;
	private final Order order;
	private final Map<String, Object> description;
	/** environment() / stats() 用的代理：超时与压测超时无关（见 {@link #ENVIRONMENT_TIMEOUT}），首次用到时创建。 */
	private volatile BenchService metadata;

	private RpcCallTarget(RpcClient client, BenchService service, Profile profile, Map<String, Object> description) {
		this.client = client;
		this.service = service;
		this.bytes = "bytes".equals(profile.target().payloadKind());
		this.payload = bytes ? Payloads.bytes(profile.target().payloadSize()) : null;
		this.order = bytes ? null : Payloads.order();
		this.description = description;
	}

	static RpcCallTarget connect(Profile profile, Endpoint endpoint) {
		return connect(profile, endpoint, null);
	}

	/** @param metrics 非 null 时把 {@link MicrometerClientFilter} 加进客户端链并绑定在途 / 连接数 Gauge（{@code --metrics-port}） */
	static RpcCallTarget connect(Profile profile, Endpoint endpoint, MeterRegistry metrics) {
		RegistryConfig registry = Endpoint.REGISTRY.equals(endpoint.mode())
				? RegistryConfig.zookeeper(endpoint.address())
				: DirectRegistryFactory.config(endpoint.address());
		Duration timeout = profile.load().timeout();
		var builder = RpcClient.builder()
				.registry(registry)
				.serializer(profile.target().serializer())
				.defaultTimeout(timeout);
		if (metrics != null) {
			builder.clientFilter(new MicrometerClientFilter(metrics));
		}
		RpcClient client = builder.build();
		try {
			if (metrics != null) {
				RpcClientMetrics.bind(client, metrics);
			}
			BenchService service = client.reference(BenchService.class,
					ReferenceOptions.defaults().timeout(timeout).retries(0).awaitProviders(DISCOVERY_TIMEOUT));
			List<ServiceInstance> providers = awaitProviderCount(client, profile.target().providers());
			Map<String, Object> description = new LinkedHashMap<>();
			description.put("transport", "rpc");
			description.put("mode", endpoint.mode());
			description.put("address", endpoint.describe());
			description.put("providers", providers.stream().map(ServiceInstance::address).sorted().toList());
			description.put("serializer", client.config().serializer());
			description.put("load_balance", client.config().loadBalance());
			description.put("cluster", client.config().cluster());
			description.put("connections_per_node", client.config().connectionsPerNode());
			description.put("retries", 0);
			description.put("call_style", "sync proxy method");
			description.put("metrics_filter", metrics != null);
			return new RpcCallTarget(client, service, profile, description);
		} catch (RuntimeException e) {
			client.close();
			throw e;
		}
	}

	/** 等到提供者数恰好等于卡片声明的数量（多了少了都不压）。 */
	private static List<ServiceInstance> awaitProviderCount(RpcClient client, int expected) {
		ServiceKey key = ServiceKey.of(BenchService.class);
		long deadline = System.nanoTime() + DISCOVERY_TIMEOUT.toNanos();
		List<ServiceInstance> current = client.providers(key);
		while (current.size() != expected) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("target.providers = " + expected + " but " + current.size()
						+ " provider(s) of " + key + " are visible after " + DISCOVERY_TIMEOUT.toSeconds() + "s: "
						+ current.stream().map(ServiceInstance::address).toList());
			}
			// 只在提供者数与卡片不符时才会走到这里（第一个提供者已由 awaitProviders 等到），少见且不在压测路径上
			try {
				TimeUnit.MILLISECONDS.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while waiting for providers", e);
			}
			current = client.providers(key);
		}
		return current;
	}

	@Override
	public void call() {
		if (bytes) {
			byte[] echoed = service.echo(payload);
			if (echoed == null || echoed.length != payload.length) {
				throw new ErrorClassifier.PayloadMismatchException("echo returned " + (echoed == null ? "null" : echoed.length + " bytes")
						+ ", sent " + payload.length);
			}
		} else {
			Order echoed = service.echoOrder(order);
			if (echoed == null || !order.getOrderId().equals(echoed.getOrderId())) {
				throw new ErrorClassifier.PayloadMismatchException("echoOrder returned " + echoed);
			}
		}
	}

	/** 开压前校验一次完整回显（逐字节 / 全字段）；压测循环里只比长度与 id，开销可忽略。 */
	@Override
	public void verify() {
		if (bytes) {
			byte[] echoed = service.echo(payload);
			if (!java.util.Arrays.equals(payload, echoed)) {
				throw new ErrorClassifier.PayloadMismatchException("echo did not return the payload unchanged");
			}
		} else if (!order.equals(service.echoOrder(order))) {
			throw new ErrorClassifier.PayloadMismatchException("echoOrder did not return the order unchanged");
		}
	}

	@Override
	public Map<String, String> serverEnvironment() {
		return metadata().environment();
	}

	@Override
	public Map<String, String> serverStats() {
		return metadata().stats();
	}

	private BenchService metadata() {
		BenchService m = metadata;
		if (m == null) {
			m = client.reference(BenchService.class, ReferenceOptions.defaults().timeout(ENVIRONMENT_TIMEOUT).retries(0));
			metadata = m;
		}
		return m;
	}

	@Override
	public Map<String, Object> describe() {
		return description;
	}

	@Override
	public void close() {
		client.close();
	}
}
