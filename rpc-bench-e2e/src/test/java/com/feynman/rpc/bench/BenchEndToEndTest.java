package com.feynman.rpc.bench;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.feynman.rpc.core.registry.RegistryConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线端到端：进程内起 bench server，用 {@link Bench#execute} 跑临时口径卡（与命令行同一入口，只是不 System.exit），
 * 检查 JSON 的结构与数字、Markdown 生成，以及"实验必须能失败"：断言不满足时退出码 2，validity 里逐条记录失败项。
 */
class BenchEndToEndTest {

	private static BenchServer server;

	@BeforeAll
	static void startServer() throws Exception {
		server = BenchServer.start(0, "127.0.0.1", null, "protobuf");
	}

	@AfterAll
	static void stopServer() {
		server.close();
	}

	record Result(int code, String out, String err, Map<?, ?> json, Path markdown) {
	}

	private static String card(String id, String model, String loadLine, String duration, String warmup, String window, int rounds,
			String timeout, String payload, String asserts) {
		return """
				profile: %s
				question: 端到端测试用的临时卡
				target:
				  transport: rpc
				  service: BenchService#%s
				  payload: %s
				  serializer: protobuf
				  providers: 1
				load:
				  model: %s
				  %s
				  duration: %s
				  warmup: %s
				  steady_window: %s
				  rounds: %d
				  timeout: %s
				isolation:
				  client_process: same_jvm
				  server_process: same_jvm
				  same_host: true
				  registry: none (in-process test)
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p95, p99, p999, max, errors_by_status]
				%s
				""".formatted(id, payload.contains("order") ? "echoOrder" : "echo", payload, model, loadLine, duration, warmup, window,
				rounds, timeout, asserts);
	}

	private static Result run(Path dir, String id, String yaml, String... target) throws Exception {
		Path card = dir.resolve(id + ".yaml");
		Files.writeString(card, yaml, StandardCharsets.UTF_8);
		Path out = dir.resolve("out-" + id);
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		String[] args = Stream.concat(Stream.of("run", "--profile", card.toString(), "--out", out.toString(), "--label", "it"),
				Stream.of(target)).toArray(String[]::new);
		int code = Bench.execute(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stderr, true, StandardCharsets.UTF_8));
		String o = stdout.toString(StandardCharsets.UTF_8);
		String e = stderr.toString(StandardCharsets.UTF_8);
		System.out.println(o);
		System.err.println(e);
		List<Path> jsons;
		List<Path> mds;
		try (Stream<Path> files = Files.list(out)) {
			List<Path> all = files.toList();
			jsons = all.stream().filter(p -> p.toString().endsWith(".json")).toList();
			mds = all.stream().filter(p -> p.toString().endsWith(".md")).toList();
		}
		assertEquals(1, jsons.size(), "one JSON per run: " + jsons);
		assertEquals(1, mds.size(), "one Markdown per run: " + mds);
		String jsonName = jsons.get(0).getFileName().toString();
		assertTrue(jsonName.matches(id + "-\\d{8}-\\d{6}\\.json"), jsonName);
		assertEquals(jsonName.replace(".json", ".md"), mds.get(0).getFileName().toString());
		Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(jsons.get(0), StandardCharsets.UTF_8));
		return new Result(code, o, e, json, mds.get(0));
	}

	private static Object path(Map<?, ?> json, String... keys) {
		return MarkdownReport.path(json, keys);
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	@Test
	void closedLoopDirectRunProducesCompleteJsonAndMarkdown(@TempDir Path dir) throws Exception {
		String id = "it-closed";
		Result r = run(dir, id, card(id, "closed", "concurrency: 8", "3s", "1s", "[1s, 2.5s]", 2, "1s",
				"{ kind: bytes, size: 1024 }", "assert:\n  errors_max: 0"), "--direct", "127.0.0.1:" + server.port());
		assertEquals(Bench.OK, r.code(), r.err());
		Map<?, ?> json = r.json();

		assertEquals(1, json.get("schema_version"));
		assertEquals("it", json.get("label"));
		for (String key : List.of("profile", "run", "environment", "rounds", "summary", "validity")) {
			assertNotNull(json.get(key), "missing top-level " + key);
		}
		assertEquals(id, path(json, "profile", "id"));
		assertTrue(String.valueOf(path(json, "profile", "raw_yaml")).contains("profile: " + id));
		assertEquals("closed", path(json, "profile", "parsed", "load", "model"));
		for (String key : List.of("os_name", "wsl", "cpu_model", "available_cpus", "host_cpus", "total_memory_bytes", "jdk_version", "jdk_vendor",
				"jvm_args", "netty_version", "git_sha", "git_dirty", "hostname", "power_supply", "cpu_governor", "started_at",
				"ended_at", "harness_version")) {
			assertTrue(((Map<?, ?>) json.get("environment")).containsKey(key), "environment." + key);
		}
		assertEquals("0.0.1-SNAPSHOT", path(json, "environment", "harness_version"));
		assertTrue(((Map<?, ?>) json.get("environment")).containsKey("cpus_allowed"));
		// 服务端环境：预热前经 environment() 取一次，进程内服务端与压测端是同一个 pid
		Map<?, ?> serverEnv = (Map<?, ?>) json.get("server_environment");
		assertNotNull(serverEnv, "server_environment must be collected");
		for (String key : List.of("jvm_args", "cpus_allowed", "pid", "hostname")) {
			assertTrue(serverEnv.containsKey(key), "server_environment." + key);
		}
		assertEquals(String.valueOf(ProcessHandle.current().pid()), String.valueOf(serverEnv.get("pid")));

		List<?> rounds = (List<?>) json.get("rounds");
		assertEquals(2, rounds.size());
		for (Object o : rounds) {
			Map<?, ?> round = (Map<?, ?>) o;
			assertTrue(number(path(round, "window", "requests")) > 0, "window requests > 0");
			assertEquals(0L, number(path(round, "window", "errors")));
			assertTrue(((Number) round.get("throughput_rps")).doubleValue() > 0);
			assertNotNull(path(round, "latency_ms", "p99"));
			assertEquals(Boolean.TRUE, round.get("valid"));
			assertEquals(Boolean.TRUE, round.get("warmup_completed"));
			// 统计窗口的 CPU 采样：Linux 上是两组核的忙碌率，其它平台 n/a
			if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
				assertTrue(path(round, "cpu", "client_busy_pct") instanceof Number, "rounds[].cpu on Linux: " + round.get("cpu"));
			} else {
				assertEquals(EnvironmentProbe.NA, round.get("cpu"));
			}
			// 统计窗口的 GC：压测端本进程的 MXBean 差值 + 服务端 stats() 的差值（进程内服务端，同一个 JVM）
			for (String side : List.of("client", "server")) {
				assertTrue(path(round, "gc", side, "pause_ms") instanceof Number, "rounds[].gc." + side + ": " + round.get("gc"));
				assertTrue(path(round, "gc", side, "collections") instanceof Number);
				assertTrue(path(round, "gc", side, "collectors") instanceof List<?>);
			}
			List<?> success = (List<?>) path(round, "timeseries", "success");
			assertTrue(success.size() >= 3, "per-second series covers the round");
			assertTrue(number(success.get(0)) > 0 && number(success.get(2)) > 0);
		}
		Object official = path(json, "summary", "official_round");
		assertTrue(official.equals(1) || official.equals(2));
		assertNotNull(path(json, "summary", "spread", "p99_ms", "range"));
		assertEquals(Boolean.TRUE, path(json, "validity", "passed"));
		assertTrue(String.valueOf(path(json, "validity", "statements")).contains("闭环"), "closed-loop disclaimer is attached");

		String md = Files.readString(r.markdown(), StandardCharsets.UTF_8);
		assertTrue(md.startsWith("# " + id + "\n"));
		assertTrue(md.contains("（正式）"));
		assertTrue(md.contains("不可外推"));
		assertTrue(md.contains("| GC 停顿 客户端/服务端 (ms) |"), md);
		assertTrue(md.contains("| 服务端 JVM 参数 |") && md.contains("| 服务端 CPU 亲和 |") && md.contains("| CPU 亲和（压测端） |"));
		int cpus = Runtime.getRuntime().availableProcessors();
		Object host = path(json, "environment", "host_cpus");
		assertTrue(md.contains("| 可用核数 / 主机核数 | " + cpus + " / " + host + "（服务端 " + cpus + " / " + host + "） |"), md);
		assertTrue(md.contains("| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=false |"), md);
		assertFalse(md.contains(BenchRunner.OPEN_LOOP_BUSY_SPIN_STATEMENT), "busy-spin statement is for open-loop cards only");
		assertTrue(r.out().contains("verdict: PASSED"));
	}

	/** 实验必须能失败：1MiB 回显配 1ms 超时，错误必然出现，errors_max: 0 不满足 → 退出码 2，validity 记录失败项。 */
	@Test
	void anAssertionThatCannotHoldFailsTheRunWithExitCode2(@TempDir Path dir) throws Exception {
		String id = "it-must-fail";
		Result r = run(dir, id, card(id, "closed", "concurrency: 16", "2s", "500ms", "[500ms, 1.5s]", 1, "1ms",
				"{ kind: bytes, size: 1048576 }", "assert:\n  errors_max: 0"), "--direct", "127.0.0.1:" + server.port());
		assertEquals(Bench.FAILED_ASSERTIONS, r.code(), r.out() + r.err());
		assertEquals(Boolean.FALSE, path(r.json(), "validity", "passed"));
		List<?> asserts = (List<?>) path(r.json(), "validity", "asserts");
		assertEquals(1, asserts.size());
		Map<?, ?> errorsMax = (Map<?, ?>) asserts.get(0);
		assertEquals("errors_max", errorsMax.get("key"));
		assertEquals(Boolean.FALSE, errorsMax.get("passed"));
		assertTrue(number(errorsMax.get("actual_value")) > 0);
		Map<?, ?> round = (Map<?, ?>) ((List<?>) r.json().get("rounds")).get(0);
		assertTrue(((Map<?, ?>) round.get("errors_by_type")).containsKey(ErrorClassifier.TIMEOUT), "timeouts are bucketed: " + round);
		String md = Files.readString(r.markdown(), StandardCharsets.UTF_8);
		assertTrue(md.contains("**未通过**"));
		assertTrue(r.out().contains("verdict: FAILED"));
	}

	@Test
	void openLoopRunRecordsServiceTimeAndSchedulerInfo(@TempDir Path dir) throws Exception {
		String id = "it-open";
		Result r = run(dir, id, card(id, "open", "rate: 500", "2s", "500ms", "[500ms, 1.5s]", 1, "1s",
				"{ kind: order }", ""), "--direct", "127.0.0.1:" + server.port());
		assertEquals(Bench.OK, r.code(), r.err());
		Map<?, ?> round = (Map<?, ?>) ((List<?>) r.json().get("rounds")).get(0);
		long requests = number(path(round, "window", "requests"));
		assertTrue(requests >= 450 && requests <= 550, "about rate x window = 500 requests, got " + requests);
		assertNotNull(path(round, "service_time_ms", "p99"));
		assertNotNull(path(round, "dispatch_lag_ms", "p99"));
		assertEquals(1000L, number(path(round, "scheduler", "inflight_cap")), "rate x timeout x 2 = 500 x 1s x 2");
		assertEquals(0L, number(round.get("backlog_dropped")));
		assertFalse(String.valueOf(path(r.json(), "validity", "statements")).contains("闭环负载"));
		assertTrue(Files.readString(r.markdown(), StandardCharsets.UTF_8).contains(BenchRunner.OPEN_LOOP_BUSY_SPIN_STATEMENT),
				"open-loop cards declare the busy-spinning scheduler thread");
		assertEquals("echoOrder", path(r.json(), "profile", "parsed", "target", "method"));
	}

	/**
	 * 预热段（{@code --pre-warmup}，缺省 = 卡片的 warmup）：第 1 轮之前以同样负载跑一段，摘要进 {@code run.pre_warmup}，
	 * 不计入任何一轮——第 1 轮的发出数仍恰好是 rate × duration。{@code --pre-warmup 0} 关闭，记为 off。
	 */
	@Test
	void preWarmupRunsBeforeTheFirstRoundAndIsNotCounted(@TempDir Path dir) throws Exception {
		String id = "it-prewarm";
		String yaml = card(id, "open", "rate: 500", "2s", "500ms", "[500ms, 1.5s]", 1, "1s", "{ kind: bytes, size: 256 }", "");
		Result r = run(dir, id, yaml, "--direct", "127.0.0.1:" + server.port(), "--open-workers", "pool");
		assertEquals(Bench.OK, r.code(), r.err());
		assertEquals(500.0, ((Number) path(r.json(), "run", "harness_options", "pre_warmup_ms")).doubleValue());
		assertEquals("pool", path(r.json(), "run", "harness_options", "open_workers"));
		Map<?, ?> pre = (Map<?, ?>) path(r.json(), "run", "pre_warmup");
		assertEquals(250L, number(pre.get("issued")), "pre-warmup = rate x 500ms");
		Map<?, ?> round = (Map<?, ?>) ((List<?>) r.json().get("rounds")).get(0);
		assertEquals(1000L, number(round.get("issued")), "round 1 still issues exactly rate x duration: the segment is not counted");
		assertEquals(Boolean.FALSE, round.get("dispatcher_delayed"));
		String md = Files.readString(r.markdown(), StandardCharsets.UTF_8);
		assertTrue(md.contains("| 预热段 | 500ms（load.warmup）"), md);
		assertTrue(md.contains("| harness 选项 | 开环执行者 pool"), md);
		assertTrue(r.out().contains("pre-warmup: open for 500ms"), r.out());

		String offId = "it-prewarm-off";
		Result off = run(dir, offId, yaml.replace("profile: " + id, "profile: " + offId), "--direct", "127.0.0.1:" + server.port(),
				"--pre-warmup", "0");
		assertEquals(Bench.OK, off.code(), off.err());
		assertEquals("off", path(off.json(), "run", "pre_warmup"));
		assertEquals(0.0, ((Number) path(off.json(), "run", "harness_options", "pre_warmup_ms")).doubleValue());
		assertTrue(Files.readString(off.markdown(), StandardCharsets.UTF_8).contains("| 预热段 | 关闭（--pre-warmup = 0ms）"));
	}

	/**
	 * {@code --io-threads} 对 rpc 卡生效（传给 {@code RpcClientConfig.ioThreads}，如实记进 {@code run.harness_options} 与
	 * {@code run.target}）；{@code --http-client} / {@code --io-busy-poll} 仍然只对 http 卡有效，rpc 卡给了就是用法错误。
	 */
	@Test
	void ioThreadsAppliesToRpcCardsButHttpClientOptionsDoNot(@TempDir Path dir) throws Exception {
		String id = "it-io-threads";
		String yaml = card(id, "open", "rate: 100", "1s", "200ms", "[200ms, 1s]", 1, "1s", "{ kind: bytes, size: 16 }", "");
		Result r = run(dir, id, yaml, "--direct", "127.0.0.1:" + server.port(), "--io-threads", "2", "--pre-warmup", "0");
		assertEquals(Bench.OK, r.code(), r.err());
		assertEquals(2, number(path(r.json(), "run", "harness_options", "io_threads")));
		assertEquals("command line", path(r.json(), "run", "harness_options", "io_source"));
		assertEquals(2, number(path(r.json(), "run", "target", "io_threads")), "RpcClient was built with ioThreads = 2");
		assertTrue(Files.readString(r.markdown(), StandardCharsets.UTF_8).contains("客户端 IO 线程 2"));

		String defaultId = "it-io-threads-default";
		Result d = run(dir, defaultId, yaml.replace("profile: " + id, "profile: " + defaultId), "--direct", "127.0.0.1:" + server.port(),
				"--pre-warmup", "0");
		assertEquals(Bench.OK, d.code(), d.err());
		assertEquals(HarnessOptions.nettyDefaultIoThreadsText(), path(d.json(), "run", "harness_options", "io_threads"));
		assertEquals(HarnessOptions.nettyDefaultIoThreadsText(), path(d.json(), "run", "target", "io_threads"));

		Path card = dir.resolve(id + ".yaml");
		for (String[] httpOnly : List.of(new String[] { "--io-busy-poll", "on" }, new String[] { "--http-client", "netty" })) {
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int code = Bench.execute(new String[] { "run", "--profile", card.toString(), "--direct", "127.0.0.1:" + server.port(), httpOnly[0],
					httpOnly[1] }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
					new PrintStream(err, true, StandardCharsets.UTF_8));
			assertEquals(Bench.ERROR, code, httpOnly[0]);
			assertTrue(err.toString(StandardCharsets.UTF_8).contains("apply to transport: http cards only"), err.toString(StandardCharsets.UTF_8));
		}
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", card.toString(), "--direct", "127.0.0.1:1", "--open-workers", "x" },
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)));
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", card.toString(), "--direct", "127.0.0.1:1", "--io-threads", "0" },
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)),
				"--io-threads must be 1..1024");
	}

	/** {@code server --io-threads}：RpcServerConfig.ioThreads，实际值进服务端环境（对比报告的"服务端配置"列）。 */
	@Test
	void serverIoThreadsAndExecutorAreRecordedInTheServerEnvironment() throws Exception {
		try (BenchServer custom = BenchServer.start(0, "127.0.0.1", null, "protobuf", null, 3)) {
			assertEquals(3, custom.rpcServer().config().ioThreads());
			assertEquals("3", custom.environment().get("server_io_threads"));
			assertEquals("rpc", custom.environment().get("transport"));
			assertTrue(custom.environment().get("server_executor").startsWith("fixed pool ("), custom.environment().toString());
		}
		assertEquals(HarnessOptions.nettyDefaultIoThreadsText(), server.environment().get("server_io_threads"));
	}

	@Test
	void registryModeDiscoversTheProviderThroughZooKeeper(@TempDir Path dir) throws Exception {
		try (TestingServer zk = new TestingServer(true);
				BenchServer registered = BenchServer.start(0, "127.0.0.1", RegistryConfig.zookeeper(zk.getConnectString()), "protobuf")) {
			String id = "it-zk";
			Result r = run(dir, id, card(id, "closed", "concurrency: 4", "2s", "500ms", "[500ms, 1.5s]", 1, "1s",
					"{ kind: bytes, size: 256 }", ""), "--registry", "zk://" + zk.getConnectString());
			assertEquals(Bench.OK, r.code(), r.err());
			assertEquals("registry", path(r.json(), "run", "target", "mode"));
			assertEquals(List.of("127.0.0.1:" + registered.port()), path(r.json(), "run", "target", "providers"));
		}
	}

	@Test
	void usageAndProfileErrorsExitWith1(@TempDir Path dir) throws Exception {
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
		PrintStream o = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", "x.yaml" }, o, e), "needs --direct or --registry");
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", "x.yaml", "--direct", "nohost" }, o, e));
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "frobnicate" }, o, e));
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "server", "--port", "0" }, o, e), "needs --registry or --no-registry");

		Path httpCard = dir.resolve("http-card.yaml");
		Files.writeString(httpCard, ProfileLoaderTest.HTTP_CARD);
		err.reset();
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", httpCard.toString(), "--direct", "127.0.0.1:1" }, o, e));
		assertTrue(err.toString(StandardCharsets.UTF_8).contains("drop --registry / --direct"), err.toString(StandardCharsets.UTF_8));
		err.reset();
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", httpCard.toString(), "--metrics-port", "0" }, o, e));
		assertTrue(err.toString(StandardCharsets.UTF_8).contains("does not apply to transport: http"), err.toString(StandardCharsets.UTF_8));

		Path card = dir.resolve("mismatch.yaml");
		Files.writeString(card, card("not-the-file-name", "closed", "concurrency: 1", "2s", "500ms", "[500ms, 1.5s]", 1, "1s",
				"{ kind: bytes, size: 1 }", ""));
		err.reset();
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", card.toString(), "--direct", "127.0.0.1:1" }, o, e));
		assertTrue(err.toString(StandardCharsets.UTF_8).contains("must equal the file name"), err.toString(StandardCharsets.UTF_8));
	}
}
