package com.feynman.rpc.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析并校验后的口径卡（不可变）。字段与 YAML 一一对应，含义见 docs/benchmarking.md；构造只经
 * {@link ProfileLoader}，因此这里的值都已通过校验。
 *
 * @param rawYaml 卡片原文，原样进 JSON（事实源要能回答"当时到底跑的是什么"）
 *
 * <p>公开类型：对比对象的 {@link CallTargetProvider} 按它创建 {@link CallTarget}（读 payload 大小、超时、负载模型）。
 */
public record Profile(String id, String question, Target target, Load load, Isolation isolation, Measure measure,
		List<Assertion> assertions, String rawYaml) {

	/**
	 * 读卡并校验（{@link ProfileLoader#load}）：卡片的 {@code profile} 必须等于文件名去扩展名；不合法时抛出的异常消息逐条列出问题。
	 * 公开给对比模块的测试校验它们自己的卡。
	 */
	public static Profile load(java.nio.file.Path file) throws java.io.IOException {
		return ProfileLoader.load(file);
	}

	static final String MODEL_CLOSED = "closed";
	static final String MODEL_OPEN = "open";

	/**
	 * @param transport  {@code rpc} | {@code http} | classpath 上某个 {@link CallTargetProvider} 的名字（对比对象）
	 * @param serializer {@code transport = rpc} 时为 Serializer SPI 名；对比对象为它声明的编码名；{@code http} 时为 null（请求体就是原始字节）
	 * @param providers {@code http} 时固定为 1（一个 URL）
	 * @param httpUrl {@code target.http.url}，只在 {@code transport = http} 时非 null
	 * @param httpConnections {@code target.http.connections}（开环的连接池大小，缺省 {@value #DEFAULT_HTTP_CONNECTIONS}）；
	 *                        闭环每个虚拟用户独占一条连接，此值只记录不使用；{@code rpc} 时为 0
	 * @param connections {@code target.connections}：到每个提供者的连接数，null = 各框架缺省（rpc 2、gRPC 1 个 channel、Dubbo triple 1 条）。
	 *                    含义随 transport：rpc = {@code RpcClientConfig.connectionsPerNode}，grpc = ManagedChannel 数（每个一条 HTTP/2 连接，
	 *                    调用轮询取用），dubbo = 独立客户端实例数（各一条 triple 连接，轮询取用；triple 忽略 reference 的 connections 参数）；http 卡不用它（连接池是 {@code target.http.connections}）
	 * @param serverExecutor {@code target.server_executor}：被压服务端的业务执行器（{@code fixed} | {@code virtual} | {@code direct}），
	 *                    只用于 rpc 卡，缺省 {@code fixed}（框架缺省）；其它 transport 为 null。卡片据此声明"这张卡压的是哪种服务端配置"，
	 *                    {@code compare.sh} 据此给 bench server 传 {@code --executor}，压测端核对服务端环境里的实际执行器，不符则判定不通过
	 */
	public record Target(String transport, String service, String method, String payloadKind, int payloadSize, String serializer,
			int providers, String httpUrl, int httpConnections, Integer connections, String serverExecutor, RpcOptions rpcOptions) {

		/** 不含 {@code rpcOptions} 的形式：rpc 卡记全部缺省（{@link RpcOptions#DEFAULTS}），其余为 null。 */
		public Target(String transport, String service, String method, String payloadKind, int payloadSize, String serializer,
				int providers, String httpUrl, int httpConnections, Integer connections, String serverExecutor) {
			this(transport, service, method, payloadKind, payloadSize, serializer, providers, httpUrl, httpConnections, connections,
					serverExecutor, TRANSPORT_RPC.equals(transport) ? RpcOptions.DEFAULTS : null);
		}

		/** rpc 卡的可选字段；非 rpc 卡与旧构造方式为 {@link RpcOptions#DEFAULTS}。 */
		RpcOptions rpcOptionsOrDefaults() {
			return rpcOptions == null ? RpcOptions.DEFAULTS : rpcOptions;
		}

		/** 不含 {@code serverExecutor} 的旧形式：rpc 卡记缺省 {@code fixed}，其余为 null。 */
		public Target(String transport, String service, String method, String payloadKind, int payloadSize, String serializer,
				int providers, String httpUrl, int httpConnections, Integer connections) {
			this(transport, service, method, payloadKind, payloadSize, serializer, providers, httpUrl, httpConnections, connections,
					TRANSPORT_RPC.equals(transport) ? DEFAULT_SERVER_EXECUTOR : null);
		}

		public boolean http() {
			return TRANSPORT_HTTP.equals(transport);
		}

		/** 本框架（{@code transport = rpc}）。 */
		public boolean rpc() {
			return TRANSPORT_RPC.equals(transport);
		}

		/** 内置 transport 以外、由 {@link CallTargetProvider} 提供的对比对象。 */
		public boolean plugin() {
			return !http() && !rpc();
		}
	}

	/**
	 * rpc 卡的可选字段（故障注入实验用；全部不写 = 框架缺省 / 原口径）：
	 *
	 * @param loadBalance         {@code target.load_balance}：客户端负载均衡 SPI 名（{@code roundrobin} | {@code p2c}），null = 框架缺省
	 * @param cluster             {@code target.cluster}：容错策略 SPI 名（{@code failfast} | {@code failover} | {@code failback}），null = 框架缺省
	 * @param retries             {@code target.retries}：{@code ReferenceOptions.retries}，null = 0（原口径：不重试）
	 * @param serverQueueCapacity {@code target.server_queue_capacity}：被压服务端固定池的排队上限（只配 {@code server_executor: fixed}），
	 *                            null = 框架缺省 {@code ExecutorConfig.DEFAULT_QUEUE_CAPACITY}；即 bench server 的 {@code --executor-queue}
	 * @param serverWork          {@code target.server_work}：业务方法里用 {@code LockSupport.parkNanos} 等待的时长（模拟 IO 等待），
	 *                            null = 不等（原样回显）；即 bench server 的 {@code --work}
	 * @param serverDeregisterGrace {@code target.server_deregister_grace}：被压服务端的 {@code RpcServerConfig.deregisterGrace}（优雅停机时
	 *                            摘注册之后、开始拒绝新请求之前的等待），null = 框架缺省 0；即 bench server 的 {@code --deregister-grace}
	 */
	public record RpcOptions(String loadBalance, String cluster, Integer retries, Integer serverQueueCapacity, Duration serverWork,
			Duration serverDeregisterGrace) {

		/** 不含 {@code serverDeregisterGrace} 的形式（= 框架缺省 0）。 */
		public RpcOptions(String loadBalance, String cluster, Integer retries, Integer serverQueueCapacity, Duration serverWork) {
			this(loadBalance, cluster, retries, serverQueueCapacity, serverWork, null);
		}

		static final RpcOptions DEFAULTS = new RpcOptions(null, null, null, null, null, null);

		/** 卡片是否写了任何一项。 */
		boolean declared() {
			return !equals(DEFAULTS);
		}
	}

	/** 开环阶梯的一级：以 {@code rate} 发 {@code duration}（{@code load.steps}）。 */
	public record Step(int rate, Duration duration) {
	}

	/**
	 * 故障时间表的一项（{@code load.faults}）：harness 不执行故障，只在本轮开始时把时间表写给驱动脚本（{@code bench/bin/fault-run.sh}），
	 * 由脚本在对应时刻执行；harness 记录时间线并读回脚本的执行记录。
	 *
	 * @param at      相对本轮开始（本轮 t = 0，预热段之后）的时刻
	 * @param kind    {@link #FAULT_KINDS} 之一
	 * @param target  提供者编号（1 起，对应 fault-topology.sh 的启动顺序）
	 * @param latency {@code toxic-latency}：toxiproxy latency toxic 的延迟
	 * @param jitter  {@code toxic-latency}：抖动（缺省 0）
	 * @param timeout {@code toxic-timeout}：toxiproxy timeout toxic 的 timeout（缺省 0 = 连接挂住、数据丢弃、不关连接）
	 * @param stream  toxic 方向：{@code downstream}（提供者 → 客户端）| {@code upstream} | {@code both}；非 toxic 的 kind 为 null
	 */
	public record Fault(Duration at, String kind, int target, Duration latency, Duration jitter, Duration timeout, String stream) {

		/** 同一时刻、同一目标换一种 kind（{@code run --fault-variant}）；参数按新 kind 的缺省取。 */
		Fault withKind(String newKind) {
			if (TOXIC_TIMEOUT.equals(newKind)) {
				return new Fault(at, newKind, target, null, null, Duration.ZERO, "both");
			}
			if (TOXIC_LATENCY.equals(newKind) || REMOVE_TOXIC.equals(newKind)) {
				throw new IllegalArgumentException("--fault-variant " + newKind + " needs parameters; only kill-9 / sigterm / sigstop / toxic-timeout "
						+ "can replace a fault");
			}
			return new Fault(at, newKind, target, null, null, null, null);
		}
	}

	static final String TOXIC_LATENCY = "toxic-latency";
	static final String TOXIC_TIMEOUT = "toxic-timeout";
	static final String REMOVE_TOXIC = "remove-toxic";
	static final String KILL_9 = "kill-9";
	static final String SIGTERM = "sigterm";
	static final String SIGSTOP = "sigstop";
	static final String SIGCONT = "sigcont";
	static final java.util.Set<String> FAULT_KINDS = java.util.Set.of(TOXIC_LATENCY, TOXIC_TIMEOUT, REMOVE_TOXIC, KILL_9, SIGTERM, SIGSTOP,
			SIGCONT);
	/** {@code run --fault-variant} 可替换成的 kind（替换卡片里全部 {@code kill-9}）。 */
	static final java.util.Set<String> FAULT_VARIANTS = java.util.Set.of(KILL_9, SIGTERM, SIGSTOP, TOXIC_TIMEOUT);

	static final String TRANSPORT_RPC = "rpc";
	/** {@code target.server_executor} 的取值与缺省。 */
	static final java.util.Set<String> SERVER_EXECUTORS = java.util.Set.of("fixed", "virtual", "direct");
	static final String DEFAULT_SERVER_EXECUTOR = "fixed";
	static final String TRANSPORT_HTTP = "http";
	static final int DEFAULT_HTTP_CONNECTIONS = 64;

	/**
	 * @param concurrency closed 必填；open 时可出现但不使用（记为 null 以外的值也只是被记录）
	 * @param rate open 必填（req/s）；closed 时同上
	 */
	public record Load(String model, Integer concurrency, Integer rate, Duration duration, Duration warmup, Duration windowStart,
			Duration windowEnd, int rounds, Duration timeout, List<Step> steps, List<Fault> faults) {

		/** 不含阶梯与故障的形式（原口径）。 */
		public Load(String model, Integer concurrency, Integer rate, Duration duration, Duration warmup, Duration windowStart,
				Duration windowEnd, int rounds, Duration timeout) {
			this(model, concurrency, rate, duration, warmup, windowStart, windowEnd, rounds, timeout, List.of(), List.of());
		}

		public Load {
			steps = steps == null ? List.of() : List.copyOf(steps);
			faults = faults == null ? List.of() : List.copyOf(faults);
		}

		/** 卡片声明了阶梯或故障：本轮记每秒时间线（{@code rounds[].timeline}）；否则行为与原口径完全相同。 */
		boolean timeline() {
			return !steps.isEmpty() || !faults.isEmpty();
		}

		/** 开环的第一级速率（预热段用它，而不是峰值）：没有阶梯时就是 {@code rate}。 */
		int initialRate() {
			return steps.isEmpty() ? rate : steps.get(0).rate();
		}

		/** 同一负载换一组故障（{@code --no-faults} / {@code --fault-variant}）。 */
		Load withFaults(List<Fault> newFaults) {
			return new Load(model, concurrency, rate, duration, warmup, windowStart, windowEnd, rounds, timeout, steps, newFaults);
		}

		public boolean closed() {
			return MODEL_CLOSED.equals(model);
		}

		/** 开环在途上限 = rate × timeout × 2（至少 1；有阶梯时 rate 是峰值那一级）。超过即 backlog_dropped，本轮无效。 */
		long inflightCap() {
			double cap = (double) rate * (timeout.toNanos() / 1e9) * 2;
			return Math.max(1, (long) Math.ceil(cap));
		}

		Duration window() {
			return windowEnd.minus(windowStart);
		}
	}

	public record Isolation(String clientProcess, String serverProcess, boolean sameHost, String registry) {
	}

	public record Measure(int significantDigits, Duration histogramMax, List<String> report) {
	}

	/**
	 * 一条断言。{@code metric} 为 errors / throughput / p50 / p95 / p99 / p999 / max；{@code upperBound} 为 true 表示
	 * "实测 ≤ 阈值"（*_max），false 表示"实测 ≥ 阈值"（throughput_min）。延迟阈值以毫秒存。
	 */
	public record Assertion(String key, String metric, boolean upperBound, double threshold, String thresholdText) {
	}

	/** 一项故障的 JSON（{@code profile.parsed.load.faults[]} 与故障时间表文件共用）。 */
	static Map<String, Object> faultJson(Fault fault) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("at", Durations.format(fault.at()));
		f.put("at_ms", Durations.millis(fault.at()));
		f.put("kind", fault.kind());
		f.put("target", fault.target());
		if (fault.latency() != null) {
			f.put("latency_ms", Durations.millis(fault.latency()));
		}
		if (fault.jitter() != null) {
			f.put("jitter_ms", Durations.millis(fault.jitter()));
		}
		if (fault.timeout() != null) {
			f.put("timeout_ms", Durations.millis(fault.timeout()));
		}
		if (fault.stream() != null) {
			f.put("stream", fault.stream());
		}
		return f;
	}

	/** 同一张卡换一组故障（{@code --no-faults} 清空、{@code --fault-variant} 替换 kind）；卡片 id 与原文不变。 */
	Profile withFaults(List<Fault> faults) {
		return new Profile(id, question, target, load.withFaults(faults), isolation, measure, assertions, rawYaml);
	}

	/** 解析后的结构，进 JSON 的 {@code profile.parsed}。 */
	Map<String, Object> toJson() {
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("transport", target.transport());
		t.put("service", target.service());
		t.put("method", target.method());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", target.payloadKind());
		payload.put("size", target.payloadKind().equals("bytes") ? target.payloadSize() : null);
		t.put("payload", payload);
		t.put("serializer", target.serializer());
		t.put("providers", target.providers());
		if (!target.http()) {
			t.put("connections", target.connections());
		}
		if (target.rpc()) {
			t.put("server_executor", target.serverExecutor());
			RpcOptions o = target.rpcOptionsOrDefaults();
			if (o.declared()) {
				t.put("load_balance", o.loadBalance());
				t.put("cluster", o.cluster());
				t.put("retries", o.retries());
				t.put("server_queue_capacity", o.serverQueueCapacity());
				t.put("server_work", o.serverWork() == null ? null : Durations.format(o.serverWork()));
				t.put("server_work_ms", o.serverWork() == null ? null : Durations.millis(o.serverWork()));
				t.put("server_deregister_grace", o.serverDeregisterGrace() == null ? null : Durations.format(o.serverDeregisterGrace()));
			}
		}
		if (target.http()) {
			Map<String, Object> http = new LinkedHashMap<>();
			http.put("url", target.httpUrl());
			http.put("connections", target.httpConnections());
			t.put("http", http);
		}

		Map<String, Object> l = new LinkedHashMap<>();
		l.put("model", load.model());
		l.put("concurrency", load.concurrency());
		l.put("rate", load.rate());
		l.put("duration", Durations.format(load.duration()));
		l.put("duration_ms", Durations.millis(load.duration()));
		l.put("warmup", Durations.format(load.warmup()));
		l.put("warmup_ms", Durations.millis(load.warmup()));
		l.put("steady_window", List.of(Durations.format(load.windowStart()), Durations.format(load.windowEnd())));
		l.put("steady_window_ms", List.of(Durations.millis(load.windowStart()), Durations.millis(load.windowEnd())));
		l.put("rounds", load.rounds());
		l.put("timeout", Durations.format(load.timeout()));
		l.put("timeout_ms", Durations.millis(load.timeout()));
		l.put("inflight_cap", load.closed() ? null : load.inflightCap());
		if (!load.steps().isEmpty()) {
			List<Object> steps = new ArrayList<>();
			for (Step step : load.steps()) {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("rate", step.rate());
				s.put("duration", Durations.format(step.duration()));
				s.put("duration_ms", Durations.millis(step.duration()));
				steps.add(s);
			}
			l.put("steps", steps);
		}
		if (!load.faults().isEmpty()) {
			List<Object> faults = new ArrayList<>();
			for (Fault fault : load.faults()) {
				faults.add(faultJson(fault));
			}
			l.put("faults", faults);
		}

		Map<String, Object> i = new LinkedHashMap<>();
		i.put("client_process", isolation.clientProcess());
		i.put("server_process", isolation.serverProcess());
		i.put("same_host", isolation.sameHost());
		i.put("registry", isolation.registry());

		Map<String, Object> histogram = new LinkedHashMap<>();
		histogram.put("significant_digits", measure.significantDigits());
		histogram.put("max", Durations.format(measure.histogramMax()));
		histogram.put("max_ms", Durations.millis(measure.histogramMax()));
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("histogram", histogram);
		m.put("report", measure.report());

		List<Object> a = new ArrayList<>();
		for (Assertion assertion : assertions) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", assertion.key());
			entry.put("metric", assertion.metric());
			entry.put("bound", assertion.upperBound() ? "max" : "min");
			entry.put("threshold", assertion.thresholdText());
			a.add(entry);
		}

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("profile", id);
		root.put("question", question);
		root.put("target", t);
		root.put("load", l);
		root.put("isolation", i);
		root.put("measure", m);
		root.put("assert", a);
		return root;
	}
}
