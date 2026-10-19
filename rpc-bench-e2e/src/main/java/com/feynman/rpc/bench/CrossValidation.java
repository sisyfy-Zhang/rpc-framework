package com.feynman.rpc.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code crossval} 子命令：把 harness 的一次 {@code transport: http} 运行（结果 JSON 的正式轮）与第三方发压工具
 * （wrk / wrk2）对<b>同一个</b> URL 测出的数字并排比较。比较的是两个发压工具，不是 RPC 与 HTTP。口径见 docs/benchmarking.md「交叉验证」。
 *
 * <p><b>开环 = 测量校验</b>（vs wrk2 {@code -U}），三项分别判定，相对偏差 = |harness − wrk2| / wrk2：
 * <ol type="a">
 *   <li>达成吞吐：harness 与 wrk2 各自相对目标 rate 的偏差 ≤ {@value #OPEN_THROUGHPUT_VS_RATE_PCT}%；</li>
 *   <li>测量一致性：harness 的<b>服务时间</b>（实际发出 → 完成）对比 wrk2 的<b>未修正</b>延迟（实际发出 → 响应），p50、p99
 *       相对偏差 ≤ {@value #LATENCY_REL_PCT}% <b>或</b>绝对差 ≤ {@value #LATENCY_ABS_MS}ms（取宽者）。两边的起点定义一致；
 *       wrk2 的"修正后"延迟含它自己按毫秒取整的发送滞后，不能做亚毫秒级的参照，只作信息项并排；</li>
 *   <li>节拍质量：harness 自己的发出滞后（dispatch lag）p99 ≤ {@value #DISPATCH_LAG_P99_MAX_MS}ms——harness 的 CO 修正延迟
 *       = 服务时间 + 发出滞后，滞后被独立测量并受此上界约束。</li>
 * </ol>
 * 前提：harness 全部轮次有效、正式轮窗口内无错误；wrk2 无 socket 错误、无非 2xx/3xx 响应。
 *
 * <p><b>闭环 = 发压容量标定</b>（vs wrk），不对吞吐设通过阈值：JVM 同步调用的 harness 每次调用要在发压虚拟线程与 IO 线程之间
 * 交接两次，固有开销决定了它的闭环上限，这不是测量误差。报告两边的吞吐与两次运行中客户端核 / 服务端核的 CPU 忙碌率，给出结论句；
 * 唯一的判定项：两次运行的 CPU 采样都成功，且都能判别瓶颈侧（某一侧平均 ≥ {@value #SATURATED_PCT}%）。
 *
 * <p>阈值是代码常量，不提供命令行开关：阈值放宽就不再是同一个校验。
 */
final class CrossValidation {

	static final String SCHEMA = "crossval/2";
	static final double OPEN_THROUGHPUT_VS_RATE_PCT = 2.0;
	static final double LATENCY_REL_PCT = 15.0;
	static final double LATENCY_ABS_MS = 0.1;
	static final double DISPATCH_LAG_P99_MAX_MS = 0.1;
	/** 判别瓶颈侧：一组核的平均忙碌率达到它即视为饱和。 */
	static final double SATURATED_PCT = 90.0;

	static final String WRK2_PACING_NOTE = "wrk2 的发送节拍是毫秒粒度：giltene/wrk2@44a94c1 的 src/wrk.c 第 604 行 "
			+ "`int msec_to_wait = round((time_usec_to_wait / 1000.0L) + 0.5);` 把距下次计划发送的等待向上取整到整毫秒，"
			+ "第 608–609 行 `aeCreateTimeEvent(thread->loop, msec_to_wait, delay_request, c, NULL)` 用 ae 定时器推迟发送，而 ae 的定时器"
			+ "本身按毫秒计时（src/ae.c 第 150–170 行 aeGetTime / aeAddMillisecondsToNow）。每个请求都比计划时刻晚发零点几到一毫秒多，"
			+ "这段滞后按修正口径计入 wrk2 的“修正后”延迟，所以它不能做亚毫秒级开环延迟的参照；“未修正”延迟从实际发出计时，不含这段滞后。";
	static final String CAUSE_ERRORS = "运行本身不干净：有错误、无效轮次或 socket 错误时数字不可比，先排除错误再比较。";
	static final String CAUSE_PACING = "发压端跟不上计划：调度滞后、压测端 CPU 不足或在途达到上限，达成吞吐低于目标 rate。";
	static final String CAUSE_MEASUREMENT = "测量起点之后的客户端开销：harness 的服务时间从发压虚拟线程开始执行算起，含虚拟线程调度、"
			+ "（连接都忙时的）排队、写出与唤醒两次跨线程交接；wrk2 的未修正延迟在单个 epoll 线程里从写出算起。";
	static final String CAUSE_DISPATCH = "harness 调度线程的发出滞后过大：调度线程被抢占（压测端核不足、未绑核）或整机停顿；看轮次表的“发出滞后 p99”与 CPU 列。";
	static final String CAUSE_NO_UNCORRECTED = "wrk2 输出里没有未修正分布：测量须带 -U（crossval.sh 已默认带）。";
	static final String CAUSE_CPU = "CPU 采样缺失或两侧都未饱和：无法判别瓶颈在压测端还是服务端（非 Linux、未绑核、或负载太轻）。";

	private static final Pattern CPU_AVERAGES = Pattern.compile("服务端核\\([^)]*\\) 平均 (\\d+)% · 压测端核\\([^)]*\\) 平均 (\\d+)%");

	private CrossValidation() {
	}

	/**
	 * 解析出的工具输出。延迟一律毫秒；工具没有输出的项为 null。
	 *
	 * @param uncorrectedP50Ms 等：wrk2 {@code -U} 输出的"未修正"分位数（从实际发出计时，不含 wrk2 自己的发送滞后）；没有该段时为 null
	 */
	record ToolResult(String tool, Double requestsPerSec, Long requests, Double durationSeconds, Double p50Ms, Double p75Ms,
			Double p90Ms, Double p99Ms, Double p999Ms, Double maxMs, long socketErrors, Map<String, Long> socketErrorsByType,
			long non2xx, Double uncorrectedP50Ms, Double uncorrectedP99Ms, Double uncorrectedP999Ms) {
	}

	static int command(Bench.Options options, PrintStream out) throws IOException {
		options.allowOnly("harness", "tool", "tool-output", "tool-command", "tool-version", "cpu-tool", "out");
		Path harnessFile = Path.of(options.required("harness"));
		String tool = options.required("tool");
		if (!tool.equals("wrk") && !tool.equals("wrk2")) {
			throw new Bench.UsageException("--tool must be wrk or wrk2, got \"" + tool + "\"");
		}
		Path toolOutput = Path.of(options.required("tool-output"));
		Path outDir = Path.of(options.required("out"));
		Object parsed = Json.parse(Files.readString(harnessFile, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map<?, ?> harness)) {
			throw new Bench.UsageException(harnessFile + " is not a bench result JSON object");
		}
		String toolText = Files.readString(toolOutput, StandardCharsets.UTF_8);
		ToolResult toolResult = parseToolOutput(tool, toolText);
		Map<String, Object> result = compare(harness, harnessFile.getFileName().toString(), toolResult, toolText,
				options.value("tool-command", null), options.value("tool-version", null), toolOutput.getFileName().toString(),
				options.value("cpu-tool", null));

		Files.createDirectories(outDir);
		String base = "crossval-" + result.get("mode") + "-" + LocalDateTime.now().format(BenchRunner.FILE_TIME);
		Path jsonFile = outDir.resolve(base + ".json");
		Files.writeString(jsonFile, Json.write(result), StandardCharsets.UTF_8);
		Map<?, ?> readBack = (Map<?, ?>) Json.parse(Files.readString(jsonFile, StandardCharsets.UTF_8));
		Path mdFile = outDir.resolve(base + ".md");
		Files.writeString(mdFile, render(readBack, jsonFile.getFileName().toString()), StandardCharsets.UTF_8);
		out.println("json: " + jsonFile.toAbsolutePath().normalize());
		out.println("markdown: " + mdFile.toAbsolutePath().normalize());
		if (result.get("conclusion") != null) {
			out.println("conclusion: " + result.get("conclusion"));
		}
		out.println("verdict: " + result.get("verdict"));
		return "PASS".equals(result.get("verdict")) ? Bench.OK : Bench.FAILED_ASSERTIONS;
	}

	// ------------------------------------------------------------------ 解析 wrk / wrk2 输出

	private static final Pattern PERCENTILE_LINE = Pattern.compile("^\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+([0-9]+(?:\\.[0-9]+)?)(us|ms|s|m|h)\\s*$");
	private static final Pattern REQUESTS_PER_SEC = Pattern.compile("^Requests/sec:\\s+([0-9]+(?:\\.[0-9]+)?)\\s*$");
	private static final Pattern REQUESTS_IN = Pattern.compile("^\\s*(\\d+) requests in ([0-9]+(?:\\.[0-9]+)?)(us|ms|s|m|h),");
	private static final Pattern SOCKET_ERRORS = Pattern.compile(
			"^\\s*Socket errors: connect (\\d+), read (\\d+), write (\\d+), timeout (\\d+)\\s*$");
	private static final Pattern NON_2XX = Pattern.compile("^\\s*Non-2xx or 3xx responses: (\\d+)\\s*$");
	private static final Pattern LATENCY_STATS = Pattern.compile(
			"^\\s*Latency\\s+([0-9]+(?:\\.[0-9]+)?)(us|ms|s|m|h)\\s+\\S+\\s+([0-9]+(?:\\.[0-9]+)?)(us|ms|s|m|h)\\s+.*$");

	/**
	 * 解析 {@code wrk --latency} / {@code wrk2 --latency} 的标准输出。分位数取 "Latency Distribution" 段：
	 * wrk 有 50/75/90/99；wrk2 的这一段来自 HdrHistogram（与其后的详细谱是同一个直方图），恰好给出 50/75/90/99/99.9/…，
	 * 详细谱的刻度里没有恰好 0.99 / 0.999 的行，所以不从谱上插值。
	 */
	static ToolResult parseToolOutput(String tool, String text) {
		Double rps = null;
		Long requests = null;
		Double seconds = null;
		Double maxMs = null;
		Map<Double, Double> percentiles = new TreeMap<>();
		Map<Double, Double> uncorrected = new TreeMap<>();
		Map<String, Long> socket = new LinkedHashMap<>();
		long non2xx = 0;
		Map<Double, Double> section = null;
		for (String line : text.split("\\R")) {
			if (line.contains("Latency Distribution")) {
				// wrk2 -U 另有一段 "Latency Distribution (HdrHistogram - Uncorrected Latency (measured without taking delayed starts into account))"
				section = line.contains("Uncorrected") ? uncorrected : percentiles;
				continue;
			}
			Matcher m = PERCENTILE_LINE.matcher(line);
			if (section != null && m.matches()) {
				section.put(Double.parseDouble(m.group(1)), toMillis(Double.parseDouble(m.group(2)), m.group(3)));
				continue;
			}
			section = null;
			if ((m = REQUESTS_PER_SEC.matcher(line)).matches()) {
				rps = Double.parseDouble(m.group(1));
			} else if ((m = REQUESTS_IN.matcher(line)).find()) {
				requests = Long.parseLong(m.group(1));
				seconds = toMillis(Double.parseDouble(m.group(2)), m.group(3)) / 1000.0;
			} else if ((m = SOCKET_ERRORS.matcher(line)).matches()) {
				socket.put("connect", Long.parseLong(m.group(1)));
				socket.put("read", Long.parseLong(m.group(2)));
				socket.put("write", Long.parseLong(m.group(3)));
				socket.put("timeout", Long.parseLong(m.group(4)));
			} else if ((m = NON_2XX.matcher(line)).matches()) {
				non2xx = Long.parseLong(m.group(1));
			} else if (maxMs == null && (m = LATENCY_STATS.matcher(line)).matches()) {
				maxMs = toMillis(Double.parseDouble(m.group(3)), m.group(4));
			}
		}
		if (rps == null) {
			throw new IllegalArgumentException(tool + " output has no \"Requests/sec:\" line (did the run fail?)");
		}
		if (!percentiles.containsKey(50.0) || !percentiles.containsKey(99.0)) {
			throw new IllegalArgumentException(tool + " output has no 50% / 99% latency lines (run it with --latency)");
		}
		long socketErrors = socket.values().stream().mapToLong(Long::longValue).sum();
		return new ToolResult(tool, rps, requests, seconds, percentiles.get(50.0), percentiles.get(75.0), percentiles.get(90.0),
				percentiles.get(99.0), percentiles.get(99.9), maxMs, socketErrors, socket, non2xx, uncorrected.get(50.0),
				uncorrected.get(99.0), uncorrected.get(99.9));
	}

	static double toMillis(double value, String unit) {
		return switch (unit) {
			case "us" -> value / 1000.0;
			case "ms" -> value;
			case "s" -> value * 1000.0;
			case "m" -> value * 60_000.0;
			case "h" -> value * 3_600_000.0;
			default -> throw new IllegalArgumentException("unknown time unit " + unit);
		};
	}


	// ------------------------------------------------------------------ 比较与判定

	static Map<String, Object> compare(Map<?, ?> harness, String harnessFileName, ToolResult tool, String toolText,
			String toolCommand, String toolVersion, String toolOutputFile, String cpuToolLine) {
		Map<?, ?> load = map(MarkdownReport.path(harness, "profile", "parsed", "load"));
		String mode = String.valueOf(load.get("model"));
		boolean open = "open".equals(mode);
		Map<?, ?> official = map(MarkdownReport.path(harness, "summary", "official"));
		Map<?, ?> round = officialRound(harness);
		Integer rate = load.get("rate") instanceof Number n ? n.intValue() : null;

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schema", SCHEMA);
		result.put("mode", mode);
		result.put("kind", open ? "measurement" : "capacity");
		result.put("created_at", ZonedDateTime.now().toOffsetDateTime().toString());
		result.put("question", open
				? "同一个 HTTP 目标、开环同一 rate，harness 与 " + tool.tool() + " 的发压节拍与测量是否一致——校验的是 harness 本身"
				: "同一个 HTTP 目标、闭环同一并发，harness 与 " + tool.tool() + " 各自能压到多少、瓶颈在哪一侧——发压容量标定");

		Map<String, Object> target = new LinkedHashMap<>();
		target.put("url", MarkdownReport.path(harness, "profile", "parsed", "target", "http", "url"));
		target.put("payload_bytes", MarkdownReport.path(harness, "profile", "parsed", "target", "payload", "size"));
		target.put("model", mode);
		target.put("rate", open ? rate : null);
		target.put("concurrency", open ? null : load.get("concurrency"));
		target.put("harness_connections", MarkdownReport.path(harness, "run", "target", "connections"));
		target.put("window", load.get("steady_window"));
		result.put("target", target);

		Map<String, Object> h = new LinkedHashMap<>();
		h.put("file", harnessFileName);
		h.put("profile", MarkdownReport.path(harness, "profile", "id"));
		h.put("label", harness.get("label"));
		h.put("official_round", MarkdownReport.path(harness, "summary", "official_round"));
		h.put("selection_rule", MarkdownReport.path(harness, "summary", "selection_rule"));
		h.put("throughput_rps", official.get("throughput_rps"));
		h.put("latency_ms", quantiles(round.get("latency_ms")));
		h.put("service_time_ms", quantiles(round.get("service_time_ms")));
		h.put("dispatch_lag_ms", quantiles(round.get("dispatch_lag_ms")));
		h.put("window_requests", official.get("window_requests"));
		h.put("window_errors", official.get("window_errors"));
		h.put("all_rounds_valid", MarkdownReport.path(harness, "validity", "all_rounds_valid"));
		h.put("backlog_triggered", MarkdownReport.path(harness, "validity", "backlog_triggered"));
		h.put("client_bound", MarkdownReport.path(harness, "validity", "client_bound"));
		h.put("passed", MarkdownReport.path(harness, "validity", "passed"));
		h.put("cpu", round.get("cpu"));
		h.put("spread", MarkdownReport.path(harness, "summary", "spread"));
		Map<?, ?> env = map(harness.get("environment"));
		h.put("cpus_allowed", env.get("cpus_allowed"));
		h.put("server_cpus_allowed", MarkdownReport.path(harness, "server_environment", "cpus_allowed"));
		h.put("available_cpus", env.get("available_cpus"));
		h.put("host_cpus", env.get("host_cpus"));
		h.put("jvm_args", env.get("jvm_args"));
		h.put("git_sha", env.get("git_sha"));
		h.put("git_dirty", env.get("git_dirty"));
		h.put("hostname", env.get("hostname"));
		h.put("cpu_model", env.get("cpu_model"));
		h.put("started_at", env.get("started_at"));
		result.put("harness", h);

		Map<String, Object> t = new LinkedHashMap<>();
		t.put("name", tool.tool());
		t.put("version", toolVersion);
		t.put("command", toolCommand);
		t.put("output_file", toolOutputFile);
		t.put("requests_per_sec", tool.requestsPerSec());
		t.put("requests", tool.requests());
		t.put("duration_s", tool.durationSeconds());
		Map<String, Object> corrected = new LinkedHashMap<>();
		corrected.put("p50", tool.p50Ms());
		corrected.put("p75", tool.p75Ms());
		corrected.put("p90", tool.p90Ms());
		corrected.put("p99", tool.p99Ms());
		corrected.put("p999", tool.p999Ms());
		corrected.put("max", tool.maxMs());
		t.put(open ? "corrected_latency_ms" : "latency_ms", corrected);
		if (open) {
			Map<String, Object> uncorrected = new LinkedHashMap<>();
			uncorrected.put("p50", tool.uncorrectedP50Ms());
			uncorrected.put("p99", tool.uncorrectedP99Ms());
			uncorrected.put("p999", tool.uncorrectedP999Ms());
			t.put("uncorrected_latency_ms", uncorrected);
		}
		t.put("socket_errors", tool.socketErrorsByType());
		t.put("non_2xx_3xx", tool.non2xx());
		int[] toolCpu = cpuAverages(cpuToolLine);
		Map<String, Object> tc = new LinkedHashMap<>();
		tc.put("sample", cpuToolLine);
		tc.put("server_busy_pct", toolCpu == null ? null : toolCpu[0]);
		tc.put("client_busy_pct", toolCpu == null ? null : toolCpu[1]);
		t.put("cpu", tc);
		t.put("raw_output", toolText);
		result.put("tool", t);

		List<Object> checks = new ArrayList<>();
		List<Object> rows = new ArrayList<>();
		List<String> causes = new ArrayList<>();
		double hThroughput = number(h.get("throughput_rps"));
		double tThroughput = tool.requestsPerSec();
		if (open) {
			boolean harnessClean = Boolean.TRUE.equals(h.get("all_rounds_valid")) && number(h.get("window_errors")) == 0;
			checks.add(check("harness_clean", "前提", "harness 全部轮次有效、正式轮窗口内无错误", "all_rounds_valid="
					+ h.get("all_rounds_valid") + ", window_errors=" + h.get("window_errors"), null, harnessClean));
			boolean toolClean = tool.socketErrors() == 0 && tool.non2xx() == 0;
			checks.add(check("tool_clean", "前提", tool.tool() + " 无 socket 错误、无非 2xx/3xx 响应", "socket_errors=" + tool.socketErrors()
					+ ", non_2xx_3xx=" + tool.non2xx(), null, toolClean));
			if (!harnessClean || !toolClean) {
				causes.add(CAUSE_ERRORS);
			}
			// a. 达成吞吐
			double hVsRate = pct(Math.abs(hThroughput - rate), rate);
			double tVsRate = pct(Math.abs(tThroughput - rate), rate);
			checks.add(check("harness_throughput_vs_rate", "a. 达成吞吐", "harness 相对目标 rate 偏差 ≤ " + fmt(OPEN_THROUGHPUT_VS_RATE_PCT) + "%",
					fmt(hThroughput) + " vs " + rate + " req/s", round3(hVsRate), hVsRate <= OPEN_THROUGHPUT_VS_RATE_PCT));
			checks.add(check("tool_throughput_vs_rate", "a. 达成吞吐", tool.tool() + " 相对目标 rate 偏差 ≤ " + fmt(OPEN_THROUGHPUT_VS_RATE_PCT) + "%",
					fmt(tThroughput) + " vs " + rate + " req/s", round3(tVsRate), tVsRate <= OPEN_THROUGHPUT_VS_RATE_PCT));
			if (hVsRate > OPEN_THROUGHPUT_VS_RATE_PCT || tVsRate > OPEN_THROUGHPUT_VS_RATE_PCT) {
				causes.add(CAUSE_PACING);
			}
			// b. 测量一致性：harness 服务时间 vs wrk2 未修正
			Map<?, ?> service = map(h.get("service_time_ms"));
			boolean measurementOk = true;
			for (String q : List.of("p50", "p99")) {
				double hv = number(service.get(q));
				Double tv = q.equals("p50") ? tool.uncorrectedP50Ms() : tool.uncorrectedP99Ms();
				boolean ok;
				Double rel = null;
				String measured;
				if (tv == null || Double.isNaN(hv)) {
					ok = false;
					measured = fmt3(hv) + " vs —（无未修正分布）";
					causes.add(CAUSE_NO_UNCORRECTED);
				} else {
					double abs = Math.abs(hv - tv);
					rel = round3(pct(abs, tv));
					ok = pct(abs, tv) <= LATENCY_REL_PCT || abs <= LATENCY_ABS_MS + 1e-9;
					measured = fmt3(hv) + " vs " + fmt3(tv) + " ms";
				}
				measurementOk &= ok;
				checks.add(check("measurement_" + q, "b. 测量一致性", "harness 服务时间 " + q + " vs " + tool.tool() + " 未修正 " + q + "：相对偏差 ≤ "
						+ fmt(LATENCY_REL_PCT) + "% 或绝对差 ≤ " + fmt(LATENCY_ABS_MS * 1000) + "µs（取宽者）", measured, rel, ok));
			}
			if (!measurementOk) {
				causes.add(CAUSE_MEASUREMENT);
			}
			// c. 节拍质量
			double lag = number(map(h.get("dispatch_lag_ms")).get("p99"));
			boolean lagOk = !Double.isNaN(lag) && lag <= DISPATCH_LAG_P99_MAX_MS + 1e-9;
			checks.add(check("dispatch_lag_p99", "c. 节拍质量", "harness 发出滞后 p99 ≤ " + fmt(DISPATCH_LAG_P99_MAX_MS * 1000) + "µs",
					fmt3(lag) + " ms", null, lagOk));
			if (!lagOk) {
				causes.add(CAUSE_DISPATCH);
			}
			rows.add(row("吞吐 (req/s)", hThroughput, tThroughput, true, "a"));
			Map<?, ?> latency = map(h.get("latency_ms"));
			rows.add(row("服务时间 vs 未修正 p50 (ms)", number(service.get("p50")), tool.uncorrectedP50Ms(), true, "b"));
			rows.add(row("服务时间 vs 未修正 p99 (ms)", number(service.get("p99")), tool.uncorrectedP99Ms(), true, "b"));
			rows.add(row("服务时间 vs 未修正 p99.9 (ms)", number(service.get("p999")), tool.uncorrectedP999Ms(), false, "b"));
			rows.add(row("修正后 p50 (ms)", number(latency.get("p50")), tool.p50Ms(), false, "info"));
			rows.add(row("修正后 p99 (ms)", number(latency.get("p99")), tool.p99Ms(), false, "info"));
			rows.add(row("修正后 p99.9 (ms)", number(latency.get("p999")), tool.p999Ms(), false, "info"));
			rows.add(row("max (ms)", number(latency.get("max")), tool.maxMs(), false, "info"));
		} else {
			String harnessSide = bottleneck(cpuBusy(h.get("cpu"), "server_busy_pct"), cpuBusy(h.get("cpu"), "client_busy_pct"));
			String toolSide = toolCpu == null ? null : bottleneck((double) toolCpu[0], (double) toolCpu[1]);
			boolean cpuOk = harnessSide != null && toolSide != null;
			checks.add(check("cpu_bottleneck", "唯一判定项", "两次运行的 CPU 采样都成功，且都能判别瓶颈侧（某一侧平均 ≥ " + fmt(SATURATED_PCT) + "%）",
					"harness 运行：" + sideText(harnessSide) + "；" + tool.tool() + " 运行：" + sideText(toolSide), null, cpuOk));
			if (!cpuOk) {
				causes.add(CAUSE_CPU);
			}
			result.put("bottleneck", Map.of("harness_run", harnessSide == null ? "unknown" : harnessSide, "tool_run",
					toolSide == null ? "unknown" : toolSide));
			result.put("conclusion", conclusion(hThroughput, harnessSide, tThroughput, toolSide, tool.tool(), target));
			Map<?, ?> latency = map(h.get("latency_ms"));
			rows.add(row("吞吐 (req/s)", hThroughput, tThroughput, false, "info"));
			rows.add(row("p50 (ms)", number(latency.get("p50")), tool.p50Ms(), false, "info"));
			rows.add(row("p99 (ms)", number(latency.get("p99")), tool.p99Ms(), false, "info"));
			rows.add(row("p99.9 (ms)", number(latency.get("p999")), tool.p999Ms(), false, "info"));
			rows.add(row("max (ms)", number(latency.get("max")), tool.maxMs(), false, "info"));
		}
		result.put("checks", checks);
		result.put("side_by_side", rows);
		boolean pass = checks.stream().allMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("passed")));
		result.put("verdict", pass ? "PASS" : "FAIL");
		result.put("possible_causes", pass ? new ArrayList<Object>() : new ArrayList<Object>(new java.util.LinkedHashSet<>(causes)));

		List<Object> notes = new ArrayList<>();
		if (open) {
			notes.add("相对偏差 = |harness − " + tool.tool() + "| / " + tool.tool() + "（以第三方工具为参照）。harness 取正式轮（"
					+ h.get("selection_rule") + "），统计窗口 " + load.get("steady_window") + "；" + tool.tool()
					+ " 先预热跑一次丢弃，再带 -U 测一次（同一次运行同时给出修正后与未修正分布），测量时长与 harness 的窗口长度相同。");
			notes.add(WRK2_PACING_NOTE);
			notes.add("harness 的修正后延迟（从计划时刻）= 服务时间 + 发出滞后；发出滞后由判定项 c 独立约束，所以服务时间与 wrk2 未修正延迟一致、"
					+ "发出滞后受控，就说明 harness 的开环延迟可信。修正后延迟两列只作信息项并排，不判定。");
			notes.add("p99.9 与 max 不参与判定。");
		} else {
			notes.add("闭环吞吐对比不是测量校验而是容量标定：闭环下吞吐 = 并发 / 往返时延，JVM 同步调用的 harness 每次调用要在发压虚拟线程与"
					+ " Netty IO 线程之间交接两次（写出、唤醒），这份固有开销决定了它的闭环上限；wrk 在单个 epoll 线程里收发，常常先压满服务端。"
					+ "延迟各列只作信息项（wrk 闭环不做 CO 修正）。");
			notes.add("CPU：harness 一侧取正式轮统计窗口内的 /proc/stat 采样（rounds[].cpu）；" + tool.tool() + " 一侧取测量运行中段的采样。");
		}
		notes.add("请求体：harness 发 " + target.get("payload_bytes") + " 字节固定种子随机内容，" + tool.tool()
				+ " 发 1024 字节固定内容（bench/bin/post-1k.lua）；回显开销与内容无关。");
		result.put("notes", notes);
		return result;
	}

	/** 结论句：两边的吞吐与瓶颈侧。 */
	static String conclusion(double harnessRps, String harnessSide, double toolRps, String toolSide, String tool, Map<String, Object> target) {
		String scope = "（本机、" + target.get("payload_bytes") + " 字节、" + target.get("concurrency") + " 连接）";
		String harnessPart = switch (harnessSide == null ? "unknown" : harnessSide) {
			case "client" -> "harness 闭环发压上限约 " + approx(harnessRps) + " req/s" + scope;
			case "server" -> "harness 闭环测得约 " + approx(harnessRps) + " req/s" + scope + "，瓶颈在服务端（不是 harness 的上限）";
			case "both" -> "harness 闭环测得约 " + approx(harnessRps) + " req/s" + scope + "，压测端与服务端同时饱和";
			default -> "harness 闭环测得约 " + approx(harnessRps) + " req/s" + scope + "，瓶颈侧无法判别";
		};
		String toolPart = switch (toolSide == null ? "unknown" : toolSide) {
			case "server" -> tool + " 测得的服务端上限约 " + approx(toolRps) + " req/s";
			case "client" -> tool + " 测得约 " + approx(toolRps) + " req/s，瓶颈在 " + tool + " 一侧（不是服务端上限）";
			case "both" -> tool + " 测得约 " + approx(toolRps) + " req/s，压测端与服务端同时饱和";
			default -> tool + " 测得约 " + approx(toolRps) + " req/s，瓶颈侧无法判别";
		};
		return harnessPart + "；" + toolPart + "。";
	}

	/** 某一侧平均 ≥ {@value #SATURATED_PCT}% 即为瓶颈侧；两侧都饱和为 both；都没饱和或缺数据为 null（无法判别）。 */
	static String bottleneck(Double serverBusy, Double clientBusy) {
		if (serverBusy == null || clientBusy == null || serverBusy.isNaN() || clientBusy.isNaN()) {
			return null;
		}
		boolean server = serverBusy >= SATURATED_PCT;
		boolean client = clientBusy >= SATURATED_PCT;
		return server && client ? "both" : server ? "server" : client ? "client" : null;
	}

	private static String sideText(String side) {
		return side == null ? "无法判别" : switch (side) {
			case "client" -> "压测端饱和";
			case "server" -> "服务端饱和";
			default -> "两侧都饱和";
		};
	}

	private static Double cpuBusy(Object cpu, String key) {
		return cpu instanceof Map<?, ?> m && m.get(key) instanceof Number n ? n.doubleValue() : null;
	}

	/** 正式轮那一项 rounds[]；找不到时空 map。 */
	private static Map<?, ?> officialRound(Map<?, ?> harness) {
		Object officialRound = MarkdownReport.path(harness, "summary", "official_round");
		for (Object r : list(harness.get("rounds"))) {
			if (r instanceof Map<?, ?> m && officialRound != null && officialRound.equals(m.get("round"))) {
				return m;
			}
		}
		return Map.of();
	}

	private static Map<String, Object> quantiles(Object series) {
		Map<?, ?> s = map(series);
		Map<String, Object> q = new LinkedHashMap<>();
		for (String key : List.of("p50", "p99", "p999", "max")) {
			q.put(key, s.get(key));
		}
		return q;
	}

	private static Map<String, Object> check(String key, String group, String criterion, String measured, Double relPct, boolean passed) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("key", key);
		c.put("group", group);
		c.put("criterion", criterion);
		c.put("measured", measured);
		c.put("rel_dev_pct", relPct);
		c.put("passed", passed);
		return c;
	}

	private static Map<String, Object> row(String metric, double harness, Double tool, boolean judged, String group) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("metric", metric);
		r.put("group", group);
		r.put("judged", judged);
		r.put("harness", Double.isNaN(harness) ? null : round3(harness));
		r.put("tool", tool == null ? null : round3(tool));
		boolean both = !Double.isNaN(harness) && tool != null;
		r.put("diff", both ? round3(harness - tool) : null);
		r.put("rel_dev_pct", both && tool != 0 ? round3(Math.abs(harness - tool) / tool * 100) : null);
		return r;
	}

	// ------------------------------------------------------------------ Markdown

	static String render(Map<?, ?> json, String fileName) {
		Map<?, ?> h = map(json.get("harness"));
		Map<?, ?> t = map(json.get("tool"));
		Map<?, ?> target = map(json.get("target"));
		String tool = String.valueOf(t.get("name"));
		boolean open = "open".equals(json.get("mode"));
		StringBuilder md = new StringBuilder();
		md.append(open ? "# 交叉验证：harness vs " + tool + "（开环，测量校验）\n\n" : "# 发压容量标定：harness vs " + tool + "（闭环）\n\n");
		md.append("> 结果文件：`").append(fileName).append("` · 判定：**").append(json.get("verdict")).append("**\n\n");
		md.append("**问题**：").append(json.get("question")).append("\n\n");
		if (json.get("conclusion") != null) {
			md.append("**结论**：").append(json.get("conclusion")).append("\n\n");
		}

		md.append("## 设置\n\n| 项 | 值 |\n|---|---|\n");
		md.append("| 目标 | `").append(target.get("url")).append("`，POST ").append(target.get("payload_bytes")).append(" 字节，原样回显 |\n");
		md.append("| 负载 | ").append(open ? "开环 " + target.get("rate") + " req/s" : "闭环 " + target.get("concurrency") + " 并发")
				.append("，harness 连接 ").append(target.get("harness_connections")).append(" 条 |\n");
		md.append("| harness | 口径卡 `").append(h.get("profile")).append("`，结果 `").append(h.get("file")).append("`，正式轮第 ")
				.append(h.get("official_round")).append(" 轮，统计窗口 ").append(target.get("window")).append("；全部轮次有效 ")
				.append(h.get("all_rounds_valid")).append("，client_bound ").append(h.get("client_bound")).append(" |\n");
		md.append("| ").append(tool).append(" | `").append(t.get("command")).append("`").append(t.get("version") == null ? "" : "（" + t.get("version") + "）")
				.append(" |\n");
		md.append("| CPU 亲和 | 服务端 ").append(h.get("server_cpus_allowed")).append(" · 压测端（harness 与 ").append(tool).append("）")
				.append(h.get("cpus_allowed")).append(" · 可用 / 主机核数 ").append(h.get("available_cpus")).append(" / ")
				.append(h.get("host_cpus")).append(" |\n");
		md.append("| 机器 | ").append(h.get("hostname")).append("，").append(h.get("cpu_model")).append("，git ").append(shortSha(h.get("git_sha")))
				.append(Boolean.TRUE.equals(h.get("git_dirty")) ? "（工作区有未提交改动）" : "").append(" |\n\n");

		md.append("## 判定\n\n| 项 | 检查 | 判据 | 实测 | 相对偏差 | 结果 |\n|---|---|---|---|---|---|\n");
		for (Object o : list(json.get("checks"))) {
			Map<?, ?> c = map(o);
			md.append("| ").append(c.get("group")).append(" | `").append(c.get("key")).append("` | ").append(c.get("criterion")).append(" | ")
					.append(c.get("measured")).append(" | ").append(c.get("rel_dev_pct") == null ? "—" : num(c.get("rel_dev_pct")) + "%")
					.append(" | ").append(Boolean.TRUE.equals(c.get("passed")) ? "通过" : "**未通过**").append(" |\n");
		}
		md.append('\n');

		md.append("## 并排\n\n| 指标 | harness（正式轮） | ").append(tool).append(" | 差（harness − ").append(tool)
				.append("） | 相对偏差 | 性质 |\n|---|---|---|---|---|---|\n");
		for (Object o : list(json.get("side_by_side"))) {
			Map<?, ?> r = map(o);
			String nature = Boolean.TRUE.equals(r.get("judged")) ? "判定（" + r.get("group") + "）"
					: "info".equals(r.get("group")) ? "信息项，不判定" : "并列，不判定";
			md.append("| ").append(r.get("metric")).append(" | ").append(num(r.get("harness"))).append(" | ").append(num(r.get("tool")))
					.append(" | ").append(num(r.get("diff"))).append(" | ").append(r.get("rel_dev_pct") == null ? "—" : num(r.get("rel_dev_pct")) + "%")
					.append(" | ").append(nature).append(" |\n");
		}
		if (open) {
			Map<?, ?> lag = map(h.get("dispatch_lag_ms"));
			md.append("\nharness 发出滞后（计划时刻 → 调度线程到点）：p50 ").append(num(lag.get("p50"))).append(" / p99 ").append(num(lag.get("p99")))
					.append(" / p99.9 ").append(num(lag.get("p999"))).append(" / max ").append(num(lag.get("max"))).append(" ms。\n");
		}
		md.append('\n');

		md.append("## CPU 忙碌率（不参与判定").append(open ? "" : "，用于判别瓶颈侧").append("）\n\n| 运行 | 服务端核 | 压测端核 | 来源 |\n|---|---|---|---|\n");
		Map<?, ?> hc = map(h.get("cpu"));
		md.append("| harness | ").append(hc.isEmpty() ? String.valueOf(h.get("cpu")) : hc.get("server_cpus") + "：" + CpuSampler.pct(hc.get("server_busy_pct")))
				.append(" | ").append(hc.isEmpty() ? "—" : hc.get("client_cpus") + "：" + CpuSampler.pct(hc.get("client_busy_pct")))
				.append(" | 正式轮统计窗口（rounds[].cpu） |\n");
		Map<?, ?> tc = map(t.get("cpu"));
		md.append("| ").append(tool).append(" | ").append(tc.get("server_busy_pct") == null ? "—" : tc.get("server_busy_pct") + "%").append(" | ")
				.append(tc.get("client_busy_pct") == null ? "—" : tc.get("client_busy_pct") + "%").append(" | ")
				.append(tc.get("sample") == null ? "未采样" : String.valueOf(tc.get("sample")).replace("|", "/")).append(" |\n\n");

		List<Object> causes = list(json.get("possible_causes"));
		if (!causes.isEmpty()) {
			md.append("## 可能原因\n\n");
			for (Object cause : causes) {
				md.append("- ").append(cause).append('\n');
			}
			md.append('\n');
		}
		md.append("## 说明\n\n");
		for (Object note : list(json.get("notes"))) {
			md.append("- ").append(note).append('\n');
		}
		md.append("- 数字来自固定的一台本机，只用于校验 harness 与第三方工具，不代表 RPC 框架的性能。\n");
		return md.toString().replace("\r", "");
	}

	// ------------------------------------------------------------------ 小工具

	/** crossval.sh 的 CPU 采样行 → {服务端平均, 压测端平均}；格式不对时 null。 */
	static int[] cpuAverages(String line) {
		if (line == null) {
			return null;
		}
		Matcher m = CPU_AVERAGES.matcher(line);
		return m.find() ? new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) } : null;
	}

	/** 结论句里的约数：取三位有效数字（134,276.9 → 134,000）。 */
	static String approx(double rps) {
		if (Double.isNaN(rps) || rps <= 0) {
			return "—";
		}
		double magnitude = Math.pow(10, Math.max(0, Math.floor(Math.log10(rps)) - 2));
		return String.format(Locale.ROOT, "%,d", (long) (Math.round(rps / magnitude) * magnitude));
	}

	private static double pct(double part, double whole) {
		return whole == 0 ? Double.POSITIVE_INFINITY : part / whole * 100;
	}

	private static double number(Object value) {
		return value instanceof Number n ? n.doubleValue() : Double.NaN;
	}

	private static double round3(double v) {
		return RoundRecorder.round3(v);
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
	}

	private static String fmt3(double v) {
		return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.3f", v);
	}

	private static String num(Object value) {
		return value instanceof Number n ? String.format(Locale.ROOT, "%.3f", n.doubleValue()) : "—";
	}

	private static String shortSha(Object sha) {
		String s = String.valueOf(sha);
		return s.length() >= 7 ? s.substring(0, 7) : s;
	}

	private static Map<?, ?> map(Object value) {
		return value instanceof Map<?, ?> m ? m : Map.of();
	}

	private static List<Object> list(Object value) {
		return value instanceof List<?> l ? new ArrayList<>(l) : List.of();
	}
}
