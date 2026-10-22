package com.feynman.rpc.bench;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.registry.RegistryConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障注入 harness 的端到端：阶梯与故障时间表走完整的 {@code run} 流水线（进程内 bench server，不依赖 toxiproxy / 进程信号）。
 * 故障由一个"假驱动"线程按时间表回写执行记录（它什么都不做，只验证时间表的输出与执行记录的读回），或者不回写（判定不通过），
 * 或者 {@code --no-faults}（对照）。另覆盖 {@code --loadbalance / --cluster / --retries}、服务端排队上限与业务等待的核对、
 * {@code server --advertise-port}（注册中心里公布另一个端口）。
 */
class FaultInjectionEndToEndTest {

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

	static String card(String id, String targetExtra, String loadBody, String duration, String window) {
		return """
				profile: %s
				question: 故障注入端到端测试用的临时卡
				target:
				  transport: rpc
				  service: BenchService#echo
				  payload: { kind: bytes, size: 256 }
				  serializer: protobuf
				  providers: 1
				%s
				load:
				  model: open
				%s
				  duration: %s
				  warmup: 500ms
				  steady_window: %s
				  rounds: 1
				  timeout: 1s
				isolation:
				  client_process: same_jvm
				  server_process: same_jvm
				  same_host: true
				  registry: none (in-process test)
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p99, errors_by_status]
				""".formatted(id, FaultCardSchemaTest.nest(targetExtra), FaultCardSchemaTest.nest(loadBody), duration, window);
	}

	/**
	 * 同 {@link #card}，但 {@code load.model: closed}（固定并发、没有发出计划）。闭环轮没有"发出滞后"这一项，也就不会因为
	 * {@code dispatcher_delayed}（调度线程被环境停顿推迟，CI runner 上偶发）被判无效；只关心核对逻辑、不关心开环轮次有效性的用例用它。
	 */
	static String closedCard(String id, String targetExtra, int concurrency, String duration, String window) {
		String open = card(id, targetExtra, "  concurrency: " + concurrency, duration, window);
		assertTrue(open.contains("\n  model: open\n"), open);
		return open.replace("\n  model: open\n", "\n  model: closed\n");
	}

	static final String STEPS_AND_FAULTS = """
			  steps: [{ rate: 200, duration: 1s }, { rate: 600, duration: 1s }, { rate: 200, duration: 1s }]
			  faults:
			    - { at: 1s, kind: toxic-latency, target: 1, latency: 5ms }
			    - { at: 2s, kind: remove-toxic, target: 1 }""";

	private static Result run(Path dir, String id, String yaml, String... args) throws Exception {
		Path card = dir.resolve(id + ".yaml");
		Files.writeString(card, yaml, StandardCharsets.UTF_8);
		Path out = dir.resolve("out-" + id);
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		String[] all = Stream.concat(Stream.of("run", "--profile", card.toString(), "--out", out.toString(), "--label", "it", "--pre-warmup", "0"),
				Stream.of(args)).toArray(String[]::new);
		int code = Bench.execute(all, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
		String o = stdout.toString(StandardCharsets.UTF_8);
		String e = stderr.toString(StandardCharsets.UTF_8);
		System.out.println(o);
		System.err.println(e);
		if (!Files.isDirectory(out)) {
			return new Result(code, o, e, null, null);
		}
		List<Path> files;
		try (Stream<Path> s = Files.list(out)) {
			files = s.toList();
		}
		Path json = files.stream().filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
		Path md = files.stream().filter(p -> p.toString().endsWith(".md")).findFirst().orElseThrow();
		return new Result(code, o, e, (Map<?, ?>) Json.parse(Files.readString(json, StandardCharsets.UTF_8)), md);
	}

	private static Object path(Map<?, ?> json, String... keys) {
		return MarkdownReport.path(json, keys);
	}

	private static Map<?, ?> round(Result r) {
		return (Map<?, ?>) ((List<?>) r.json().get("rounds")).get(0);
	}

	/** 假驱动：等时间表出现，按其中的 fault 行回写"已执行"（时刻 = 本轮开始 + at），什么都不真正做。 */
	private static Thread fakeDriver(Path schedule) {
		return Thread.ofVirtual().start(() -> {
			try {
				long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
				while (!Files.exists(schedule) && System.nanoTime() < deadline) {
					Thread.sleep(10);
				}
				List<String> lines = Files.readAllLines(schedule);
				long start = lines.stream().filter(l -> l.startsWith("round_start_epoch_ms ")).map(l -> Long.parseLong(l.split(" ")[1])).findFirst()
						.orElseThrow();
				StringBuilder executed = new StringBuilder();
				for (String line : lines) {
					if (line.startsWith("fault ")) {
						String[] p = line.split(" ");
						executed.append(p[1]).append(' ').append(start + Long.parseLong(p[2]) + 3).append(" ok fake driver: ").append(p[3]).append('\n');
					}
				}
				Files.writeString(schedule.resolveSibling(schedule.getFileName() + ".executed"), executed);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
	}

	@Test
	void stepsAndAFaultScheduleRunThroughThePipeline(@TempDir Path dir) throws Exception {
		String id = "it-fault-steps";
		Path schedule = dir.resolve("schedule.txt");
		Thread driver = fakeDriver(schedule);
		Result r = run(dir, id, card(id, "", STEPS_AND_FAULTS, "3s", "[500ms, 3s]"), "--direct", "127.0.0.1:" + server.port(),
				"--fault-schedule", schedule.toString());
		driver.join(5_000);
		assertEquals(Bench.OK, r.code(), r.out() + r.err());

		List<String> lines = Files.readAllLines(schedule);
		assertEquals(FaultPlan.HEADER, lines.get(0));
		assertEquals("mode inject", lines.get(1));
		assertTrue(lines.contains("fault 0 1000 toxic-latency 1 latency_ms=5 jitter_ms=0 stream=downstream"), lines.toString());
		assertTrue(lines.contains("fault 1 2000 remove-toxic 1"), lines.toString());

		assertEquals("inject", path(r.json(), "run", "faults", "mode"));
		assertEquals(Boolean.TRUE, path(r.json(), "validity", "faults_executed"));
		Map<?, ?> round = round(r);
		List<?> rows = (List<?>) ((Map<?, ?>) round.get("timeline")).get("rows");
		assertTrue(rows.size() >= 3, "a row per second: " + rows.size());
		long success = rows.stream().mapToLong(o -> ((Number) ((Map<?, ?>) o).get("success")).longValue()).sum();
		assertTrue(success > 900, "200 + 600 + 200 requests: " + success);
		Map<?, ?> first = (Map<?, ?>) rows.get(0);
		assertNotNull(first.get("node_share"), "sampled columns are present: " + first);
		assertEquals(1.0, ((Map<?, ?>) first.get("node_share")).get("127.0.0.1:" + server.port()));
		assertEquals(0L, ((Number) first.get("server_executor_rejections")).longValue());

		List<?> steps = (List<?>) round.get("steps");
		assertEquals(3, steps.size());
		Map<?, ?> burst = (Map<?, ?>) steps.get(1);
		assertEquals(600, burst.get("rate"));
		long burstRequests = ((Number) burst.get("requests")).longValue();
		assertTrue(burstRequests > 500 && burstRequests < 700, "about 600 completions in the 600 req/s step: " + burstRequests);
		assertTrue(burst.get("server") instanceof Map<?, ?>, "the step's server delta from two samples: " + burst);

		List<?> faults = (List<?>) round.get("faults");
		Map<?, ?> f0 = (Map<?, ?>) faults.get(0);
		assertEquals("ok", f0.get("status"));
		assertEquals(1003.0, ((Number) f0.get("executed_at_ms")).doubleValue(), 1.0);
		assertNotNull(path(f0, "recovery", "errors_recovery_s"), "no errors in the run: recovered at once: " + f0);
		assertTrue(round.get("server_executor") instanceof Map<?, ?> se && se.get("queue_wait_ms") instanceof Map<?, ?>,
				"window server executor stats: " + round.get("server_executor"));
		assertEquals(1200L, ((Number) path(r.json(), "profile", "parsed", "load", "inflight_cap")).longValue(), "cap = peak 600 x 1s x 2");

		String md = Files.readString(r.markdown(), StandardCharsets.UTF_8);
		assertTrue(md.contains("阶梯（第 1 轮）"), md);
		assertTrue(md.contains("故障（第 1 轮）"), md);
		assertTrue(md.contains("时间线（第 1 轮，每秒）"), md);
		assertTrue(md.contains("开环（open）阶梯：200 req/s × 1s → 600 req/s × 1s → 200 req/s × 1s"), md);
		assertTrue(md.contains("服务端业务执行器（正式轮统计窗口内）"), md);
	}

	@Test
	void faultsThatWereNotExecutedFailTheRun(@TempDir Path dir) throws Exception {
		String id = "it-fault-missing";
		Path schedule = dir.resolve("schedule.txt");
		Result r = run(dir, id, card(id, "", STEPS_AND_FAULTS, "3s", "[500ms, 3s]"), "--direct", "127.0.0.1:" + server.port(),
				"--fault-schedule", schedule.toString());
		assertEquals(Bench.FAILED_ASSERTIONS, r.code(), r.out() + r.err());
		assertEquals(Boolean.FALSE, path(r.json(), "validity", "faults_executed"));
		assertTrue(String.valueOf(path(r.json(), "validity", "statements")).contains("故障没有全部按计划执行"));
		assertEquals("not_executed", ((Map<?, ?>) ((List<?>) round(r).get("faults")).get(0)).get("status"));
		assertTrue(Files.exists(schedule), "the schedule is written even when nobody executes it");
	}

	@Test
	void noFaultsIsAControlRun(@TempDir Path dir) throws Exception {
		String id = "it-fault-control";
		Result r = run(dir, id, card(id, "", STEPS_AND_FAULTS, "3s", "[500ms, 3s]"), "--direct", "127.0.0.1:" + server.port(), "--no-faults");
		assertEquals(Bench.OK, r.code(), r.out() + r.err());
		assertEquals("control", path(r.json(), "run", "faults", "mode"));
		Map<?, ?> f0 = (Map<?, ?>) ((List<?>) round(r).get("faults")).get(0);
		assertTrue(String.valueOf(f0.get("status")).startsWith("control"), f0.toString());
		assertTrue(String.valueOf(path(r.json(), "validity", "statements")).contains("对照运行（--no-faults）"));

		Result usage = run(dir, "it-fault-usage", card("it-fault-usage", "", STEPS_AND_FAULTS, "3s", "[500ms, 3s]"), "--direct",
				"127.0.0.1:" + server.port());
		assertEquals(Bench.ERROR, usage.code());
		assertTrue(usage.err().contains("--fault-schedule"), usage.err());
	}

	@Test
	void clusterOptionsComeFromTheCommandLineOrTheCard(@TempDir Path dir) throws Exception {
		String id = "it-cluster-cli";
		Result r = run(dir, id, card(id, "", "  rate: 200", "1s", "[500ms, 1s]"), "--direct", "127.0.0.1:" + server.port(), "--loadbalance", "p2c",
				"--cluster", "failover", "--retries", "2");
		assertEquals(Bench.OK, r.code(), r.out() + r.err());
		assertEquals("p2c", path(r.json(), "run", "target", "load_balance"));
		assertEquals("failover", path(r.json(), "run", "target", "cluster"));
		assertEquals(2, path(r.json(), "run", "target", "retries"));
		assertEquals("command line", path(r.json(), "run", "target", "cluster_options_source", "cluster"));
		assertFalse(round(r).containsKey("timeline"), "no steps / faults: no timeline, the round JSON keeps its old shape");

		String conflict = "it-cluster-conflict";
		Result c = run(dir, conflict, card(conflict, "  cluster: failfast", "  rate: 200", "1s", "[500ms, 1s]"), "--direct",
				"127.0.0.1:" + server.port(), "--cluster", "failover");
		assertEquals(Bench.ERROR, c.code());
		assertTrue(c.err().contains("conflicts with the card's target.cluster"), c.err());
		Result bad = run(dir, "it-cluster-bad", card("it-cluster-bad", "", "  rate: 200", "1s", "[500ms, 1s]"), "--direct",
				"127.0.0.1:" + server.port(), "--loadbalance", "random");
		assertEquals(Bench.ERROR, bad.code());
	}

	/**
	 * 这里验的是"卡片声明的服务端排队上限 / 业务等待"与服务端报告的环境逐项核对（{@code validity.server_options}），不是轮次有效性。
	 * 三次运行都用闭环短卡（{@link #closedCard}）：开环卡在 CI runner 上偶发 {@code dispatcher_delayed}（发出滞后 p99 &gt; 1ms，轮次判无效、
	 * 退出码 2），会让"相符应当通过"的断言因为与本用例无关的原因失败，"不符应当退出码 2"的断言也可能因为错误的原因通过。
	 * 闭环轮没有发出计划，不存在这条无效原因；退出码只反映核对结果。
	 */
	@Test
	void serverQueueCapacityAndWorkAreCheckedAgainstTheServer(@TempDir Path dir) throws Exception {
		int threads = ((ExecutorConfig.Fixed) ExecutorConfig.defaults()).threads();
		try (BenchServer working = BenchServer.start(0, "127.0.0.1", null, "protobuf", null, 0, Bench.executor("fixed", 50), true, true, null,
				null, Duration.ofMillis(2).toNanos())) {
			assertEquals("50", working.environment().get("server_executor_queue_capacity"));
			assertEquals("2.0", working.environment().get("server_work_ms"));
			assertTrue(working.environment().get("server_executor").contains(threads + " threads, queue 50"), working.environment().toString());
			String id = "it-server-options";
			Result r = run(dir, id, closedCard(id, "  server_queue_capacity: 50\n  server_work: 2ms", 4, "1s", "[500ms, 1s]"), "--direct",
					"127.0.0.1:" + working.port());
			assertEquals(Bench.OK, r.code(), r.out() + r.err());
			assertEquals(Boolean.TRUE, path(r.json(), "validity", "server_options", "matches"));
			assertTrue(((Number) path(r.json(), "summary", "official", "p50_ms")).doubleValue() >= 2.0, "every call waits 2ms on the server");

			String mismatch = "it-server-options-mismatch";
			Result m = run(dir, mismatch, closedCard(mismatch, "  server_work: 3ms", 4, "1s", "[500ms, 1s]"), "--direct",
					"127.0.0.1:" + working.port());
			assertEquals(Bench.FAILED_ASSERTIONS, m.code(), m.out() + m.err());
			assertEquals(Boolean.FALSE, path(m.json(), "validity", "server_options", "matches"));
			assertEquals(Boolean.TRUE, path(m.json(), "validity", "all_rounds_valid"), "exit code 2 comes from the mismatch, not from an invalid round");
		}
		Result defaults = run(dir, "it-server-defaults", closedCard("it-server-defaults", "", 4, "1s", "[500ms, 1s]"), "--direct",
				"127.0.0.1:" + server.port());
		assertEquals(Bench.OK, defaults.code(), defaults.err());
		assertEquals(Boolean.TRUE, path(defaults.json(), "validity", "server_options", "matches"), "defaults: queue 1000, no work");
	}

	/**
	 * 业务等待方式（{@code target.server_work_mode}）与服务端环境的 {@code server_work_mode} 核对：相符通过；卡片没写（= park）而服务端是
	 * monitor 时判定不通过（退出码 2，原因是核对而不是轮次无效）。闭环短卡，理由同上。
	 */
	@Test
	void serverWorkModeIsCheckedAgainstTheServer(@TempDir Path dir) throws Exception {
		try (BenchServer monitor = BenchServer.start(0, "127.0.0.1", null, "protobuf", null, 0, Bench.executor("virtual"), true, true, null,
				null, Duration.ofMillis(2).toNanos(), null, BenchServiceImpl.WorkMode.MONITOR)) {
			String id = "it-work-mode";
			Result r = run(dir, id, closedCard(id, "  server_executor: virtual\n  server_work: 2ms\n  server_work_mode: monitor", 4, "1s",
					"[500ms, 1s]"), "--direct", "127.0.0.1:" + monitor.port());
			assertEquals(Bench.OK, r.code(), r.out() + r.err());
			assertEquals(Boolean.TRUE, path(r.json(), "validity", "server_options", "matches"));
			assertEquals("monitor", path(r.json(), "validity", "server_options", "work_mode_actual"));
			assertTrue(((List<?>) path(r.json(), "validity", "statements")).stream()
					.anyMatch(s -> String.valueOf(s).contains("业务方法每次在 synchronized 块内 Object.wait 2ms")), r.json().toString());

			String mismatch = "it-work-mode-mismatch";
			Result m = run(dir, mismatch, closedCard(mismatch, "  server_executor: virtual\n  server_work: 2ms", 4, "1s", "[500ms, 1s]"),
					"--direct", "127.0.0.1:" + monitor.port());
			assertEquals(Bench.FAILED_ASSERTIONS, m.code(), m.out() + m.err());
			assertEquals(Boolean.FALSE, path(m.json(), "validity", "server_options", "matches"));
			assertEquals("park", path(m.json(), "validity", "server_options", "work_mode_expected"));
			assertEquals(Boolean.TRUE, path(m.json(), "validity", "all_rounds_valid"), "exit code 2 comes from the mismatch, not from an invalid round");
		}
	}

	/**
	 * 拓扑文件第 6 列给了 HTTP 控制端口时，每秒的服务端计数与窗口统计改经 {@code GET /counters} / {@code GET /stats} 取（IO 线程回答，
	 * 不经业务执行器）。这里的业务执行器是 1 个线程、队列 0、每次调用占 30ms：经 RPC 取统计会被拒绝，经 HTTP 照样取得到。
	 */
	@Test
	void controlPortsMoveServerSamplingOffTheBusinessExecutor(@TempDir Path dir) throws Exception {
		try (BenchServer busy = BenchServer.start(0, "127.0.0.1", null, "protobuf", null, 0, ExecutorConfig.fixed(1, 0), true, true, null, null,
				Duration.ofMillis(30).toNanos());
				BenchHttpServer control = BenchHttpServer.start(0, busy.environment(), busy.statsSupplier(), busy.countersSupplier())) {
			Path topology = dir.resolve("topology.txt");
			Files.writeString(topology, "# idx advertised pid listen_port proxy control_http_port\n1 127.0.0.1:" + busy.port() + " 1 " + busy.port()
					+ " - " + control.port() + "\n");
			String id = "it-control-port";
			Result r = run(dir, id, card(id, "  server_queue_capacity: 0\n  server_work: 30ms",
					"  rate: 100\n  faults: [{ at: 1s, kind: sigcont, target: 1 }]", "3s", "[500ms, 3s]"), "--direct", "127.0.0.1:" + busy.port(),
					"--no-faults", "--topology", topology.toString());
			assertEquals(Bench.OK, r.code(), r.out() + r.err());
			assertTrue(String.valueOf(path(r.json(), "run", "target", "server_stats_channel")).contains("HTTP control endpoint"),
					String.valueOf(path(r.json(), "run", "target")));
			List<?> rows = (List<?>) ((Map<?, ?>) round(r).get("timeline")).get("rows");
			long rejected = 0;
			for (Object o : rows) {
				Map<?, ?> row = (Map<?, ?>) o;
				if (row.get("node_share") == null) {
					continue; // 本轮结束之后的尾巴秒没有下一个样本
				}
				assertEquals(List.of(), row.get("providers_unreachable"), "the HTTP channel answers while the executor is saturated: " + row);
				rejected += ((Number) row.get("server_executor_rejections")).longValue();
			}
			assertTrue(rejected > 0, "100 req/s at 30ms on one thread without a queue: most are rejected and the counter sees it");
			assertTrue(round(r).get("server_executor") instanceof Map<?, ?>, "window stats over HTTP: " + round(r).get("server_executor"));
		}
	}

	@Test
	void advertisePortRegistersAnotherPortInZooKeeper(@TempDir Path dir) throws Exception {
		// 公布的端口指向另一个真实在跑的 bench server（替代 toxiproxy 代理）：客户端经注册中心发现的是公布端口，调用也确实打到那里
		try (TestingServer zk = new TestingServer(true);
				BenchServer registered = BenchServer.start(0, "127.0.0.1", RegistryConfig.zookeeper(zk.getConnectString()), "protobuf", null, 0,
						ExecutorConfig.defaults(), true, true, null, server.port(), 0)) {
			assertEquals(String.valueOf(server.port()), registered.environment().get("server_advertised_port"));
			String id = "it-advertise";
			Result r = run(dir, id, card(id, "", "  rate: 100", "1s", "[500ms, 1s]"), "--registry", "zk://" + zk.getConnectString());
			assertEquals(Bench.OK, r.code(), r.out() + r.err());
			assertEquals(List.of("127.0.0.1:" + server.port()), path(r.json(), "run", "target", "providers"));
		}
	}
}
