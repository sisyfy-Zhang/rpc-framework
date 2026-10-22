package com.feynman.rpc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import com.feynman.rpc.core.cluster.Cluster;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.serialize.Serializer;

/**
 * 口径卡 YAML → {@link Profile}。SnakeYAML 只用 {@link SafeConstructor} 加载成 Map / List / 标量（不反序列化到 Bean，
 * 卡片里的 {@code !!java...} 标签会被拒绝），再逐字段手工映射与校验。
 *
 * <p>校验一次收集全部问题，每条带字段路径（{@code load.steady_window[1]: ...}），而不是遇到第一个错就停：
 * 改卡片的人一次就能看到所有要改的地方。未知字段一律拒绝——拼错的 {@code warm_up} 被静默忽略，
 * 比直接报错危险得多（卡片以为有预热，实际没有）。
 */
final class ProfileLoader {

	private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
	static final Set<String> REPORT_ITEMS = new LinkedHashSet<>(
			List.of("throughput", "mean", "p50", "p90", "p95", "p99", "p999", "max", "errors_by_status"));
	private static final Set<String> SERVICES = Set.of("BenchService#echo", "BenchService#echoOrder");
	private static final Set<String> PROCESS_KINDS = Set.of("separate_jvm", "same_jvm");
	/** {@code target.connections} 上限：每个提供者的连接数，压测里用到 8 已经足够说明趋势。 */
	static final int MAX_CONNECTIONS = 64;
	/** bytes 负载上限：协议帧上限是 8MiB（请求帧还要装信封与参数），4MiB 以上的回显必然撞线，此处直接拒绝。 */
	private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;

	private ProfileLoader() {
	}

	/** 读文件并校验；卡片的 {@code profile} 必须等于文件名去掉扩展名。 */
	static Profile load(Path file) throws IOException {
		String yaml = Files.readString(file, StandardCharsets.UTF_8);
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		return parse(yaml, stem, file.toString());
	}

	/**
	 * @param expectedId 文件名（去扩展名）；null 表示不校验一致性（只用于测试解析本身）
	 */
	static Profile parse(String yaml, String expectedId, String source) {
		List<String> problems = new ArrayList<>();
		Object root;
		try {
			LoaderOptions options = new LoaderOptions();
			options.setAllowDuplicateKeys(false);
			options.setMaxAliasesForCollections(10);
			options.setAllowRecursiveKeys(false);
			root = new Yaml(new SafeConstructor(options)).load(yaml);
		} catch (YAMLException e) {
			throw new ProfileException(source, List.of("(yaml): " + e.getMessage().replace('\n', ' ')));
		}
		if (!(root instanceof Map<?, ?>)) {
			throw new ProfileException(source, List.of("(root): expected a mapping, got " + typeName(root)));
		}
		Section top = new Section("", (Map<?, ?>) root, problems);
		top.allowOnly("profile", "question", "target", "load", "isolation", "measure", "assert");

		String id = top.string("profile", true);
		if (id != null && !ID.matcher(id).matches()) {
			problems.add("profile: \"" + id + "\" must match " + ID.pattern());
		}
		if (id != null && expectedId != null && !id.equals(expectedId)) {
			problems.add("profile: \"" + id + "\" must equal the file name without extension (\"" + expectedId + "\")");
		}
		String question = top.string("question", true);

		Profile.Target target = target(top.section("target", true), problems);
		Profile.Load load = load(top.section("load", true), problems);
		Profile.Isolation isolation = isolation(top.section("isolation", true), problems);
		Profile.Measure measure = measure(top.section("measure", true), problems, load);
		List<Profile.Assertion> assertions = assertions(top.section("assert", false), problems);
		crossCheckFaults(target, load, problems);

		if (!problems.isEmpty()) {
			throw new ProfileException(source, problems);
		}
		return new Profile(id, question, target, load, isolation, measure, assertions, yaml);
	}

	private static Profile.Target target(Section s, List<String> problems) {
		if (s == null) {
			return null;
		}
		s.allowOnly("transport", "service", "payload", "serializer", "providers", "connections", "http", "server_executor", "load_balance",
				"cluster", "retries", "server_queue_capacity", "server_work", "server_deregister_grace", "server_work_mode");
		// 内置 rpc / http + classpath 上的 CallTargetProvider（对比对象）；不认识的名字报错并列出可用的全部 transport
		Map<String, CallTargetProvider> plugins;
		try {
			plugins = CallTargets.providers();
		} catch (IllegalStateException e) {
			problems.add("target.transport: " + e.getMessage());
			plugins = Map.of();
		}
		Set<String> transports = new LinkedHashSet<>(CallTargets.BUILTIN);
		transports.addAll(plugins.keySet());
		String transport = s.string("transport", true);
		if (transport != null && !transports.contains(transport)) {
			problems.add("target.transport: " + CallTargets.unknownMessage(transport));
			transport = null;
		}
		boolean http = Profile.TRANSPORT_HTTP.equals(transport);
		CallTargetProvider plugin = transport == null ? null : plugins.get(transport);
		String service = s.oneOf("service", true, plugin == null ? SERVICES : plugin.services());
		String method = service == null ? null : service.substring(service.indexOf('#') + 1);
		Section payload = s.section("payload", true);
		String kind = null;
		int size = 0;
		if (payload != null) {
			payload.allowOnly("kind", "size");
			kind = payload.oneOf("kind", true, Set.of("bytes", "order"));
			if ("bytes".equals(kind)) {
				Integer n = payload.integer("size", true, 1, MAX_PAYLOAD_BYTES);
				size = n == null ? 0 : n;
			} else if ("order".equals(kind) && payload.has("size")) {
				problems.add("target.payload.size: only applies to kind = bytes (the order fixture has a fixed shape)");
			}
			if ("echo".equals(method) && "order".equals(kind) || "echoOrder".equals(method) && "bytes".equals(kind)) {
				problems.add("target.payload.kind: \"" + kind + "\" does not match target.service " + service
						+ " (echo takes bytes, echoOrder takes order)");
			}
			if (http && "order".equals(kind)) {
				problems.add("target.payload.kind: transport = http only takes bytes (the HTTP body is the raw payload, "
						+ "there is no serializer to encode an order)");
			}
		}
		if (http) {
			return httpTarget(s, problems, transport, service, method, kind, size);
		}
		if (s.has("http")) {
			problems.add("target.http: only applies to transport = http (an rpc card is addressed with --direct / --registry)");
		}
		// rpc：classpath 上实际可用的 Serializer SPI 名；对比对象：提供者声明的编码名（例如 gRPC 的 protobuf 生成代码）
		Set<String> serializers = plugin != null ? plugin.serializers()
				: ExtensionLoader.getExtensionLoader(Serializer.class).getSupportedExtensions();
		String serializer = s.oneOf("serializer", true, serializers);
		Integer providers = s.integer("providers", true, 1, 1024);
		// 可选：到每个提供者的连接数（rpc = connectionsPerNode，grpc = ManagedChannel 数，dubbo = 独立客户端实例数）；不写 = 各框架缺省
		Integer connections = s.has("connections") ? s.integer("connections", false, 1, MAX_CONNECTIONS) : null;
		// 被压服务端的业务执行器：只有本框架的卡声明它（对比对象的服务端执行器由各自的服务端参数决定，记在服务端环境里）
		String serverExecutor = null;
		if (Profile.TRANSPORT_RPC.equals(transport)) {
			serverExecutor = s.has("server_executor") ? s.oneOf("server_executor", false, Profile.SERVER_EXECUTORS)
					: Profile.DEFAULT_SERVER_EXECUTOR;
		} else if (s.has("server_executor")) {
			problems.add("target.server_executor: only applies to transport = rpc (a comparison server's executor is its own server option)");
		}
		Profile.RpcOptions rpcOptions = rpcOptions(s, problems, transport, serverExecutor);
		return new Profile.Target(transport, service, method, kind, size, serializer, providers == null ? 0 : providers, null, 0,
				connections, serverExecutor, rpcOptions);
	}

	/** rpc 卡的可选字段（负载均衡、容错、重试、服务端排队上限与业务等待）；其它 transport 写了就拒绝。 */
	private static Profile.RpcOptions rpcOptions(Section s, List<String> problems, String transport, String serverExecutor) {
		List<String> keys = List.of("load_balance", "cluster", "retries", "server_queue_capacity", "server_work", "server_deregister_grace",
				"server_work_mode");
		if (!Profile.TRANSPORT_RPC.equals(transport)) {
			for (String key : keys) {
				if (s.has(key)) {
					problems.add("target." + key + ": only applies to transport = rpc");
				}
			}
			return transport == null ? Profile.RpcOptions.DEFAULTS : null;
		}
		String loadBalance = s.has("load_balance")
				? s.oneOf("load_balance", false, ExtensionLoader.getExtensionLoader(LoadBalance.class).getSupportedExtensions())
				: null;
		String cluster = s.has("cluster") ? s.oneOf("cluster", false, ExtensionLoader.getExtensionLoader(Cluster.class).getSupportedExtensions())
				: null;
		Integer retries = s.has("retries") ? s.integer("retries", false, 0, 10) : null;
		Integer queue = s.has("server_queue_capacity") ? s.integer("server_queue_capacity", false, 0, 10_000_000) : null;
		if (queue != null && serverExecutor != null && !"fixed".equals(serverExecutor)) {
			problems.add("target.server_queue_capacity: only applies to server_executor: fixed (got " + serverExecutor + ")");
		}
		Duration work = s.has("server_work") ? s.duration("server_work", false) : null;
		if (work != null && work.compareTo(Duration.ofSeconds(1)) > 0) {
			problems.add("target.server_work: " + Durations.format(work) + " is longer than 1s; this is a simulated IO wait, not a sleep test");
		}
		Duration grace = s.has("server_deregister_grace") ? s.durationAllowZero("server_deregister_grace", false) : null;
		if (grace != null && grace.compareTo(Duration.ofSeconds(30)) > 0) {
			problems.add("target.server_deregister_grace: " + Durations.format(grace) + " is longer than 30s");
		}
		String workMode = s.has("server_work_mode") ? s.oneOf("server_work_mode", false, BenchServiceImpl.WorkMode.cardValues()) : null;
		if (workMode != null && work == null && !s.has("server_work")) {
			problems.add("target.server_work_mode: only applies together with target.server_work (there is nothing to wait for)");
		}
		return new Profile.RpcOptions(loadBalance, cluster, retries, queue, work, grace, workMode);
	}

	/**
	 * {@code transport = http}：目标是 {@code target.http.url}（{@code bench server --http-port} 的 {@code POST /echo}，
	 * 或任何原样回显请求体的 HTTP/1.1 端点）。没有序列化器、只有一个 URL，所以 {@code serializer} / {@code providers}
	 * 不适用、写了就拒绝（卡片上不该出现不起作用的字段）。
	 */
	private static Profile.Target httpTarget(Section s, List<String> problems, String transport, String service, String method,
			String kind, int size) {
		if (s.has("serializer")) {
			problems.add("target.serializer: does not apply to transport = http (the body is the raw payload bytes)");
		}
		if (s.has("providers")) {
			problems.add("target.providers: does not apply to transport = http (the target is a single URL)");
		}
		if (s.has("server_executor")) {
			problems.add("target.server_executor: does not apply to transport = http");
		}
		if (s.has("connections")) {
			problems.add("target.connections: does not apply to transport = http (the connection pool is target.http.connections)");
		}
		for (String key : List.of("load_balance", "cluster", "retries", "server_queue_capacity", "server_work", "server_deregister_grace",
				"server_work_mode")) {
			if (s.has(key)) {
				problems.add("target." + key + ": only applies to transport = rpc");
			}
		}
		Section http = s.section("http", false);
		if (http == null) {
			if (!s.has("http")) {
				problems.add("target.http: required when transport = http ({ url: http://host:port/echo, connections: 64 })");
			}
			return new Profile.Target(transport, service, method, kind, size, null, 1, null, Profile.DEFAULT_HTTP_CONNECTIONS, null);
		}
		http.allowOnly("url", "connections");
		String url = http.string("url", true);
		if (url != null) {
			String problem = httpUrlProblem(url);
			if (problem != null) {
				problems.add("target.http.url: " + problem);
				url = null;
			}
		}
		Integer connections = http.has("connections") ? http.integer("connections", false, 1, 100_000) : null;
		return new Profile.Target(transport, service, method, kind, size, null, 1, url,
				connections == null ? Profile.DEFAULT_HTTP_CONNECTIONS : connections, null);
	}

	/** null = 合法：{@code http://host[:port]/path}，不支持 https（压的是明文 HTTP/1.1，与 wrk 对照）。 */
	static String httpUrlProblem(String url) {
		java.net.URI uri;
		try {
			uri = new java.net.URI(url);
		} catch (java.net.URISyntaxException e) {
			return "\"" + url + "\" is not a valid URI (" + e.getReason() + ")";
		}
		if (!"http".equalsIgnoreCase(uri.getScheme())) {
			return "\"" + url + "\" must start with http:// (plain HTTP/1.1 only)";
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return "\"" + url + "\" has no host";
		}
		if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
			return "\"" + url + "\" has no path (e.g. /echo)";
		}
		return null;
	}

	private static Profile.Load load(Section s, List<String> problems) {
		if (s == null) {
			return null;
		}
		s.allowOnly("model", "concurrency", "rate", "duration", "warmup", "steady_window", "rounds", "timeout", "steps", "faults");
		String model = s.oneOf("model", true, Set.of(Profile.MODEL_CLOSED, Profile.MODEL_OPEN));
		// 另一种模型的字段可以出现（例如卡片同时写了 concurrency 与 rate），只记录不使用
		Integer concurrency = s.integer("concurrency", false, 1, 100_000);
		if (Profile.MODEL_CLOSED.equals(model) && s.raw("concurrency") == null) {
			problems.add("load.concurrency: required when load.model = closed");
		}
		Integer rate = s.integer("rate", false, 1, 10_000_000);
		boolean hasSteps = s.has("steps");
		if (Profile.MODEL_OPEN.equals(model) && s.raw("rate") == null && !hasSteps) {
			problems.add("load.rate: required when load.model = open (or give load.steps)");
		}
		Duration duration = s.duration("duration", true);
		List<Profile.Step> steps = steps(s, problems, model, duration);
		if (hasSteps && s.raw("rate") != null) {
			problems.add("load.rate: do not combine with load.steps (the steps define the rate; the peak step is the in-flight cap)");
		}
		if (!steps.isEmpty()) {
			rate = steps.stream().mapToInt(Profile.Step::rate).max().orElse(1);
		}
		Duration warmup = s.durationAllowZero("warmup", true);
		Duration windowStart = null;
		Duration windowEnd = null;
		Object window = s.raw("steady_window");
		if (window == null) {
			problems.add("load.steady_window: required ([start, end] relative to the round start)");
		} else if (!(window instanceof List<?> list) || list.size() != 2) {
			problems.add("load.steady_window: expected a list of two durations [start, end], got " + describe(window));
		} else {
			windowStart = s.durationValue("steady_window[0]", list.get(0), true);
			windowEnd = s.durationValue("steady_window[1]", list.get(1), false);
		}
		Integer rounds = s.integer("rounds", true, 1, 100);
		Duration timeout = s.duration("timeout", true);

		if (duration != null && warmup != null && warmup.compareTo(duration) >= 0) {
			problems.add("load.warmup: " + Durations.format(warmup) + " must be shorter than load.duration " + Durations.format(duration));
		}
		if (windowStart != null && windowEnd != null) {
			if (windowStart.compareTo(windowEnd) >= 0) {
				problems.add("load.steady_window: start " + Durations.format(windowStart) + " must be before end " + Durations.format(windowEnd));
			}
			if (warmup != null && windowStart.compareTo(warmup) < 0) {
				problems.add("load.steady_window[0]: " + Durations.format(windowStart) + " starts inside the warmup (" + Durations.format(warmup)
						+ "); the window must begin at or after load.warmup");
			}
			if (duration != null && windowEnd.compareTo(duration) > 0) {
				problems.add("load.steady_window[1]: " + Durations.format(windowEnd) + " ends after load.duration " + Durations.format(duration));
			}
		}
		if (rate != null && timeout != null && Profile.MODEL_OPEN.equals(model)) {
			double cap = (double) rate * timeout.toNanos() / 1e9 * 2;
			if (cap > 5_000_000) {
				problems.add("load: rate x timeout x 2 = " + (long) cap + " in-flight calls would be allowed; lower rate or timeout");
			}
		}
		List<Profile.Fault> faults = faults(s, problems, duration);
		if (!faults.isEmpty() && rounds != null && rounds != 1) {
			problems.add("load.rounds: must be 1 when load.faults is given (a fault such as kill-9 cannot be replayed in the next round; "
					+ "repeat the experiment as separate runs on a fresh topology)");
		}
		return new Profile.Load(model, concurrency, rate, duration, warmup, windowStart, windowEnd, rounds == null ? 0 : rounds, timeout,
				steps, faults);
	}

	/**
	 * {@code load.steps}：开环阶梯，{@code [{rate, duration}, ...]}，按顺序切换速率；各级时长之和必须等于 {@code load.duration}。
	 * 不写 = 原口径（单一速率）。
	 */
	private static List<Profile.Step> steps(Section s, List<String> problems, String model, Duration duration) {
		List<Profile.Step> steps = new ArrayList<>();
		if (!s.has("steps")) {
			return steps;
		}
		Object raw = s.raw("steps");
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			problems.add("load.steps: expected a non-empty list of { rate, duration }, got " + describe(raw));
			return steps;
		}
		if (Profile.MODEL_CLOSED.equals(model)) {
			problems.add("load.steps: only applies to load.model = open");
		}
		Duration total = Duration.ZERO;
		boolean complete = true;
		for (int i = 0; i < list.size(); i++) {
			Object item = list.get(i);
			String path = "load.steps[" + i + "]";
			if (!(item instanceof Map<?, ?> m)) {
				problems.add(path + ": expected a mapping { rate, duration }, got " + describe(item));
				complete = false;
				continue;
			}
			Section step = new Section(path, m, problems);
			step.allowOnly("rate", "duration");
			Integer rate = step.integer("rate", true, 1, 10_000_000);
			Duration d = step.duration("duration", true);
			if (rate == null || d == null) {
				complete = false;
				continue;
			}
			total = total.plus(d);
			steps.add(new Profile.Step(rate, d));
		}
		if (complete && duration != null && !total.equals(duration)) {
			problems.add("load.steps: durations add up to " + Durations.format(total) + " but load.duration is " + Durations.format(duration));
		}
		return steps;
	}

	/**
	 * {@code load.faults}：故障时间表 {@code [{at, kind, target, ...参数}]}。harness 不执行故障，只把时间表交给驱动脚本
	 * （见 {@link Profile.Fault}）；这里校验 kind 与参数是否相配、时刻是否落在本轮之内。target 的范围在 {@link #crossCheckFaults} 里核对。
	 */
	private static List<Profile.Fault> faults(Section s, List<String> problems, Duration duration) {
		List<Profile.Fault> faults = new ArrayList<>();
		if (!s.has("faults")) {
			return faults;
		}
		Object raw = s.raw("faults");
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			problems.add("load.faults: expected a non-empty list of { at, kind, target, ... }, got " + describe(raw));
			return faults;
		}
		for (int i = 0; i < list.size(); i++) {
			Object item = list.get(i);
			String path = "load.faults[" + i + "]";
			if (!(item instanceof Map<?, ?> m)) {
				problems.add(path + ": expected a mapping { at, kind, target, ... }, got " + describe(item));
				continue;
			}
			Section f = new Section(path, m, problems);
			String kind = f.oneOf("kind", true, Profile.FAULT_KINDS);
			Duration at = f.durationAllowZero("at", true);
			Integer target = f.integer("target", true, 1, 1024);
			Duration latency = null;
			Duration jitter = null;
			Duration timeout = null;
			String stream = null;
			if (Profile.TOXIC_LATENCY.equals(kind)) {
				f.allowOnly("at", "kind", "target", "latency", "jitter", "stream");
				latency = f.duration("latency", true);
				jitter = f.has("jitter") ? f.durationAllowZero("jitter", false) : Duration.ZERO;
				stream = f.has("stream") ? f.oneOf("stream", false, STREAMS) : "downstream";
			} else if (Profile.TOXIC_TIMEOUT.equals(kind)) {
				f.allowOnly("at", "kind", "target", "timeout", "stream");
				timeout = f.has("timeout") ? f.durationAllowZero("timeout", false) : Duration.ZERO;
				stream = f.has("stream") ? f.oneOf("stream", false, STREAMS) : "both";
			} else {
				f.allowOnly("at", "kind", "target");
			}
			if (at != null && duration != null && at.compareTo(duration) >= 0) {
				problems.add(path + ".at: " + Durations.format(at) + " is not inside the round (load.duration " + Durations.format(duration) + ")");
			}
			if (kind != null && at != null && target != null) {
				faults.add(new Profile.Fault(at, kind, target, latency, jitter, timeout, stream));
			}
		}
		for (int i = 1; i < faults.size(); i++) {
			if (faults.get(i).at().compareTo(faults.get(i - 1).at()) < 0) {
				problems.add("load.faults: list the faults in time order (faults[" + i + "].at is earlier than faults[" + (i - 1) + "].at)");
				break;
			}
		}
		return faults;
	}

	private static final Set<String> STREAMS = Set.of("downstream", "upstream", "both");

	/** 故障只用于 rpc 卡（驱动脚本按注册中心拓扑杀进程 / 加 toxic），target 必须是存在的提供者编号。 */
	private static void crossCheckFaults(Profile.Target target, Profile.Load load, List<String> problems) {
		if (target == null || load == null || load.faults().isEmpty()) {
			return;
		}
		if (target.transport() != null && !target.rpc()) {
			problems.add("load.faults: only applies to transport = rpc (the fault driver works on a bench-server topology)");
		}
		for (int i = 0; i < load.faults().size(); i++) {
			int t = load.faults().get(i).target();
			if (target.providers() > 0 && t > target.providers()) {
				problems.add("load.faults[" + i + "].target: " + t + " but target.providers is " + target.providers());
			}
		}
	}

	private static Profile.Isolation isolation(Section s, List<String> problems) {
		if (s == null) {
			return null;
		}
		s.allowOnly("client_process", "server_process", "same_host", "registry");
		String client = s.oneOf("client_process", true, PROCESS_KINDS);
		String server = s.oneOf("server_process", true, PROCESS_KINDS);
		Boolean sameHost = s.bool("same_host", true);
		String registry = s.string("registry", true);
		return new Profile.Isolation(client, server, Boolean.TRUE.equals(sameHost), registry);
	}

	private static Profile.Measure measure(Section s, List<String> problems, Profile.Load load) {
		if (s == null) {
			return null;
		}
		s.allowOnly("histogram", "report");
		Section histogram = s.section("histogram", true);
		Integer digits = null;
		Duration max = null;
		if (histogram != null) {
			histogram.allowOnly("significant_digits", "max");
			digits = histogram.integer("significant_digits", true, 1, 5);
			max = histogram.duration("max", true);
			if (max != null && load != null && load.timeout() != null && max.compareTo(load.timeout()) <= 0) {
				problems.add("measure.histogram.max: " + Durations.format(max) + " must exceed load.timeout " + Durations.format(load.timeout())
						+ " (a call can take up to the timeout)");
			}
		}
		List<String> report = new ArrayList<>();
		Object raw = s.raw("report");
		if (raw == null) {
			problems.add("measure.report: required (subset of " + REPORT_ITEMS + ")");
		} else if (!(raw instanceof List<?> list) || list.isEmpty()) {
			problems.add("measure.report: expected a non-empty list, got " + describe(raw));
		} else {
			for (int i = 0; i < list.size(); i++) {
				Object item = list.get(i);
				if (!(item instanceof String name) || !REPORT_ITEMS.contains(name)) {
					problems.add("measure.report[" + i + "]: " + describe(item) + " is not one of " + REPORT_ITEMS);
				} else if (report.contains(name)) {
					problems.add("measure.report[" + i + "]: duplicate \"" + name + "\"");
				} else {
					report.add(name);
				}
			}
		}
		return new Profile.Measure(digits == null ? 0 : digits, max, List.copyOf(report));
	}

	private static List<Profile.Assertion> assertions(Section s, List<String> problems) {
		List<Profile.Assertion> result = new ArrayList<>();
		if (s == null) {
			return result;
		}
		s.allowOnly("errors_max", "throughput_min", "p50_max", "p95_max", "p99_max", "p999_max", "max_max");
		for (Object rawKey : s.map.keySet()) {
			String key = String.valueOf(rawKey);
			switch (key) {
				case "errors_max" -> {
					Integer n = s.integer(key, true, 0, Integer.MAX_VALUE);
					if (n != null) {
						result.add(new Profile.Assertion(key, "errors", true, n, String.valueOf(n)));
					}
				}
				case "throughput_min" -> {
					Integer n = s.integer(key, true, 0, Integer.MAX_VALUE);
					if (n != null) {
						result.add(new Profile.Assertion(key, "throughput", false, n, n + " req/s"));
					}
				}
				case "p50_max", "p95_max", "p99_max", "p999_max", "max_max" -> {
					Duration d = s.duration(key, true);
					if (d != null) {
						result.add(new Profile.Assertion(key, key.substring(0, key.length() - 4), true, Durations.millis(d),
								Durations.format(d)));
					}
				}
				default -> {
					// allowOnly 已报告
				}
			}
		}
		return result;
	}

	static String describe(Object value) {
		return value == null ? "nothing" : typeName(value) + " " + value;
	}

	private static String typeName(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Map<?, ?>) {
			return "mapping";
		}
		if (value instanceof List<?>) {
			return "list";
		}
		if (value instanceof String) {
			return "string";
		}
		if (value instanceof Number) {
			return "number";
		}
		if (value instanceof Boolean) {
			return "boolean";
		}
		return value.getClass().getSimpleName();
	}

	/** 一个 mapping 节点 + 它的路径前缀；取值时类型 / 范围不对就记一条问题并返回 null。 */
	private static final class Section {
		final String path;
		final Map<?, ?> map;
		final List<String> problems;

		Section(String path, Map<?, ?> map, List<String> problems) {
			this.path = path;
			this.map = map;
			this.problems = problems;
		}

		String at(String key) {
			return path.isEmpty() ? key : path + "." + key;
		}

		boolean has(String key) {
			return map.containsKey(key);
		}

		Object raw(String key) {
			return map.get(key);
		}

		void allowOnly(String... keys) {
			Set<String> allowed = Set.of(keys);
			for (Object key : map.keySet()) {
				if (!(key instanceof String k) || !allowed.contains(k)) {
					problems.add(at(String.valueOf(key)) + ": unknown field (allowed: " + String.join(", ", keys) + ")");
				}
			}
		}

		private boolean missing(String key, boolean required) {
			if (map.get(key) != null) {
				return false;
			}
			if (required) {
				problems.add(at(key) + ": required");
			}
			return true;
		}

		Section section(String key, boolean required) {
			if (missing(key, required)) {
				return null;
			}
			Object value = map.get(key);
			if (!(value instanceof Map<?, ?> m)) {
				problems.add(at(key) + ": expected a mapping, got " + describe(value));
				return null;
			}
			return new Section(at(key), m, problems);
		}

		String string(String key, boolean required) {
			if (missing(key, required)) {
				return null;
			}
			Object value = map.get(key);
			if (!(value instanceof String s) || s.isBlank()) {
				problems.add(at(key) + ": expected a non-blank string, got " + describe(value));
				return null;
			}
			return s.trim();
		}

		String oneOf(String key, boolean required, Set<String> allowed) {
			String value = string(key, required);
			if (value != null && !allowed.contains(value)) {
				problems.add(at(key) + ": \"" + value + "\" is not one of " + new java.util.TreeSet<>(allowed));
				return null;
			}
			return value;
		}

		Integer integer(String key, boolean required, int min, int max) {
			if (missing(key, required)) {
				return null;
			}
			Object value = map.get(key);
			if (!(value instanceof Integer n)) {
				problems.add(at(key) + ": expected an integer, got " + describe(value));
				return null;
			}
			if (n < min || n > max) {
				problems.add(at(key) + ": " + n + " is out of range [" + min + ", " + max + "]");
				return null;
			}
			return n;
		}

		Boolean bool(String key, boolean required) {
			if (missing(key, required)) {
				return null;
			}
			Object value = map.get(key);
			if (!(value instanceof Boolean b)) {
				problems.add(at(key) + ": expected true or false, got " + describe(value));
				return null;
			}
			return b;
		}

		Duration duration(String key, boolean required) {
			if (missing(key, required)) {
				return null;
			}
			return durationValue(key, map.get(key), false);
		}

		Duration durationAllowZero(String key, boolean required) {
			if (missing(key, required)) {
				return null;
			}
			return durationValue(key, map.get(key), true);
		}

		Duration durationValue(String key, Object value, boolean allowZero) {
			if (!(value instanceof String text)) {
				problems.add(at(key) + ": expected a duration with a unit (e.g. 500ms, 20s, 1m), got " + describe(value));
				return null;
			}
			try {
				Duration d = Durations.parse(text);
				if (d.isZero() && !allowZero) {
					problems.add(at(key) + ": must be greater than zero");
					return null;
				}
				return d;
			} catch (IllegalArgumentException e) {
				problems.add(at(key) + ": " + e.getMessage());
				return null;
			}
		}
	}
}
