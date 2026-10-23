package io.github.sisyfyzhang.steadyrpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** wrk / wrk2 输出解析（fixture 是 WSL 上实跑的原始输出）与交叉验证的判定口径（开环测量校验、闭环容量标定）。 */
class CrossValidationTest {

	static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures");
	static final String TOOL_CPU_SERVER_BOUND = "服务端核(0 1 2 3 4) 平均 99% · 压测端核(5 6 7 8 9) 平均 67%（20s 采样）· cpu0=98%";

	private static String fixture(String name) throws Exception {
		return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ 解析

	@Test
	void parsesWrkLatencyDistributionAndThroughput() throws Exception {
		CrossValidation.ToolResult r = CrossValidation.parseToolOutput("wrk", fixture("wrk-closed-sample.txt"));
		assertEquals(230369.35, r.requestsPerSec());
		assertEquals(2304894L, r.requests());
		assertEquals(10.01, r.durationSeconds(), 1e-9);
		assertEquals(0.166, r.p50Ms(), 1e-9);
		assertEquals(0.91, r.p75Ms(), 1e-9);
		assertEquals(3.38, r.p90Ms(), 1e-9);
		assertEquals(8.44, r.p99Ms(), 1e-9);
		assertNull(r.p999Ms(), "wrk prints no 99.9%");
		assertEquals(24.55, r.maxMs(), 1e-9);
		assertEquals(0, r.socketErrors());
		assertNull(r.uncorrectedP50Ms());
	}

	@Test
	void parsesWrk2RecordedAndUncorrectedSectionsSeparately() throws Exception {
		CrossValidation.ToolResult r = CrossValidation.parseToolOutput("wrk2", fixture("wrk2-open-u-sample.txt"));
		assertEquals(4988.71, r.requestsPerSec());
		assertEquals(0.96, r.p50Ms(), 1e-9);
		assertEquals(1.91, r.p99Ms(), 1e-9);
		assertEquals(2.13, r.p999Ms(), 1e-9);
		assertEquals(0.143, r.uncorrectedP50Ms(), 1e-9, "the -U section must not overwrite the recorded one");
		assertEquals(0.324, r.uncorrectedP99Ms(), 1e-9);
		assertEquals(0.421, r.uncorrectedP999Ms(), 1e-9);

		CrossValidation.ToolResult plain = CrossValidation.parseToolOutput("wrk2", fixture("wrk2-open-sample.txt"));
		assertEquals(0.98, plain.p50Ms(), 1e-9);
		assertEquals(3.01, plain.p999Ms(), 1e-9);
		assertNull(plain.uncorrectedP50Ms());
	}

	@Test
	void parsesSocketErrorsAndRejectsIncompleteOutput() {
		String withErrors = """
				  Latency Distribution
				     50%  100.00us
				     99%    1.50ms
				  10 requests in 1.00s, 1.00KB read
				  Socket errors: connect 1, read 2, write 0, timeout 40
				  Non-2xx or 3xx responses: 3
				Requests/sec:     10.00
				""";
		CrossValidation.ToolResult r = CrossValidation.parseToolOutput("wrk", withErrors);
		assertEquals(43, r.socketErrors());
		assertEquals(3, r.non2xx());
		assertEquals(0.1, r.p50Ms(), 1e-9);
		assertThrows(IllegalArgumentException.class, () -> CrossValidation.parseToolOutput("wrk", "unable to connect"));
		assertThrows(IllegalArgumentException.class, () -> CrossValidation.parseToolOutput("wrk", "Requests/sec: 1.00\n"),
				"no --latency distribution");
		assertEquals(1500.0, CrossValidation.toMillis(1.5, "s"));
		assertEquals(0.25, CrossValidation.toMillis(250, "us"));
	}

	@Test
	void cpuSamplerLineAndApproximations() {
		int[] averages = CrossValidation.cpuAverages(TOOL_CPU_SERVER_BOUND);
		assertEquals(99, averages[0]);
		assertEquals(67, averages[1]);
		assertNull(CrossValidation.cpuAverages("unknown"));
		assertNull(CrossValidation.cpuAverages(null));
		assertEquals("134,000", CrossValidation.approx(134276.9));
		assertEquals("211,000", CrossValidation.approx(211011.8));
		assertEquals("5,000", CrossValidation.approx(4995.9));
		assertEquals("server", CrossValidation.bottleneck(99.0, 67.0));
		assertEquals("client", CrossValidation.bottleneck(77.0, 98.0));
		assertEquals("both", CrossValidation.bottleneck(95.0, 98.0));
		assertNull(CrossValidation.bottleneck(40.0, 30.0), "neither side saturated: cannot tell");
		assertNull(CrossValidation.bottleneck(null, 98.0));
	}

	// ------------------------------------------------------------------ 判定

	/** 合成的 harness 结果 JSON：正式轮第 2 轮，带服务时间、发出滞后与 CPU 采样。 */
	static Map<String, Object> harness(String model, double rps, double serviceP50, double serviceP99, double lagP99, boolean valid,
			Double clientBusy, Double serverBusy) {
		Map<String, Object> load = new LinkedHashMap<>();
		load.put("model", model);
		load.put("rate", "open".equals(model) ? 5000 : null);
		load.put("concurrency", "open".equals(model) ? null : 64);
		load.put("steady_window", List.of("20s", "60s"));
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("transport", "http");
		target.put("payload", Map.of("kind", "bytes", "size", 1024));
		target.put("http", Map.of("url", "http://127.0.0.1:19180/echo", "connections", 64));
		Map<String, Object> parsed = new LinkedHashMap<>();
		parsed.put("load", load);
		parsed.put("target", target);
		double latencyP50 = serviceP50 + 0.035;
		double latencyP99 = serviceP99 + 0.05;
		Map<String, Object> official = new LinkedHashMap<>();
		official.put("throughput_rps", rps);
		official.put("p50_ms", latencyP50);
		official.put("p99_ms", latencyP99);
		official.put("p999_ms", latencyP99 * 1.5);
		official.put("max_ms", latencyP99 * 3);
		official.put("window_requests", 200000);
		official.put("window_errors", 0);
		Map<String, Object> round = new LinkedHashMap<>();
		round.put("round", 2);
		round.put("valid", valid);
		round.put("window", Map.of("requests", 200000, "errors", 0));
		round.put("latency_ms", Map.of("p50", latencyP50, "p99", latencyP99, "p999", latencyP99 * 1.5, "max", latencyP99 * 3));
		round.put("service_time_ms", Map.of("p50", serviceP50, "p99", serviceP99, "p999", serviceP99 * 1.3, "max", latencyP99 * 3));
		round.put("dispatch_lag_ms", Map.of("p50", 0.0, "p99", lagP99, "p999", lagP99 * 5, "max", 1.0));
		if (clientBusy != null) {
			Map<String, Object> cpu = new LinkedHashMap<>();
			cpu.put("client_cpus", "5-9");
			cpu.put("client_busy_pct", clientBusy);
			cpu.put("server_cpus", "0-4");
			cpu.put("server_busy_pct", serverBusy);
			round.put("cpu", cpu);
		} else {
			round.put("cpu", "n/a");
		}
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("schema_version", 1);
		json.put("label", "crossval");
		json.put("profile", Map.of("id", "crossval-http-" + model + "-v1", "parsed", parsed));
		json.put("run", Map.of("target", Map.of("connections", 64)));
		json.put("environment", Map.of("cpus_allowed", "5-9", "available_cpus", 5, "host_cpus", 10, "hostname", "h", "cpu_model", "c",
				"git_sha", "0123456789", "git_dirty", false));
		json.put("server_environment", Map.of("cpus_allowed", "0-4"));
		Map<String, Object> first = new LinkedHashMap<>(round);
		first.put("round", 1);
		Map<String, Object> third = new LinkedHashMap<>(round);
		third.put("round", 3);
		json.put("rounds", List.of(first, round, third));
		json.put("summary", Map.of("official_round", 2, "selection_rule", "rule", "official", official));
		json.put("validity", Map.of("all_rounds_valid", valid, "backlog_triggered", false, "client_bound", false, "passed", valid));
		return json;
	}

	static Map<String, Object> openHarness(double serviceP50, double serviceP99, double lagP99) {
		return harness("open", 5000.0, serviceP50, serviceP99, lagP99, true, 34.0, 18.0);
	}

	/** wrk2 -U：修正后（含 wrk2 自己的毫秒级发送滞后）与未修正两组分位数。 */
	static CrossValidation.ToolResult wrk2(double rps, double correctedP50, double correctedP99, Double uncorrectedP50, Double uncorrectedP99) {
		return new CrossValidation.ToolResult("wrk2", rps, 200000L, 40.0, correctedP50, null, null, correctedP99, correctedP99 * 1.1, 4.0, 0,
				Map.of(), 0, uncorrectedP50, uncorrectedP99, uncorrectedP99 == null ? null : uncorrectedP99 * 1.3);
	}

	static CrossValidation.ToolResult wrk(double rps, double p50, double p99) {
		return new CrossValidation.ToolResult("wrk", rps, 200000L, 40.0, p50, null, null, p99, null, 35.0, 0, Map.of(), 0, null, null, null);
	}

	private static Map<?, ?> check(Map<String, Object> result, String key) {
		for (Object c : (List<?>) result.get("checks")) {
			if (key.equals(((Map<?, ?>) c).get("key"))) {
				return (Map<?, ?>) c;
			}
		}
		throw new AssertionError("no check " + key + " in " + result.get("checks"));
	}

	private static Map<String, Object> open(Map<String, Object> harness, CrossValidation.ToolResult tool) {
		return CrossValidation.compare(harness, "h.json", tool, "", "wrk2 ...", "wrk 4.0.0", "t.txt", null);
	}

	private static Map<String, Object> open(Map<String, Object> harness, List<CrossValidation.Measurement> runs) {
		return CrossValidation.compare(harness, "h.json", runs, "wrk2 ...", "wrk 4.0.0", null);
	}

	private static CrossValidation.Measurement run(String file, CrossValidation.ToolResult result) {
		return new CrossValidation.Measurement(file, result, "raw " + file);
	}

	/**
	 * 对称口径：wrk2 三次测量按未修正 p99 取中位那一次作对照值；离群的一次（task10 #4 那样的 0.537ms）不会单独决定判定。
	 * 三次都写进 JSON（tool.measurements），被选中的标 selected。
	 */
	@Test
	void theReferenceIsTheMedianOfThreeWrk2Runs() {
		List<CrossValidation.Measurement> runs = List.of(run("m1.txt", wrk2(4996.0, 0.97, 1.95, 0.150, 0.537)),
				run("m2.txt", wrk2(4996.0, 0.97, 1.95, 0.148, 0.352)), run("m3.txt", wrk2(4996.0, 0.97, 1.95, 0.152, 0.348)));
		assertEquals(1, CrossValidation.medianIndex(runs), "sorted by uncorrected p99: m3 0.348, m2 0.352, m1 0.537 -> m2");
		Map<String, Object> r = open(openHarness(0.150, 0.350, 0.04), runs);
		assertEquals("PASS", r.get("verdict"), String.valueOf(r.get("checks")));
		assertEquals(3, ((Number) MarkdownReport.path(r, "tool", "runs")).intValue());
		assertEquals(2, ((Number) MarkdownReport.path(r, "tool", "selected_run")).intValue());
		assertEquals("m2.txt", MarkdownReport.path(r, "tool", "output_file"));
		assertEquals(0.352, ((Number) MarkdownReport.path(r, "tool", "uncorrected_latency_ms", "p99")).doubleValue());
		List<?> measurements = (List<?>) MarkdownReport.path(r, "tool", "measurements");
		assertEquals(3, measurements.size());
		assertEquals(Boolean.TRUE, ((Map<?, ?>) measurements.get(1)).get("selected"));
		assertEquals(0.537, ((Number) ((Map<?, ?>) measurements.get(0)).get("uncorrected_p99_ms")).doubleValue());
		assertEquals(CrossValidation.SCHEMA, r.get("schema"));
		assertEquals("crossval/3", CrossValidation.SCHEMA);
		Map<?, ?> readBack = (Map<?, ?>) Json.parse(Json.write(r));
		String md = CrossValidation.render(readBack, "x.json");
		assertTrue(md.contains("## wrk2 各次测量") && md.contains("（对照值）") && md.contains("`m1.txt`"), md);
		// 只有一次测量时与旧行为相同
		assertEquals(0, CrossValidation.medianIndex(List.of(run("only.txt", wrk2(4996.0, 0.97, 1.95, 0.148, 0.330)))));
	}

	/** 前提：三次里任何一次有 socket 错误（或非 2xx），参照端就不干净，判 FAIL——即使被选中的那一次本身干净。 */
	@Test
	void aSocketErrorInAnyReferenceRunFailsThePrecondition() {
		CrossValidation.ToolResult dirty = new CrossValidation.ToolResult("wrk2", 4996.0, 200000L, 40.0, 0.97, null, null, 1.95, 2.1, 4.0, 3,
				Map.of("timeout", 3L), 0, 0.150, 0.600, 0.8);
		List<CrossValidation.Measurement> runs = List.of(run("m1.txt", wrk2(4996.0, 0.97, 1.95, 0.148, 0.352)), run("m2.txt", dirty),
				run("m3.txt", wrk2(4996.0, 0.97, 1.95, 0.152, 0.348)));
		assertEquals(0, CrossValidation.medianIndex(runs), "median by p99: m3 0.348, m1 0.352, m2 0.600 -> m1 (clean)");
		Map<String, Object> r = open(openHarness(0.150, 0.350, 0.04), runs);
		assertEquals(Boolean.FALSE, check(r, "tool_clean").get("passed"));
		assertEquals("FAIL", r.get("verdict"));
	}

	/** 本机实测的形态：wrk2 修正后 0.97 vs harness 0.25 差得很远，但那是 wrk2 的毫秒节拍；服务时间 vs 未修正一致即通过。 */
	@Test
	void openLoopPassesOnServiceTimeVersusUncorrectedLatencyRegardlessOfWrk2CorrectedLatency() {
		Map<String, Object> r = open(openHarness(0.218, 0.401, 0.009), wrk2(4995.9, 0.97, 1.95, 0.148, 0.330));
		assertEquals("PASS", r.get("verdict"), String.valueOf(r.get("checks")));
		assertEquals("measurement", r.get("kind"));
		for (String key : List.of("harness_clean", "tool_clean", "harness_throughput_vs_rate", "tool_throughput_vs_rate", "measurement_p50",
				"measurement_p99", "dispatch_lag_p99")) {
			assertEquals(Boolean.TRUE, check(r, key).get("passed"), key);
		}
		String rows = r.get("side_by_side").toString();
		assertTrue(rows.contains("metric=修正后 p50 (ms), group=info, judged=false"), "corrected latency is shown but not judged: " + rows);
		assertTrue(r.get("notes").toString().contains("src/wrk.c 第 604 行"), "the wrk2 pacing limitation cites its source line");
	}

	@Test
	void openLoopMeasurementUsesTheRelativeOrTheAbsoluteBound() {
		// 相对偏差 > 15% 但绝对差 ≤ 100µs：通过
		Map<String, Object> close = open(openHarness(0.20, 0.40, 0.009), wrk2(5000.0, 0.9, 1.9, 0.12, 0.31));
		assertEquals(Boolean.TRUE, check(close, "measurement_p50").get("passed"));
		assertEquals(Boolean.TRUE, check(close, "measurement_p99").get("passed"));
		// 两者都超：失败
		Map<String, Object> far = open(openHarness(0.40, 0.90, 0.009), wrk2(5000.0, 0.9, 1.9, 0.15, 0.33));
		assertEquals("FAIL", far.get("verdict"));
		assertEquals(Boolean.FALSE, check(far, "measurement_p50").get("passed"));
		assertEquals(Boolean.FALSE, check(far, "measurement_p99").get("passed"));
		assertTrue(((List<?>) far.get("possible_causes")).contains(CrossValidation.CAUSE_MEASUREMENT));
	}

	@Test
	void openLoopFailsWithoutAnUncorrectedDistribution() {
		Map<String, Object> r = open(openHarness(0.218, 0.401, 0.009), wrk2(5000.0, 0.97, 1.95, null, null));
		assertEquals("FAIL", r.get("verdict"));
		assertEquals(Boolean.FALSE, check(r, "measurement_p50").get("passed"));
		assertTrue(((List<?>) r.get("possible_causes")).contains(CrossValidation.CAUSE_NO_UNCORRECTED));
	}

	@Test
	void openLoopFailsWhenTheHarnessDispatchLagP99ExceedsOneHundredMicroseconds() {
		Map<String, Object> r = open(openHarness(0.218, 0.401, 0.25), wrk2(5000.0, 0.97, 1.95, 0.148, 0.330));
		assertEquals("FAIL", r.get("verdict"));
		assertEquals(Boolean.FALSE, check(r, "dispatch_lag_p99").get("passed"));
		assertTrue(((List<?>) r.get("possible_causes")).contains(CrossValidation.CAUSE_DISPATCH));
	}

	@Test
	void openLoopFailsWhenAToolMissesTheRateByMoreThanTwoPercent() {
		Map<String, Object> r = open(openHarness(0.218, 0.401, 0.009), wrk2(4880.0, 0.97, 1.95, 0.148, 0.330));
		assertEquals("FAIL", r.get("verdict"));
		assertEquals(Boolean.FALSE, check(r, "tool_throughput_vs_rate").get("passed"));
		assertEquals(Boolean.TRUE, check(r, "harness_throughput_vs_rate").get("passed"));
		assertTrue(((List<?>) r.get("possible_causes")).contains(CrossValidation.CAUSE_PACING));
	}

	@Test
	void openLoopPreconditionsStillApply() {
		Map<String, Object> invalid = open(harness("open", 5000.0, 0.218, 0.401, 0.009, false, 34.0, 18.0),
				wrk2(5000.0, 0.97, 1.95, 0.148, 0.330));
		assertEquals("FAIL", invalid.get("verdict"));
		assertEquals(Boolean.FALSE, check(invalid, "harness_clean").get("passed"));
	}

	/**
	 * 前提按有效轮计：3 轮里 1 轮被判无效（例如 dispatcher_delayed）仍可比较（正式轮在有效轮里选）；只剩 1 个有效轮时证据不足，FAIL。
	 * b 项阈值不受影响。
	 */
	@Test
	@SuppressWarnings("unchecked")
	void openLoopPreconditionCountsValidRounds() {
		Map<String, Object> oneInvalid = harness("open", 5000.0, 0.218, 0.401, 0.009, true, 34.0, 18.0);
		Map<String, Object> delayed = new LinkedHashMap<>((Map<String, Object>) ((List<?>) oneInvalid.get("rounds")).get(0));
		delayed.put("valid", false);
		List<Object> rounds = new java.util.ArrayList<>((List<?>) oneInvalid.get("rounds"));
		rounds.set(0, delayed);
		oneInvalid.put("rounds", rounds);
		Map<String, Object> r = open(oneInvalid, wrk2(5000.0, 0.97, 1.95, 0.148, 0.330));
		assertEquals(Boolean.TRUE, check(r, "harness_clean").get("passed"), String.valueOf(check(r, "harness_clean")));
		assertEquals(2, ((Number) MarkdownReport.path(r, "harness", "valid_rounds")).intValue());

		Map<?, ?> second = (Map<?, ?>) rounds.get(2);
		Map<String, Object> delayedToo = new LinkedHashMap<>((Map<String, Object>) second);
		delayedToo.put("valid", false);
		rounds.set(2, delayedToo);
		Map<String, Object> onlyOne = open(oneInvalid, wrk2(5000.0, 0.97, 1.95, 0.148, 0.330));
		assertEquals(Boolean.FALSE, check(onlyOne, "harness_clean").get("passed"));
		assertEquals("FAIL", onlyOne.get("verdict"));
	}

	/** 闭环 = 容量标定：吞吐差 36% 不判失败；唯一判定项是两侧 CPU 都能判别瓶颈；结论句写明两边的上限。 */
	@Test
	void closedLoopIsACapacityCalibrationJudgedOnlyOnTheCpuEvidence() {
		Map<String, Object> r = CrossValidation.compare(harness("closed", 134276.9, 0.37, 1.4, 0.0, true, 98.0, 77.0), "h.json",
				wrk(211011.8, 0.181, 8.31), "", "wrk ...", null, "t.txt", TOOL_CPU_SERVER_BOUND);
		assertEquals("PASS", r.get("verdict"), String.valueOf(r.get("checks")));
		assertEquals("capacity", r.get("kind"));
		assertEquals(1, ((List<?>) r.get("checks")).size(), "the CPU evidence is the only judged item");
		assertEquals(Map.of("harness_run", "client", "tool_run", "server"), r.get("bottleneck"));
		assertEquals("harness 闭环发压上限约 134,000 req/s（本机、1024 字节、64 连接）；wrk 测得的服务端上限约 211,000 req/s。",
				r.get("conclusion"));
		assertFalse(r.get("side_by_side").toString().contains("judged=true"), "closed-loop numbers are informational");
	}

	@Test
	void closedLoopFailsWhenTheBottleneckCannotBeTold() {
		Map<String, Object> noToolCpu = CrossValidation.compare(harness("closed", 134276.9, 0.37, 1.4, 0.0, true, 98.0, 77.0), "h.json",
				wrk(211011.8, 0.181, 8.31), "", null, null, "t.txt", "unknown");
		assertEquals("FAIL", noToolCpu.get("verdict"));
		assertTrue(((List<?>) noToolCpu.get("possible_causes")).contains(CrossValidation.CAUSE_CPU));
		Map<String, Object> lightLoad = CrossValidation.compare(harness("closed", 50000.0, 0.37, 1.4, 0.0, true, 60.0, 40.0), "h.json",
				wrk(211011.8, 0.181, 8.31), "", null, null, "t.txt", TOOL_CPU_SERVER_BOUND);
		assertEquals("FAIL", lightLoad.get("verdict"), "neither side saturated in the harness run");
		Map<String, Object> noHarnessCpu = CrossValidation.compare(harness("closed", 134276.9, 0.37, 1.4, 0.0, true, null, null), "h.json",
				wrk(211011.8, 0.181, 8.31), "", null, null, "t.txt", TOOL_CPU_SERVER_BOUND);
		assertEquals("FAIL", noHarnessCpu.get("verdict"), "rounds[].cpu = n/a (not Linux)");
	}

	/** 子命令端到端（真实 wrk2 -U 输出）：写 crossval-<mode>-<时间>.json / .md，PASS 时退出码 0。 */
	@Test
	void crossvalCommandWritesJsonAndMarkdown(@TempDir Path dir) throws Exception {
		Path harnessFile = dir.resolve("crossval-http-open-v1-20261019-000000.json");
		Files.writeString(harnessFile, Json.write(openHarness(0.232, 0.42, 0.009)), StandardCharsets.UTF_8);
		Files.copy(FIXTURES.resolve("wrk2-open-u-sample.txt"), dir.resolve("wrk2-measure.txt"));
		Path out = dir.resolve("out");
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		int code = Bench.execute(new String[] { "crossval", "--harness", harnessFile.toString(), "--tool", "wrk2", "--tool-output",
				dir.resolve("wrk2-measure.txt").toString(), "--tool-command", "wrk2 -t 4 -c 64 -R 5000 -U", "--cpu-tool",
				"服务端核(0-4) 平均 12% · 压测端核(5-9) 平均 6%（20s 采样）", "--out", out.toString() },
				new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stdout, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, stdout.toString(StandardCharsets.UTF_8));
		Path json;
		try (Stream<Path> files = Files.list(out)) {
			json = files.filter(p -> p.getFileName().toString().matches("crossval-open-\\d{8}-\\d{6}\\.json")).findFirst().orElseThrow();
		}
		Map<?, ?> result = (Map<?, ?>) Json.parse(Files.readString(json, StandardCharsets.UTF_8));
		assertEquals("PASS", result.get("verdict"));
		String md = Files.readString(out.resolve(json.getFileName().toString().replace(".json", ".md")), StandardCharsets.UTF_8);
		assertTrue(md.startsWith("# 交叉验证：harness vs wrk2（开环，测量校验）"), md);
		assertTrue(md.contains("判定：**PASS**"));
		assertTrue(md.contains("| 服务时间 vs 未修正 p50 (ms) | 0.232 | 0.143 | 0.089 | 62.238% | 判定（b） |"), md);
		assertTrue(md.contains("| 修正后 p50 (ms) | 0.267 | 0.960 |"), md);
		assertTrue(md.contains("信息项，不判定"));
		assertTrue(md.contains("| c. 节拍质量 | `dispatch_lag_p99` |"), md);
		assertTrue(md.contains("| harness | 0-4：18.0% | 5-9：34.0% |"), md);

		assertEquals(Bench.ERROR, Bench.execute(new String[] { "crossval", "--harness", harnessFile.toString(), "--tool", "ab",
				"--tool-output", "x", "--out", out.toString() }, new PrintStream(stdout, true, StandardCharsets.UTF_8),
				new PrintStream(stdout, true, StandardCharsets.UTF_8)), "only wrk / wrk2");
	}
}
