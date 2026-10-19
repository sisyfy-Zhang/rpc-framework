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

import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.metrics.micrometer.PrometheusEndpoint;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 命令行入口。子命令：
 * <pre>
 *   server --port &lt;p&gt; [--host &lt;h&gt;] [--registry zk://host:port | --no-registry] [--serializer protobuf] [--metrics-port &lt;p&gt;]
 *          [--http-port &lt;p&gt;]
 *   run    --profile &lt;file.yaml&gt; (--registry zk://host:port | --direct host:port[,host:port]) [--out &lt;dir&gt;] [--label &lt;text&gt;]
 *          [--metrics-port &lt;p&gt;]       （transport: http 的卡不带 --registry / --direct：目标是卡上的 target.http.url）
 *   report --in &lt;dir|file.json&gt; [--out &lt;file.md&gt;]   （压测结果 + crossval 结果 JSON 一起汇总）
 *   crossval --harness &lt;result.json&gt; --tool wrk|wrk2 --tool-output &lt;file&gt; [--tool-command &lt;text&gt;] --out &lt;dir&gt;
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

	/** 可在进程内调用（集成测试），不调用 System.exit。 */
	static int execute(String[] args, PrintStream out, PrintStream err) {
		if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
			usage(out);
			return args.length == 0 ? ERROR : OK;
		}
		String command = args[0];
		try {
			Options options = Options.parse(args, 1);
			return switch (command) {
				case "server" -> server(options, out);
				case "run" -> run(options, args, out);
				case "report" -> report(options, out, err);
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

	private static int server(Options options, PrintStream out) throws Exception {
		options.allowOnly("port", "host", "registry", "no-registry", "serializer", "metrics-port", "http-port");
		int port = options.intValue("port");
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
		BenchServer server = BenchServer.start(port, host, registry, serializer, metrics);
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
				+ (http == null ? "" : " http_port=" + http.port()));
		out.flush();
		stopped.await();
		return OK;
	}

	private static int run(Options options, String[] rawArgs, PrintStream out) throws Exception {
		options.allowOnly("profile", "out", "registry", "direct", "label", "metrics-port");
		Integer metricsPort = options.has("metrics-port") ? options.intValue("metrics-port") : null;
		Path profileFile = Path.of(options.required("profile"));
		String registry = options.value("registry", null);
		String direct = options.value("direct", null);
		if (registry != null && direct != null || registry == null && direct == null && !isHttpCard(profileFile)) {
			throw new UsageException("run needs exactly one of --registry zk://host:port or --direct host:port"
					+ " (cards with transport: http take neither: the target is target.http.url)");
		}
		Profile profile = ProfileLoader.load(profileFile);
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
			outcome = BenchRunner.run(profile, endpoint, outDir, options.value("label", null), commandLine, out, metrics);
		}
		out.println("json: " + outcome.json().toAbsolutePath().normalize());
		out.println("markdown: " + outcome.markdown().toAbsolutePath().normalize());
		Map<?, ?> validity = (Map<?, ?>) outcome.result().get("validity");
		out.println("verdict: " + (outcome.exitCode() == OK ? "PASSED" : "FAILED") + " (all_rounds_valid="
				+ validity.get("all_rounds_valid") + ", asserts_passed=" + validity.get("asserts_passed") + ")");
		return outcome.exitCode();
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
			return BenchHttpServer.start(port, environment);
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

	private static int report(Options options, PrintStream out, PrintStream err) throws IOException {
		options.allowOnly("in", "out");
		Path in = Path.of(options.required("in"));
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
		String markdown = MarkdownReport.renderSummary(new LinkedHashMap<>(byProfile), crossval);
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
				         [--metrics-port <p>] [--http-port <p>]
				  run    --profile <card.yaml> (--registry zk://host:port | --direct host:port[,host:port]) [--out <dir>] [--label <text>]
				         [--metrics-port <p>]          (transport: http cards take neither --registry nor --direct)
				  report --in <dir|result.json> [--out <file.md>]
				  crossval --harness <result.json> --tool wrk|wrk2 --tool-output <file> [--tool-command <text>] --out <dir>
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
		private static final Set<String> FLAGS = Set.of("no-registry");
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
