package com.feynman.rpc.bench;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.config.OutlierDetectionConfig;
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

	/** {@code counters()} 的超时：时间线每秒对每个提供者调一次，挂住的提供者不能拖住采样。 */
	static final Duration COUNTERS_TIMEOUT = Duration.ofMillis(400);

	private final RpcClient client;
	/**
	 * 每个提供者一个只连它的统计通道（有 HTTP 控制端口时经 HTTP，否则是 direct 注册中心、1 个 IO 线程、1 条连接的元数据客户端），按地址排序；
	 * 只在多提供者、卡片声明了阶梯 / 故障，或给了 HTTP 控制端口时创建。
	 * 统计调用必须落在确定的提供者上：经主客户端的负载均衡时，窗口起止两次 stats() 可能打到不同的节点，差值没有意义。
	 */
	private final Map<String, ProviderProbe> probes;
	private final BenchService service;
	private final boolean bytes;
	private final byte[] payload;
	private final Order order;
	private final Map<String, Object> description;
	/** environment() / stats() 用的代理：超时与压测超时无关（见 {@link #ENVIRONMENT_TIMEOUT}），首次用到时创建。 */
	private volatile BenchService metadata;

	private RpcCallTarget(RpcClient client, BenchService service, Profile profile, Map<String, Object> description,
			Map<String, ProviderProbe> probes) {
		this.client = client;
		this.probes = probes;
		this.service = service;
		this.bytes = "bytes".equals(profile.target().payloadKind());
		this.payload = bytes ? Payloads.bytes(profile.target().payloadSize()) : null;
		this.order = bytes ? null : Payloads.order();
		this.description = description;
	}

	static RpcCallTarget connect(Profile profile, Endpoint endpoint) {
		return connect(profile, endpoint, null, null);
	}

	/**
	 * @param metrics   非 null 时把 {@link MicrometerClientFilter} 加进客户端链并绑定在途 / 连接数 Gauge（{@code --metrics-port}）
	 * @param ioThreads {@code --io-threads}：{@code RpcClientConfig.ioThreads}；null = 0 = Netty 缺省（2 × 可用核数）
	 */
	static RpcCallTarget connect(Profile profile, Endpoint endpoint, MeterRegistry metrics, Integer ioThreads) {
		return connect(profile, endpoint, metrics, ioThreads, null);
	}

	/** @param flushConsolidation {@code --flush-consolidation}：{@code RpcClientConfig.flushConsolidation}；null = 框架缺省（开） */
	static RpcCallTarget connect(Profile profile, Endpoint endpoint, MeterRegistry metrics, Integer ioThreads, Boolean flushConsolidation) {
		return connect(profile, endpoint, metrics, ioThreads, flushConsolidation, ClusterChoice.DEFAULTS);
	}

	/** @param choice 负载均衡 / 容错策略 / 重试次数（卡片或命令行，见 {@link ClusterChoice}）；{@link ClusterChoice#DEFAULTS} = 原口径 */
	static RpcCallTarget connect(Profile profile, Endpoint endpoint, MeterRegistry metrics, Integer ioThreads, Boolean flushConsolidation,
			ClusterChoice choice) {
		return connect(profile, endpoint, metrics, ioThreads, flushConsolidation, choice, Map.of());
	}

	/**
	 * @param controlPorts 提供者公布地址 → HTTP 控制端口（{@code server --http-port}）：有的提供者，每秒的 {@code counters()} 与窗口的
	 *                     {@code stats()} 改经 HTTP 取（IO 线程回答，不排在压测请求后面）；没有的照旧经 RPC
	 */
	static RpcCallTarget connect(Profile profile, Endpoint endpoint, MeterRegistry metrics, Integer ioThreads, Boolean flushConsolidation,
			ClusterChoice choice, Map<String, Integer> controlPorts) {
		return connect(profile, endpoint, metrics, ioThreads, flushConsolidation, choice, controlPorts, null);
	}

	/**
	 * @param outlierDetection {@code --outlier-detection on|off}：{@code RpcClientConfig.outlierDetection} 开（框架缺省阈值）/ 关；null = 框架缺省（开）。
	 *                         实际配置进 {@code run.target.outlier_detection}
	 */
	static RpcCallTarget connect(Profile profile, Endpoint endpoint, MeterRegistry metrics, Integer ioThreads, Boolean flushConsolidation,
			ClusterChoice choice, Map<String, Integer> controlPorts, Boolean outlierDetection) {
		RegistryConfig registry = Endpoint.REGISTRY.equals(endpoint.mode())
				? RegistryConfig.zookeeper(endpoint.address())
				: DirectRegistryFactory.config(endpoint.address());
		Duration timeout = profile.load().timeout();
		var builder = RpcClient.builder()
				.registry(registry)
				.serializer(profile.target().serializer())
				.defaultTimeout(timeout)
				.ioThreads(ioThreads == null ? 0 : ioThreads);
		if (profile.target().connections() != null) {
			builder.connectionsPerNode(profile.target().connections());
		}
		if (flushConsolidation != null) {
			builder.flushConsolidation(flushConsolidation);
		}
		if (choice.loadBalance() != null) {
			builder.loadBalance(choice.loadBalance());
		}
		if (choice.cluster() != null) {
			builder.cluster(choice.cluster());
		}
		if (metrics != null) {
			builder.clientFilter(new MicrometerClientFilter(metrics));
		}
		if (outlierDetection != null) {
			builder.outlierDetection(outlierDetection ? OutlierDetectionConfig.defaults() : OutlierDetectionConfig.disabled());
		}
		RpcClient client = builder.build();
		Map<String, ProviderProbe> probes = new java.util.TreeMap<>();
		try {
			if (metrics != null) {
				RpcClientMetrics.bind(client, metrics);
			}
			BenchService service = client.reference(BenchService.class,
					ReferenceOptions.defaults().timeout(timeout).retries(choice.retries()).awaitProviders(DISCOVERY_TIMEOUT));
			List<ServiceInstance> providers = awaitProviderCount(client, profile.target().providers());
			// 单提供者、无时间线时原本经主客户端取统计；给了它的 HTTP 控制端口（sweep.sh / compare.sh 的 --topology）时同样建统计通道，
			// 窗口统计改经 GET /stats（执行器饱和时 RPC 的 stats() 会被拒，该端只能记 unknown）
			boolean controlPortGiven = providers.stream().anyMatch(instance -> controlPorts.containsKey(instance.address()));
			if (providers.size() > 1 || profile.load().timeline() || controlPortGiven) {
				for (ServiceInstance instance : providers) {
					probes.put(instance.address(), ProviderProbe.connect(instance, profile.target().serializer(),
							controlPorts.get(instance.address())));
				}
			}
			Map<String, Object> description = new LinkedHashMap<>();
			description.put("transport", "rpc");
			description.put("mode", endpoint.mode());
			description.put("address", endpoint.describe());
			description.put("providers", providers.stream().map(ServiceInstance::address).sorted().toList());
			description.put("serializer", client.config().serializer());
			description.put("load_balance", client.config().loadBalance());
			description.put("cluster", client.config().cluster());
			description.put("cluster_options_source", new LinkedHashMap<>(new java.util.TreeMap<>(choice.sources())));
			description.put("connections_per_node", client.config().connectionsPerNode());
			description.put("connection_model", client.config().connectionsPerNode() + " TCP connection(s) per provider, requests multiplexed by request id");
			description.put("connections_meaning", "target.connections = RpcClientConfig.connectionsPerNode (default "
					+ com.feynman.rpc.core.config.RpcClientConfig.DEFAULT_CONNECTIONS_PER_NODE + ")");
			description.put("connections_source", profile.target().connections() == null ? "default" : "target.connections");
			description.put("io_transport", "nio");
			description.put("flush_consolidation", client.config().flushConsolidation() ? "on" : "off");
			description.put("server_executor_expected", profile.target().serverExecutor());
			description.put("io_threads", client.config().ioThreads() == 0 ? HarnessOptions.nettyDefaultIoThreadsText() : client.config().ioThreads());
			description.put("retries", choice.retries());
			description.put("outlier_detection", outlierDetectionJson(client.config().outlierDetection(), outlierDetection));
			description.put("call_style", "sync proxy method");
			description.put("metrics_filter", metrics != null);
			if (!probes.isEmpty()) {
				description.put("server_stats_provider", probes.keySet().iterator().next());
				description.put("server_stats_note", "window gc / io / backpressure / server_executor come from this provider only "
						+ "(the first address in sorted order); per-provider counters are in rounds[].timeline");
				Map<String, Object> channels = new LinkedHashMap<>();
				probes.forEach((address, probe) -> channels.put(address, probe.channel()));
				description.put("server_stats_channel", channels);
			}
			return new RpcCallTarget(client, service, profile, description, probes);
		} catch (RuntimeException e) {
			probes.values().forEach(ProviderProbe::close);
			client.close();
			throw e;
		}
	}

	/** {@code run.target.outlier_detection}：开关、实际阈值与来源。 */
	static Map<String, Object> outlierDetectionJson(OutlierDetectionConfig config, Boolean fromCommandLine) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("enabled", config.enabled() ? "on" : "off");
		json.put("min_requests", config.minRequests());
		json.put("failure_ratio", config.failureRatio());
		json.put("consecutive_failures", config.consecutiveFailures());
		json.put("window_seconds", OutlierDetectionConfig.WINDOW_SECONDS);
		json.put("base_ejection", Durations.format(config.baseEjection()));
		json.put("max_ejection", Durations.format(config.maxEjection()));
		json.put("max_ejection_percent", config.maxEjectionPercent());
		json.put("source", fromCommandLine == null ? "default" : "command line (--outlier-detection)");
		return json;
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
		if (!probes.isEmpty()) {
			return probes.values().iterator().next().stats();
		}
		return metadata().stats();
	}

	/** 客户端计数的键：重试与换节点（{@code RpcClient.retryAttempts / retryNodeSwitches}）、按节点的尝试 / 失败 / 超时。 */
	static final String CLIENT_RETRY_ATTEMPTS_KEY = "client.retry_attempts";
	static final String CLIENT_RETRY_NODE_SWITCHES_KEY = "client.retry_node_switches";
	static final String NODE_KEY_PREFIX = "node.";
	/** 离群摘除：累计摘除 / 放回次数（{@code RpcClient.outlierEjections / outlierRestores}）与此刻处于摘除期的地址数（{@code ejectedNodes().size()}）。 */
	static final String CLIENT_OUTLIER_EJECTIONS_KEY = "client.outlier_ejections";
	static final String CLIENT_OUTLIER_RESTORES_KEY = "client.outlier_restores";
	static final String CLIENT_OUTLIER_EJECTED_KEY = "client.outlier_ejected_nodes";

	@Override
	public Map<String, String> clientStats() {
		Map<String, String> out = new java.util.LinkedHashMap<>();
		out.put(BackpressureStats.CLIENT_SEND_BUFFER_REJECTIONS_KEY, String.valueOf(client.sendBufferRejections()));
		out.put(BackpressureStats.CLIENT_UNWRITABLE_SWITCHES_KEY, String.valueOf(client.unwritableConnectionSwitches()));
		out.put(CLIENT_RETRY_ATTEMPTS_KEY, String.valueOf(client.retryAttempts()));
		out.put(CLIENT_RETRY_NODE_SWITCHES_KEY, String.valueOf(client.retryNodeSwitches()));
		out.put(CLIENT_OUTLIER_EJECTIONS_KEY, String.valueOf(client.outlierEjections()));
		out.put(CLIENT_OUTLIER_RESTORES_KEY, String.valueOf(client.outlierRestores()));
		out.put(CLIENT_OUTLIER_EJECTED_KEY, String.valueOf(client.ejectedNodes().size()));
		client.nodeCallStats().forEach((address, s) -> {
			out.put(NODE_KEY_PREFIX + address + ".attempts", String.valueOf(s.attempts()));
			out.put(NODE_KEY_PREFIX + address + ".failures", String.valueOf(s.failures()));
			out.put(NODE_KEY_PREFIX + address + ".timeouts", String.valueOf(s.timeouts()));
		});
		return out;
	}

	/** 并行问每个提供者（各一个虚拟线程，{@link #COUNTERS_TIMEOUT}），失败的记 null。没有元数据客户端时用主客户端问"某一个"提供者。 */
	@Override
	public Map<String, Map<String, String>> providerCounters() {
		Map<String, Map<String, String>> out = new java.util.TreeMap<>();
		if (probes.isEmpty()) {
			return out;
		}
		Map<String, java.util.concurrent.Future<Map<String, String>>> futures = new LinkedHashMap<>();
		try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			probes.forEach((address, probe) -> futures.put(address, executor.submit(probe::counters)));
			futures.forEach((address, future) -> {
				try {
					out.put(address, future.get());
				} catch (java.util.concurrent.ExecutionException e) {
					out.put(address, null);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					out.put(address, null);
				}
			});
		}
		return out;
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
		probes.values().forEach(ProviderProbe::close);
		client.close();
	}

	/** 只连一个提供者的元数据客户端：{@code stats()}（30s 超时）与 {@code counters()}（{@link #COUNTERS_TIMEOUT}）。 */
	/**
	 * 只连一个提供者的统计通道。有 HTTP 控制端口时经 {@code GET /stats} / {@code GET /counters}（JDK HttpClient，IO 线程回答，不经业务执行器——
	 * 执行器排满、甚至拒绝压测请求时也取得到）；没有时经一个只连它的元数据 RpcClient 调 {@code stats()}（30s 超时）/
	 * {@code counters()}（{@link #COUNTERS_TIMEOUT}），这两个调用与压测请求排同一个业务执行器。
	 */
	private static final class ProviderProbe {
		private final RpcClient client;
		private final BenchService statsProxy;
		private final BenchService countersProxy;
		private final java.net.http.HttpClient http;
		private final String base;

		private ProviderProbe(RpcClient client, BenchService statsProxy, BenchService countersProxy, java.net.http.HttpClient http, String base) {
			this.client = client;
			this.statsProxy = statsProxy;
			this.countersProxy = countersProxy;
			this.http = http;
			this.base = base;
		}

		static ProviderProbe connect(ServiceInstance instance, String serializer, Integer controlPort) {
			if (controlPort != null) {
				java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(COUNTERS_TIMEOUT)
						.version(java.net.http.HttpClient.Version.HTTP_1_1).build();
				return new ProviderProbe(null, null, null, http, "http://" + instance.host() + ":" + controlPort);
			}
			String address = instance.address();
			RpcClient client = RpcClient.builder().registry(DirectRegistryFactory.config(address)).serializer(serializer).ioThreads(1)
					.connectionsPerNode(1).defaultTimeout(ENVIRONMENT_TIMEOUT).build();
			try {
				return new ProviderProbe(client,
						client.reference(BenchService.class, ReferenceOptions.defaults().timeout(ENVIRONMENT_TIMEOUT).retries(0)),
						client.reference(BenchService.class, ReferenceOptions.defaults().timeout(COUNTERS_TIMEOUT).retries(0)), null, null);
			} catch (RuntimeException e) {
				client.close();
				throw e;
			}
		}

		String channel() {
			return http != null ? base + " (HTTP control endpoint, IO thread)" : "rpc (BenchService.stats / counters through the business executor)";
		}

		Map<String, String> stats() {
			return http != null ? get(BenchHttpServer.STATS_PATH, ENVIRONMENT_TIMEOUT) : statsProxy.stats();
		}

		Map<String, String> counters() {
			return http != null ? get(BenchHttpServer.COUNTERS_PATH, COUNTERS_TIMEOUT) : countersProxy.counters();
		}

		private Map<String, String> get(String path, Duration timeout) {
			try {
				var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path)).timeout(timeout).GET().build(),
						java.net.http.HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) {
					throw new IllegalStateException("GET " + base + path + " -> HTTP " + response.statusCode());
				}
				Map<String, String> out = new LinkedHashMap<>();
				((Map<?, ?>) Json.parse(response.body())).forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
				return out;
			} catch (java.io.IOException e) {
				throw new IllegalStateException("GET " + base + path + ": " + e, e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted: GET " + base + path, e);
			}
		}

		void close() {
			if (client != null) {
				client.close();
			}
			if (http != null) {
				http.close();
			}
		}
	}
}
