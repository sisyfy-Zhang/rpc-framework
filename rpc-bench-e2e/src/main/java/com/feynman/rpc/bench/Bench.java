package com.feynman.rpc.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.metrics.micrometer.PrometheusEndpoint;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 命令行入口。子命令：
 * <pre>
 *   server --port &lt;p&gt; [--host &lt;h&gt;] [--registry zk://host:port | --no-registry] [--serializer protobuf] [--metrics-port &lt;p&gt;]
 *          [--http-port &lt;p&gt;] [--io-threads &lt;n&gt;] [--executor fixed|virtual|direct] [--flush-consolidation on|off]
 *   server --transport &lt;name&gt; ...     （对比对象的服务端：参数交给该 transport 的 {@link ServerLauncher}）
 *   run    --profile &lt;file.yaml&gt; (--registry zk://host:port | --direct host:port[,host:port]) [--out &lt;dir&gt;] [--label &lt;text&gt;]
 *          [--metrics-port &lt;p&gt;]       （transport: http 的卡不带 --registry / --direct：目标是卡上的 target.http.url）
 *          [--open-workers per-request|pool[:N]|pool-platform[:N]] [--http-client netty|blocking] [--io-threads &lt;n&gt;] [--io-busy-poll on|off]
 *                                       （--io-threads 对 rpc 卡 = RpcClientConfig.ioThreads，对比对象由其提供者使用）
 *          [--pin-dispatcher &lt;cpu&gt;|off] [--pre-warmup &lt;时长&gt;|0] [--flush-consolidation on|off]
 *                                       （harness 实现选项，见 {@link HarnessOptions}；--flush-consolidation 只用于 rpc 卡）
 *   report --in &lt;dir|file.json&gt; [--out &lt;file.md&gt;]   （压测结果 + crossval 结果 JSON 一起汇总，含对比表）
 *   compare --in &lt;dir|file.json&gt; [--out &lt;file.md&gt;]  （同一负载 / 负载大小、不同 transport 或并发的结果并排成表，见 {@link CompareReport}）
 *   crossval --harness &lt;result.json&gt; --tool wrk|wrk2 --tool-output &lt;file&gt;[,&lt;file&gt;...] [--tool-command &lt;text&gt;] --out &lt;dir&gt;
 * </pre>
 * 退出码：0 成功；1 用法错误、口径卡不合法或运行异常；2 实验判定未通过（断言失败或有无效轮次）。
 *
 * <p>{@code --metrics-port}（缺省关闭）：给被压的 RpcServer / RpcClient 加 Micrometer 指标 Filter 与 Gauge，并在该端口内嵌
 * Prometheus 抓取端点 {@code /metrics}（docker/ 里的 Prometheus 预置了 9464 = server、9465 = run）。指标 Filter 在热路径上，
 * 正式数字默认不开；开了时 JSON 的 {@code environment.metrics_enabled} / {@code server_environment.metrics_enabled} 为 true。
 */
public final class Bench {

	static final int OK = 0;
	static final int ERROR = 1;
	static final int FAILED_ASSERTIONS = 2;

	private Bench() {
	}

	public static void main(String[] args) {
		int code = execute(args, System.out, System.err);
		// server 子命令阻塞到关闭钩子执行完；其余子命令执行完即退出（不等可能残留的非守护线程）
		System.exit(code);
	}

	/** 可在进程内调用（集成测试，含对比模块的端到端测试），不调用 System.exit。 */
	public static int execute(String[] args, PrintStream out, PrintStream err) {
		if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
			usage(out);
			return args.length == 0 ? ERROR : OK;
		}
		String command = args[0];
		try {
			Options options = Options.parse(args, 1);
			return switch (command) {
				case "server" -> server(options, args, out);
				case "run" -> run(options, args, out);
				case "report" -> report(options, out, err);
				case "compare" -> compare(options, out, err);
				case "crossval" -> CrossValidation.command(options, out);
				default -> {
					err.println("unknown command: " + command);
					usage(err);
					yield ERROR;
				}
			};
		} catch (UsageException e) {
			err.println("error: " + e.getMessage());
			usage(err);
			return ERROR;
		} catch (ProfileException e) {
			err.println(e.getMessage());
			return ERROR;
		} catch (Exception e) {
			err.println("error: " + e);
			e.printStackTrace(err);
			return ERROR;
		}
	}

	private static int server(Options options, String[] rawArgs, PrintStream out) throws Exception {
		String transport = options.value("transport", Profile.TRANSPORT_RPC);
		if (!Profile.TRANSPORT_RPC.equals(transport)) {
			return pluginServer(transport, rawArgs, out);
		}
		options.allowOnly("transport", "port", "host", "registry", "no-registry", "serializer", "metrics-port", "http-port", "io-threads",
				"executor", "flush-consolidation", "outbound-backpressure", "write-buffer-water-mark", "executor-queue", "work", "advertise-port",
				"deregister-grace");
		int port = options.intValue("port");
		int ioThreads = options.has("io-threads") ? options.intValue("io-threads") : 0;
		ExecutorConfig executor = executor(options.value("executor", BenchServer.EXECUTOR_FIXED),
				options.has("executor-queue") ? options.intValue("executor-queue") : null);
		long workNanos = 0;
		if (options.has("work")) {
			try {
				workNanos = Durations.parse(options.value("work", null)).toNanos();
			} catch (IllegalArgumentException e) {
				throw new UsageException("--work: " + e.getMessage());
			}
		}
		Integer advertisePort = options.has("advertise-port") ? options.intValue("advertise-port") : null;
		java.time.Duration deregisterGrace = null;
		if (options.has("deregister-grace")) {
			String text = options.value("deregister-grace", null).trim();
			try {
				deregisterGrace = text.equals("0") ? java.time.Duration.ZERO : Durations.parse(text);
			} catch (IllegalArgumentException e) {
				throw new UsageException("--deregister-grace: " + e.getMessage());
			}
		}
		boolean flushConsolidation = onOff("flush-consolidation", options.value("flush-consolidation", "on"));
		boolean outboundBackpressure = onOff("outbound-backpressure", options.value("outbound-backpressure", "on"));
		int[] waterMark = waterMark(options.value("write-buffer-water-mark", null));
		Integer metricsPort = options.has("metrics-port") ? options.intValue("metrics-port") : null;
		Integer httpPort = options.has("http-port") ? options.intValue("http-port") : null;
		String host = options.value("host", null);
		boolean noRegistry = options.flag("no-registry");
		String registryUri = options.value("registry", null);
		if (noRegistry == (registryUri != null)) {
			throw new UsageException("server needs exactly one of --registry zk://host:port or --no-registry");
		}
		RegistryConfig registry = registryUri == null ? null
				: RegistryConfig.zookeeper(CallTarget.Endpoint.registry(registryUri).address());
		String serializer = options.value("serializer", "protobuf");
		MeterRegistry metrics = metricsPort == null ? null : PrometheusEndpoint.newRegistry();
		BenchServer server;
		try {
			server = BenchServer.start(port, host, registry, serializer, metrics, ioThreads, executor, flushConsolidation,
					outboundBackpressure, waterMark, advertisePort, workNanos, deregisterGrace);
		} catch (IllegalArgumentException e) {
			throw new UsageException(e.getMessage());
		}
		PrometheusEndpoint endpoint = metrics == null ? null : startEndpoint(metricsPort, metrics, server::close);
		if (endpoint != null) {
			out.println("bench-server metrics http://0.0.0.0:" + endpoint.port() + PrometheusEndpoint.PATH);
		}
		BenchHttpServer http = httpPort == null ? null : startHttp(httpPort, server, endpoint);
		if (http != null) {
			out.println("bench-server http http://0.0.0.0:" + http.port() + BenchHttpServer.ECHO_PATH + " (POST echo; GET "
					+ BenchHttpServer.PING_PATH + ", GET " + BenchHttpServer.ENVIRONMENT_PATH + ")");
		}
		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			out.println("bench-server stopping");
			if (http != null) {
				http.close();
			}
			server.close();
			if (endpoint != null) {
				endpoint.close();
			}
			out.println("bench-server stopped");
			out.flush();
			stopped.countDown();
		}, "bench-server-shutdown"));
		out.println("bench-server ready port=" + server.port() + " pid=" + ProcessHandle.current().pid()
				+ (registry == null ? " registry=none" : " registry=zk://" + registry.address()) + " serializer=" + serializer
				+ (http == null ? "" : " http_port=" + http.port()) + " transport=rpc io_threads=" + server.environment().get("server_io_threads")
				+ " executor=" + server.environment().get("server_executor_kind") + " flush_consolidation="
				+ server.environment().get("server_flush_consolidation") + " outbound_backpressure="
				+ server.environment().get("server_outbound_backpressure") + " write_buffer_water_mark="
				+ server.environment().get("server_write_buffer_water_mark")
				+ (server.environment().containsKey("server_executor_queue_capacity")
						? " executor_queue=" + server.environment().get("server_executor_queue_capacity") : "")
				+ " work_ms=" + server.environment().get("server_work_ms")
				+ " deregister_grace_ms=" + server.environment().get("server_deregister_grace_ms")
				+ (advertisePort == null ? "" : " advertised_port=" + advertisePort));
		out.flush();
		stopped.await();
		return OK;
	}

	/**
	 * {@code server --executor fixed|virtual|direct}：fixed = {@code ExecutorConfig.defaults()}（框架缺省，不改变"开箱即用"口径）；
	 * virtual = {@code ExecutorConfig.virtualThreads()}；direct = {@code ExecutorConfig.direct()}（opt-in，业务方法在 IO 线程上执行，
	 * 结果要标"非缺省"）。
	 */
	static ExecutorConfig executor(String kind) {
		return executor(kind, null);
	}

	/**
	 * @param queueCapacity {@code server --executor-queue <n>}：固定池的排队上限（只配 fixed）；null = 框架缺省
	 *                      {@code ExecutorConfig.DEFAULT_QUEUE_CAPACITY}，线程数仍是框架缺省（2 × 可用核数）
	 */
	static ExecutorConfig executor(String kind, Integer queueCapacity) {
		if (queueCapacity != null) {
			if (!BenchServer.EXECUTOR_FIXED.equals(kind.trim())) {
				throw new UsageException("--executor-queue only applies to --executor fixed");
			}
			if (queueCapacity < 0) {
				throw new UsageException("--executor-queue must be >= 0, got " + queueCapacity);
			}
			return ExecutorConfig.fixed(((ExecutorConfig.Fixed) ExecutorConfig.defaults()).threads(), queueCapacity);
		}
		return switch (kind.trim()) {
			case BenchServer.EXECUTOR_FIXED -> ExecutorConfig.defaults();
			case BenchServer.EXECUTOR_VIRTUAL -> ExecutorConfig.virtualThreads();
			case BenchServer.EXECUTOR_DIRECT -> ExecutorConfig.direct();
			default -> throw new UsageException("--executor must be fixed, virtual or direct, got \"" + kind + "\"");
		};
	}

	/**
	 * {@code server --write-buffer-water-mark <low>:<high>}（字节，可带 K / M 后缀，如 {@code 256K:1M}）：
	 * {@code RpcServerConfig.writeBufferWaterMark}；不给 = 框架缺省（null）。
	 */
	static int[] waterMark(String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.trim().split(":");
		if (parts.length != 2) {
			throw new UsageException("--write-buffer-water-mark must be <low>:<high> (bytes, K/M suffix allowed), got \"" + value + "\"");
		}
		return new int[] { size(parts[0], value), size(parts[1], value) };
	}

	private static int size(String text, String whole) {
		String t = text.trim().toUpperCase(java.util.Locale.ROOT);
		long multiplier = 1;
		if (t.endsWith("K")) {
			multiplier = 1024;
			t = t.substring(0, t.length() - 1);
		} else if (t.endsWith("M")) {
			multiplier = 1024 * 1024;
			t = t.substring(0, t.length() - 1);
		}
		try {
			long bytes = Long.parseLong(t) * multiplier;
			if (bytes < 0 || bytes > Integer.MAX_VALUE) {
				throw new NumberFormatException();
			}
			return (int) bytes;
		} catch (NumberFormatException e) {
			throw new UsageException("--write-buffer-water-mark: \"" + text + "\" is not a size in bytes (K/M suffix allowed) in \"" + whole + "\"");
		}
	}

	private static boolean onOff(String option, String value) {
		return switch (value.trim()) {
			case "on" -> true;
			case "off" -> false;
			default -> throw new UsageException("--" + option + " must be on or off, got \"" + value + "\"");
		};
	}

	/**
	 * {@code server --transport <name> ...}：对比对象的服务端。除 {@code --transport <name>} 之外的参数原样交给该 transport 的
	 * {@link ServerLauncher}（它打印就绪行、阻塞到关闭）。
	 */
	private static int pluginServer(String transport, String[] rawArgs, PrintStream out) throws Exception {
		CallTargetProvider provider;
		try {
			provider = CallTargets.provider(transport);
		} catch (IllegalArgumentException e) {
			throw new UsageException(e.getMessage());
		}
		ServerLauncher launcher = provider.serverLauncher()
				.orElseThrow(() -> new UsageException("transport \"" + transport + "\" has no bench server (CallTargetProvider.serverLauncher() is empty)"));
		List<String> rest = new ArrayList<>();
		for (int i = 1; i < rawArgs.length; i++) {
			if (rawArgs[i].equals("--transport")) {
				i++;
				continue;
			}
			rest.add(rawArgs[i]);
		}
		return launcher.run(rest.toArray(new String[0]), out);
	}

	private static int run(Options options, String[] rawArgs, PrintStream out) throws Exception {
		options.allowOnly("profile", "out", "registry", "direct", "label", "metrics-port", "open-workers", "http-client", "io-threads", "io-busy-poll",
				"pin-dispatcher", "pre-warmup", "flush-consolidation", "loadbalance", "cluster", "retries", "fault-schedule", "no-faults",
				"fault-variant", "topology");
		HarnessOptions harnessOptions;
		try {
			harnessOptions = HarnessOptions.parse(options.value("open-workers", null), options.value("http-client", null),
					options.value("io-threads", null),
					options.value("io-busy-poll", null), options.value("pin-dispatcher", null), options.value("pre-warmup", null))
					.withFlushConsolidation(options.value("flush-consolidation", null))
					.withCluster(options.value("loadbalance", null), options.value("cluster", null), options.value("retries", null));
		} catch (IllegalArgumentException e) {
			throw new UsageException(e.getMessage());
		}
		Integer metricsPort = options.has("metrics-port") ? options.intValue("metrics-port") : null;
		Path profileFile = Path.of(options.required("profile"));
		String registry = options.value("registry", null);
		String direct = options.value("direct", null);
		if (registry != null && direct != null || registry == null && direct == null && !isHttpCard(profileFile)) {
			throw new UsageException("run needs exactly one of --registry zk://host:port or --direct host:port"
					+ " (cards with transport: http take neither: the target is target.http.url)");
		}
		Profile profile = ProfileLoader.load(profileFile);
		if (harnessOptions.flushConsolidation() != null && !profile.target().rpc()) {
			throw new UsageException("--flush-consolidation is RpcClientConfig.flushConsolidation and applies to transport: rpc cards only");
		}
		if (harnessOptions.clusterOptionsGiven()) {
			if (!profile.target().rpc()) {
				throw new UsageException("--loadbalance / --cluster / --retries apply to transport: rpc cards only");
			}
			try {
				validateClusterNames(harnessOptions);
				ClusterChoice.resolve(profile, harnessOptions);
			} catch (IllegalArgumentException e) {
				throw new UsageException(e.getMessage());
			}
		}
		FaultPlan plan;
		try {
			plan = FaultPlan.of(profile, options.has("fault-schedule") ? Path.of(options.value("fault-schedule", null)) : null,
					options.flag("no-faults"), options.value("fault-variant", null),
					options.has("topology") ? Path.of(options.value("topology", null)) : null);
		} catch (IllegalArgumentException e) {
			throw new UsageException(e.getMessage());
		}
		CallTarget.Endpoint endpoint;
		if (profile.target().http()) {
			if (registry != null || direct != null) {
				throw new UsageException("transport: http cards are addressed by target.http.url; drop --registry / --direct");
			}
			if (metricsPort != null) {
				throw new UsageException("--metrics-port instruments the RPC client and does not apply to transport: http");
			}
			endpoint = CallTarget.Endpoint.http(profile.target().httpUrl());
		} else {
			endpoint = registry != null ? CallTarget.Endpoint.registry(registry) : CallTarget.Endpoint.direct(direct);
			if (harnessOptions.httpOnlyOptionsGiven()) {
				throw new UsageException("--http-client / --io-busy-poll apply to transport: http cards only"
						+ " (--io-threads applies to every transport: RpcClientConfig.ioThreads for rpc cards)");
			}
			if (profile.target().plugin()) {
				if (registry != null) {
					throw new UsageException("transport: " + profile.target().transport()
							+ " cards are addressed with --direct host:port (comparison targets do not use a registry)");
				}
				if (metricsPort != null) {
					throw new UsageException("--metrics-port instruments the RPC client and does not apply to transport: "
							+ profile.target().transport());
				}
			}
		}
		Path outDir = options.value("out", null) != null ? Path.of(options.value("out", null)) : defaultArchiveDir();
		List<String> commandLine = new ArrayList<>(List.of("rpc-bench-e2e"));
		commandLine.addAll(List.of(rawArgs));
		out.println("profile " + profile.id() + " (" + profileFile + ") -> " + outDir.toAbsolutePath().normalize());
		MeterRegistry metrics = metricsPort == null ? null : PrometheusEndpoint.newRegistry();
		BenchRunner.Outcome outcome;
		try (PrometheusEndpoint metricsEndpoint = metrics == null ? null : startEndpoint(metricsPort, metrics, () -> { })) {
			if (metricsEndpoint != null) {
				out.println("metrics http://0.0.0.0:" + metricsEndpoint.port() + PrometheusEndpoint.PATH
						+ " (metrics filter on: say so in --label)");
			}
			outcome = BenchRunner.run(profile, endpoint, outDir, options.value("label", null), commandLine, out, metrics, harnessOptions, plan);
		}
		out.println("json: " + outcome.json().toAbsolutePath().normalize());
		out.println("markdown: " + outcome.markdown().toAbsolutePath().normalize());
		Map<?, ?> validity = (Map<?, ?>) outcome.result().get("validity");
		out.println("verdict: " + (outcome.exitCode() == OK ? "PASSED" : "FAILED") + " (all_rounds_valid="
				+ validity.get("all_rounds_valid") + ", asserts_passed=" + validity.get("asserts_passed") + ")");
		return outcome.exitCode();
	}

	/** {@code --loadbalance} / {@code --cluster} 的名字必须是 classpath 上的 SPI 扩展（{@code rr} 是 roundrobin 的简写）。 */
	private static void validateClusterNames(HarnessOptions options) {
		String lb = ClusterChoice.loadBalanceName(options.loadBalance());
		Set<String> lbs = com.feynman.rpc.core.extension.ExtensionLoader.getExtensionLoader(com.feynman.rpc.core.loadbalance.LoadBalance.class)
				.getSupportedExtensions();
		if (lb != null && !lbs.contains(lb)) {
			throw new IllegalArgumentException("--loadbalance must be rr or one of " + new java.util.TreeSet<>(lbs) + ", got \"" + lb + "\"");
		}
		Set<String> clusters = com.feynman.rpc.core.extension.ExtensionLoader.getExtensionLoader(com.feynman.rpc.core.cluster.Cluster.class)
				.getSupportedExtensions();
		if (options.cluster() != null && !clusters.contains(options.cluster())) {
			throw new IllegalArgumentException("--cluster must be one of " + new java.util.TreeSet<>(clusters) + ", got \"" + options.cluster() + "\"");
		}
	}

	/**
	 * 没给 --registry / --direct 时先看一眼卡片是不是 http 卡（读不了或不合法时返回 false）：rpc 卡漏写目标仍然是用法错误，
	 * 卡片本身的问题由随后的正式加载完整报出。
	 */
	private static boolean isHttpCard(Path profileFile) {
		try {
			return ProfileLoader.load(profileFile).target().http();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** 起 HTTP 对照端点；失败时先关掉已经起来的 RPC 服务端与指标端点再抛出。 */
	private static BenchHttpServer startHttp(int port, BenchServer server, PrometheusEndpoint endpoint) throws InterruptedException {
		try {
			Map<String, String> environment = new LinkedHashMap<>(server.environment());
			// HTTP 路径上没有任何指标 Filter：这里的 metrics_enabled 说的是被压的这条路径，与 RPC 端点的 --metrics-port 无关
			environment.put("metrics_enabled", "false");
			environment.put("endpoint", "http");
			return BenchHttpServer.start(port, environment, server.statsSupplier(), server.countersSupplier());
		} catch (InterruptedException | RuntimeException e) {
			server.close();
			if (endpoint != null) {
				endpoint.close();
			}
			throw e;
		}
	}

	/** 起内嵌抓取端点；绑定失败时先执行 {@code onFailure}（例如关掉已经起来的服务端）再抛出。 */
	private static PrometheusEndpoint startEndpoint(int port, MeterRegistry metrics, Runnable onFailure) {
		try {
			return new PrometheusEndpoint(port, metrics);
		} catch (RuntimeException e) {
			onFailure.run();
			throw e;
		}
	}

	/** 归档目录约定 {@code bench/reports/<yyyyMMdd>-<sha7>/}（相对当前目录）；没有 git 时 sha 部分为 {@code nogit}。 */
	static Path defaultArchiveDir() {
		Object sha = EnvironmentProbe.git().get("sha");
		String sha7 = sha instanceof String s && s.length() >= 7 && !s.equals(EnvironmentProbe.UNKNOWN) ? s.substring(0, 7) : "nogit";
		return Path.of("bench", "reports", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + sha7);
	}

	/** {@code --in} 的目录（递归）或单个文件下的全部 {@code .json}。 */
	private static List<Path> jsonFiles(Path in) throws IOException {
		List<Path> files = new ArrayList<>();
		if (Files.isDirectory(in)) {
			try (Stream<Path> walk = Files.walk(in)) {
				walk.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted().forEach(files::add);
			}
		} else if (Files.isRegularFile(in)) {
			files.add(in);
		} else {
			throw new UsageException("--in does not exist: " + in);
		}
		return files;
	}

	/**
	 * {@code compare}：把同一负载模型 / 负载大小 / 时长口径、不同 transport（或不同并发、速率、IO 线程数）的结果并排成表
	 * （{@link CompareReport}）。只读 schema_version 1 的压测结果，交叉验证结果与读不了的文件跳过。
	 */
	private static int compare(Options options, PrintStream out, PrintStream err) throws IOException {
		options.allowOnly("in", "out");
		List<Path> files = jsonFiles(Path.of(options.required("in")));
		List<Map.Entry<String, Map<?, ?>>> results = new ArrayList<>();
		int skipped = 0;
		for (Path file : files) {
			Object parsed;
			try {
				parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
			} catch (RuntimeException e) {
				err.println("skip " + file + ": not readable JSON (" + e.getMessage() + ")");
				skipped++;
				continue;
			}
			if (!(parsed instanceof Map<?, ?> json) || !Integer.valueOf(BenchRunner.SCHEMA_VERSION).equals(json.get("schema_version"))) {
				skipped++;
				continue;
			}
			results.add(Map.entry(file.getFileName().toString(), json));
		}
		if (results.isEmpty()) {
			throw new UsageException("no bench result JSON found under " + options.required("in"));
		}
		results.sort(Comparator.comparing((Map.Entry<String, Map<?, ?>> e) -> String.valueOf(MarkdownReport.path(e.getValue(), "environment", "started_at"))));
		String markdown = CompareReport.document(results);
		String target = options.value("out", null);
		if (target == null) {
			out.print(markdown);
		} else {
			Path outFile = Path.of(target);
			if (outFile.toAbsolutePath().getParent() != null) {
				Files.createDirectories(outFile.toAbsolutePath().getParent());
			}
			Files.writeString(outFile, markdown, StandardCharsets.UTF_8);
			out.println("compare: " + outFile.toAbsolutePath().normalize() + " (" + results.size() + " result(s), " + skipped + " skipped)");
		}
		return OK;
	}

	private static int report(Options options, PrintStream out, PrintStream err) throws IOException {
		options.allowOnly("in", "out");
		Path in = Path.of(options.required("in"));
		List<Path> files = jsonFiles(in);
		Map<String, List<Map.Entry<String, Map<?, ?>>>> byProfile = new TreeMap<>();
		List<Map.Entry<String, Map<?, ?>>> crossval = new ArrayList<>();
		int skipped = 0;
		for (Path file : files) {
			Object parsed;
			try {
				parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
			} catch (RuntimeException e) {
				err.println("skip " + file + ": not readable JSON (" + e.getMessage() + ")");
				skipped++;
				continue;
			}
			if (parsed instanceof Map<?, ?> cv && cv.get("schema") instanceof String schema && schema.startsWith("crossval/")) {
				// 交叉验证结果（crossval 子命令写出）：汇总进"交叉验证判定"一节，作为该 sha 下 harness 可信度的证据
				crossval.add(Map.entry(file.getFileName().toString(), cv));
				continue;
			}
			if (!(parsed instanceof Map<?, ?> json) || !Integer.valueOf(BenchRunner.SCHEMA_VERSION).equals(json.get("schema_version"))) {
				err.println("skip " + file + ": not a schema_version " + BenchRunner.SCHEMA_VERSION + " bench result");
				skipped++;
				continue;
			}
			String id = String.valueOf(MarkdownReport.path(json, "profile", "id"));
			byProfile.computeIfAbsent(id, k -> new ArrayList<>()).add(Map.entry(file.getFileName().toString(), json));
		}
		if (byProfile.isEmpty() && crossval.isEmpty()) {
			throw new UsageException("no bench result JSON found under " + in);
		}
		byProfile.values().forEach(runs -> runs.sort(Comparator.comparing(
				(Map.Entry<String, Map<?, ?>> e) -> String.valueOf(MarkdownReport.path(e.getValue(), "environment", "started_at")))));
		crossval.sort(Comparator.comparing((Map.Entry<String, Map<?, ?>> e) -> String.valueOf(e.getValue().get("created_at"))));
		List<Map.Entry<String, Map<?, ?>>> all = new ArrayList<>();
		byProfile.values().forEach(all::addAll);
		all.sort(Comparator.comparing((Map.Entry<String, Map<?, ?>> e) -> String.valueOf(MarkdownReport.path(e.getValue(), "environment", "started_at"))));
		String markdown = MarkdownReport.renderSummary(new LinkedHashMap<>(byProfile), crossval, CompareReport.render(all, 3));
		String target = options.value("out", null);
		if (target == null) {
			out.print(markdown);
		} else {
			Path outFile = Path.of(target);
			if (outFile.toAbsolutePath().getParent() != null) {
				Files.createDirectories(outFile.toAbsolutePath().getParent());
			}
			Files.writeString(outFile, markdown, StandardCharsets.UTF_8);
			out.println("report: " + outFile.toAbsolutePath().normalize() + " (" + byProfile.size() + " profile(s), "
					+ crossval.size() + " crossval result(s), " + files.size() + " file(s), " + skipped + " skipped)");
		}
		return OK;
	}

	private static void usage(PrintStream out) {
		out.println("""
				usage:
				  server --port <p> [--host <advertised-host>] (--registry zk://host:port | --no-registry) [--serializer protobuf]
				         [--metrics-port <p>] [--http-port <p>] [--io-threads <n>] [--executor fixed|virtual|direct]
				         [--flush-consolidation on|off] [--outbound-backpressure on|off] [--write-buffer-water-mark <low>:<high>]
				         [--executor-queue <n>] [--work <duration>] [--advertise-port <p>] [--deregister-grace <duration>|0]
				                                       (fault-injection experiments)
				  server --transport <name> --port <p> ...   (comparison server from a CallTargetProvider on the classpath)
				  run    --profile <card.yaml> (--registry zk://host:port | --direct host:port[,host:port]) [--out <dir>] [--label <text>]
				         [--metrics-port <p>]          (transport: http cards take neither --registry nor --direct)
				         [--open-workers per-request|pool[:N]|pool-platform[:N]] [--http-client netty|blocking] [--io-threads <n>]
				         [--io-busy-poll on|off] [--pin-dispatcher <cpu>|off] [--pre-warmup <duration>|0]
				         [--flush-consolidation on|off]   (rpc cards only: RpcClientConfig.flushConsolidation)
				         [--loadbalance rr|p2c] [--cluster failfast|failover|failback] [--retries N]   (rpc cards; the card's
				                                       target.load_balance / cluster / retries win, a different value is an error)
				         [--fault-schedule <file> | --no-faults] [--fault-variant sigterm|sigstop|toxic-timeout] [--topology <file>]
				                                       (cards with load.faults; driven by bench/bin/fault-run.sh)
				  report --in <dir|result.json> [--out <file.md>]
				  compare --in <dir|result.json> [--out <file.md>]
				  crossval --harness <result.json> --tool wrk|wrk2 --tool-output <file>[,<file>...] [--tool-command <text>] --out <dir>
				exit codes: 0 ok, 1 usage / invalid profile / runtime error, 2 experiment failed (assert or invalid round)""");
	}

	static final class UsageException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		UsageException(String message) {
			super(message);
		}
	}

	/** {@code --key value} 与 {@code --flag}；同一个键出现两次视为错误。 */
	static final class Options {
		private static final Set<String> FLAGS = Set.of("no-registry", "no-faults");
		private final Map<String, String> values = new HashMap<>();

		static Options parse(String[] args, int from) {
			Options options = new Options();
			for (int i = from; i < args.length; i++) {
				String arg = args[i];
				if (!arg.startsWith("--") || arg.length() == 2) {
					throw new UsageException("unexpected argument \"" + arg + "\"");
				}
				String key = arg.substring(2);
				String value;
				if (FLAGS.contains(key)) {
					value = "true";
				} else {
					if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
						throw new UsageException("--" + key + " needs a value");
					}
					value = args[++i];
				}
				if (options.values.put(key, value) != null) {
					throw new UsageException("--" + key + " given twice");
				}
			}
			return options;
		}

		void allowOnly(String... keys) {
			Set<String> allowed = Set.of(keys);
			for (String key : values.keySet()) {
				if (!allowed.contains(key)) {
					throw new UsageException("unknown option --" + key);
				}
			}
		}

		String value(String key, String defaultValue) {
			return values.getOrDefault(key, defaultValue);
		}

		String required(String key) {
			String value = values.get(key);
			if (value == null) {
				throw new UsageException("--" + key + " is required");
			}
			return value;
		}

		int intValue(String key) {
			String value = required(key);
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e) {
				throw new UsageException("--" + key + " must be an integer, got \"" + value + "\"");
			}
		}

		boolean flag(String key) {
			return values.containsKey(key);
		}

		boolean has(String key) {
			return values.containsKey(key);
		}
	}
}
