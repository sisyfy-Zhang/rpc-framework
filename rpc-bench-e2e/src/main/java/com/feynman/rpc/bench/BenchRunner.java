package com.feynman.rpc.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code run} 子命令：读卡 → 连目标 → 跑 rounds 轮 → 汇总与判定 → 写 JSON → 从<b>读回的</b> JSON 生成 Markdown。
 *
 * <p>判定（{@code validity.passed}）= 每轮都有效（预热完成、没有 backlog_dropped、窗口内有成功调用）且全部断言通过；
 * 不通过时退出码 2。实验必须能失败：没有判定的数字只是"跑出来的数"。
 */
final class BenchRunner {

	static final int SCHEMA_VERSION = 1;
	static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
	static final String CLOSED_LOOP_STATEMENT = "闭环负载（closed）低估尾延迟：每个虚拟用户等上一次调用返回才发下一次，服务端一慢，"
			+ "发压随之变慢，排队时间不会被计入（coordinated omission）。本卡的 p99 / p99.9 只能当下界看，尾延迟以开环卡为准。";
	static final String OPEN_LOOP_STATEMENT = "开环负载（open）：延迟从计划发出时刻算起，发压端自身的调度滞后（见“发出滞后”列）也计入延迟；"
			+ "发出滞后的 p99 明显大于服务时间时，说明压测端本身成了瓶颈，数字不代表被测服务。";

	static final String OPEN_LOOP_BUSY_SPIN_STATEMENT = "开环调度线程以忙等对齐节拍，独占一个核；本报告的 CPU 亲和见环境表。";

	static final String CLIENT_BOUND_STATEMENT = "本卡吞吐受发压端 CPU 限制（有效轮里客户端核平均忙碌率超过 " + (int) CpuSampler.CLIENT_BOUND_PCT
			+ "%），是 harness 的发压上限而非服务端上限。";

	/** 有效轮里压测端 GC 停顿占统计窗口的比例超过它（%）时，报告附声明：发压端自己的停顿会抬高尾延迟。 */
	static final double CLIENT_GC_PAUSE_PCT = 1.0;

	record Outcome(int exitCode, Path json, Path markdown, Map<String, Object> result) {
	}

	private BenchRunner() {
	}

	static Outcome run(Profile profile, CallTarget.Endpoint endpoint, Path outDir, String label, List<String> commandLine,
			PrintStream out) throws IOException {
		return run(profile, endpoint, outDir, label, commandLine, out, null);
	}

	/**
	 * @param metrics 非 null 时（{@code --metrics-port}）被压客户端带指标 Filter；{@code environment.metrics_enabled} 如实记录，
	 *                正式数字默认不开（指标 Filter 在热路径上），开了要在 label 里写明
	 */
	static Outcome run(Profile profile, CallTarget.Endpoint endpoint, Path outDir, String label, List<String> commandLine,
			PrintStream out, MeterRegistry metrics) throws IOException {
		Instant started = Instant.now();
		Map<String, Object> environment = EnvironmentProbe.collect();
		environment.put("metrics_enabled", metrics != null);
		environment.put("started_at", started.toString());

		List<Map<String, Object>> rounds = new ArrayList<>();
		Map<String, Object> targetDescription;
		Object serverEnvironment;
		CpuSampler cpu = null;
		try (CallTarget target = CallTarget.create(profile, endpoint, metrics)) {
			targetDescription = target.describe();
			serverEnvironment = serverEnvironment(target, environment, out);
			Object serverCpus = serverEnvironment instanceof Map<?, ?> m ? m.get("cpus_allowed") : null;
			cpu = CpuSampler.create(environment.get("cpus_allowed"), serverCpus, profile.isolation().sameHost());
			try {
				target.verify();
			} catch (ErrorClassifier.PayloadMismatchException e) {
				throw e;
			} catch (RuntimeException e) {
				// 例如卡片故意把 timeout 设得极小：不在这里中止，失败会在轮次与断言里如实体现
				out.println("warning: pre-run verification call failed (" + ErrorClassifier.classify(e) + "): " + e.getMessage());
			}
			for (int round = 1; round <= profile.load().rounds(); round++) {
				out.printf(Locale.ROOT, "round %d/%d: %s for %s ...%n", round, profile.load().rounds(), profile.load().model(),
						Durations.format(profile.load().duration()));
				RoundRecorder recorder = LoadDriver.runRound(round, profile, target, cpu);
				Map<String, Object> json = recorder.toJson();
				rounds.add(json);
				out.printf(Locale.ROOT, "round %d: window requests=%d throughput=%.1f req/s p50=%s p99=%s errors=%d valid=%s cpu=%s%n", round,
						recorder.windowSuccesses() + recorder.windowErrors(), recorder.throughput(),
						((Map<?, ?>) json.get("latency_ms")).get("p50"), ((Map<?, ?>) json.get("latency_ms")).get("p99"),
						recorder.windowErrors(), json.get("valid"), CpuSampler.brief(json.get("cpu")));
			}
		}
		Instant ended = Instant.now();
		environment.put("ended_at", ended.toString());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("schema_version", SCHEMA_VERSION);
		result.put("label", label);
		Map<String, Object> profileJson = new LinkedHashMap<>();
		profileJson.put("id", profile.id());
		profileJson.put("parsed", profile.toJson());
		profileJson.put("raw_yaml", profile.rawYaml());
		result.put("profile", profileJson);
		Map<String, Object> run = new LinkedHashMap<>();
		run.put("command", String.join(" ", commandLine));
		run.put("target", targetDescription);
		result.put("run", run);
		result.put("environment", environment);
		result.put("server_environment", serverEnvironment);
		result.put("rounds", rounds);
		Map<String, Object> summary = summary(profile, rounds);
		result.put("summary", summary);
		Map<String, Object> validity = validity(profile, rounds, summary, targetDescription);
		result.put("validity", validity);

		Files.createDirectories(outDir);
		String base = profile.id() + "-" + LocalDateTime.ofInstant(started, ZoneId.systemDefault()).format(FILE_TIME);
		Path jsonFile = outDir.resolve(base + ".json");
		String jsonText = Json.write(result);
		Files.writeString(jsonFile, jsonText, StandardCharsets.UTF_8);
		// Markdown 只从写出的 JSON 读回生成：报告里不可能出现 JSON 里没有的数字
		Map<?, ?> parsed = (Map<?, ?>) Json.parse(Files.readString(jsonFile, StandardCharsets.UTF_8));
		Path mdFile = outDir.resolve(base + ".md");
		Files.writeString(mdFile, MarkdownReport.render(parsed, jsonFile.getFileName().toString()), StandardCharsets.UTF_8);

		boolean passed = Boolean.TRUE.equals(validity.get("passed"));
		return new Outcome(passed ? 0 : 2, jsonFile, mdFile, result);
	}

	/**
	 * 预热前向服务端取一次环境，按压测端 environment 的键序排列（服务端多出的键排在后面）。
	 * 取不到时记 {@value EnvironmentProbe#UNKNOWN}，不中止压测。
	 */
	private static Object serverEnvironment(CallTarget target, Map<String, Object> clientEnvironment, PrintStream out) {
		Map<String, String> remote;
		try {
			remote = target.serverEnvironment();
		} catch (RuntimeException e) {
			out.println("warning: could not read the server environment (" + ErrorClassifier.classify(e) + "): " + e.getMessage());
			return EnvironmentProbe.UNKNOWN;
		}
		if (remote == null || remote.isEmpty()) {
			return EnvironmentProbe.UNKNOWN;
		}
		Map<String, Object> ordered = new LinkedHashMap<>();
		for (String key : clientEnvironment.keySet()) {
			if (remote.containsKey(key)) {
				ordered.put(key, remote.get(key));
			}
		}
		new java.util.TreeMap<>(remote).forEach(ordered::putIfAbsent);
		return ordered;
	}

	/** 正式轮：closed 取吞吐中位数那一轮，open 取 p99 中位数那一轮（偶数轮取下中位）；有有效轮时只在有效轮里选。 */
	static Map<String, Object> summary(Profile profile, List<Map<String, Object>> rounds) {
		boolean closed = profile.load().closed();
		List<Map<String, Object>> candidates = rounds.stream().filter(r -> Boolean.TRUE.equals(r.get("valid"))).toList();
		boolean fromValid = !candidates.isEmpty();
		if (!fromValid) {
			candidates = rounds;
		}
		Comparator<Map<String, Object>> order = closed
				? Comparator.comparingDouble(r -> number(r.get("throughput_rps")))
				: Comparator.comparingDouble(r -> number(latency(r, "p99")));
		List<Map<String, Object>> sorted = new ArrayList<>(candidates);
		sorted.sort(order.thenComparing(r -> number(r.get("round"))));
		Map<String, Object> official = sorted.get((sorted.size() - 1) / 2);

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("official_round", official.get("round"));
		summary.put("selection_rule", (closed ? "闭环：窗口吞吐的中位数那一轮" : "开环：窗口 p99 的中位数那一轮")
				+ (rounds.size() % 2 == 0 ? "，偶数轮取下中位" : "") + (fromValid ? "" : "；没有有效轮，在全部轮次里选"));
		Map<String, Object> officialMetrics = new LinkedHashMap<>();
		officialMetrics.put("throughput_rps", official.get("throughput_rps"));
		for (String q : List.of("p50", "p90", "p95", "p99", "p999", "max", "mean")) {
			officialMetrics.put(q + "_ms", latency(official, q));
		}
		officialMetrics.put("window_requests", ((Map<?, ?>) official.get("window")).get("requests"));
		officialMetrics.put("window_errors", ((Map<?, ?>) official.get("window")).get("errors"));
		summary.put("official", officialMetrics);

		Map<String, Object> spread = new LinkedHashMap<>();
		spread.put("throughput_rps", spread(rounds.stream().map(r -> r.get("throughput_rps")).toList()));
		for (String q : List.of("p50", "p95", "p99", "p999", "max")) {
			spread.put(q + "_ms", spread(rounds.stream().map(r -> latency(r, q)).toList()));
		}
		summary.put("spread", spread);
		return summary;
	}

	private static Map<String, Object> spread(List<Object> values) {
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		int n = 0;
		for (Object v : values) {
			if (v instanceof Number num) {
				min = Math.min(min, num.doubleValue());
				max = Math.max(max, num.doubleValue());
				n++;
			}
		}
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("rounds", n);
		s.put("min", n == 0 ? null : min);
		s.put("max", n == 0 ? null : max);
		s.put("range", n == 0 ? null : RoundRecorder.round3(max - min));
		s.put("range_pct", n == 0 || min <= 0 ? null : RoundRecorder.round3((max - min) / min * 100));
		return s;
	}

	static Map<String, Object> validity(Profile profile, List<Map<String, Object>> rounds, Map<String, Object> summary,
			Map<String, Object> target) {
		Map<String, Object> validity = new LinkedHashMap<>();
		boolean warmup = rounds.stream().allMatch(r -> Boolean.TRUE.equals(r.get("warmup_completed")));
		boolean backlog = rounds.stream().anyMatch(r -> number(r.get("backlog_dropped")) > 0);
		boolean allValid = rounds.stream().allMatch(r -> Boolean.TRUE.equals(r.get("valid")));
		validity.put("warmup_completed", warmup);
		validity.put("backlog_triggered", backlog);
		validity.put("all_rounds_valid", allValid);
		boolean clientBound = CpuSampler.clientBound(rounds);
		validity.put("client_bound", clientBound);

		List<Object> statements = new ArrayList<>();
		statements.add(profile.load().closed() ? CLOSED_LOOP_STATEMENT : OPEN_LOOP_STATEMENT);
		if (!profile.load().closed()) {
			statements.add(OPEN_LOOP_BUSY_SPIN_STATEMENT);
		}
		if (backlog) {
			statements.add("开环 backlog 触发：在途调用达到 rate × timeout × 2，压测端已无法按计划发出，对应轮次无效。");
		}
		if (clientBound) {
			statements.add(CLIENT_BOUND_STATEMENT);
		}
		Map<String, Object> worstClientGc = worstClientGcRound(rounds);
		validity.put("client_gc_pause_pct_max", worstClientGc == null ? null : GcSampler.pausePct(worstClientGc, "client"));
		if (worstClientGc != null && GcSampler.pausePct(worstClientGc, "client") > CLIENT_GC_PAUSE_PCT) {
			statements.add(String.format(Locale.ROOT, "发压端 GC 停顿占窗口 %.1f%%（第 %s 轮，有效轮中最高），可能抬高尾延迟。",
					GcSampler.pausePct(worstClientGc, "client"), worstClientGc.get("round")));
		}
		statements.add("错误不计入延迟分布与吞吐；延迟分位数只统计完成时刻落在统计窗口内的成功调用。");
		validity.put("statements", statements);
		validity.put("topology", topology(profile, target));

		List<Object> asserts = new ArrayList<>();
		Map<?, ?> official = (Map<?, ?>) summary.get("official");
		boolean assertsPassed = true;
		for (Profile.Assertion assertion : profile.assertions()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("key", assertion.key());
			entry.put("threshold", assertion.thresholdText());
			double actual;
			String actualText;
			String scope;
			switch (assertion.metric()) {
				case "errors" -> {
					// 错误按全部轮次的窗口内错误之和判定：任何一轮出错都算
					long sum = 0;
					for (Map<String, Object> r : rounds) {
						sum += (long) number(((Map<?, ?>) r.get("window")).get("errors"));
					}
					actual = sum;
					actualText = String.valueOf(sum);
					scope = "全部轮次窗口内错误之和";
				}
				case "throughput" -> {
					actual = number(official.get("throughput_rps"));
					actualText = String.format(Locale.ROOT, "%.1f req/s", actual);
					scope = "正式轮";
				}
				default -> {
					Object value = official.get(assertion.metric() + "_ms");
					actual = value instanceof Number n ? n.doubleValue() : Double.NaN;
					actualText = value == null ? "—（无成功调用）" : value + "ms";
					scope = "正式轮";
				}
			}
			boolean passed = !Double.isNaN(actual) && (assertion.upperBound() ? actual <= assertion.threshold() : actual >= assertion.threshold());
			entry.put("actual", actualText);
			entry.put("actual_value", Double.isNaN(actual) ? null : actual);
			entry.put("scope", scope);
			entry.put("passed", passed);
			asserts.add(entry);
			assertsPassed &= passed;
		}
		validity.put("asserts", asserts);
		validity.put("asserts_passed", assertsPassed);
		validity.put("passed", allValid && assertsPassed);
		return validity;
	}

	/** 有效轮里压测端 GC 停顿占比最高的那一轮；没有有效轮或都没有 GC 数据时 null。 */
	private static Map<String, Object> worstClientGcRound(List<Map<String, Object>> rounds) {
		Map<String, Object> worst = null;
		for (Map<String, Object> r : rounds) {
			Double pct = GcSampler.pausePct(r, "client");
			if (Boolean.TRUE.equals(r.get("valid")) && pct != null && (worst == null || pct > GcSampler.pausePct(worst, "client"))) {
				worst = r;
			}
		}
		return worst;
	}

	private static String topology(Profile profile, Map<String, Object> target) {
		Profile.Isolation iso = profile.isolation();
		String processes = "separate_jvm".equals(iso.clientProcess()) && "separate_jvm".equals(iso.serverProcess())
				? "client 与 server 是两个独立 JVM"
				: "client " + iso.clientProcess() + "、server " + iso.serverProcess();
		String host = iso.sameHost() ? "同一台机器（loopback）" : "不同机器";
		return processes + "，" + host + "，" + profile.target().providers() + " 个提供者（" + target.get("providers") + "）"
				+ "，经 " + target.get("address") + " 发现；卡片声明的注册中心：" + iso.registry() + "。";
	}

	private static Object latency(Map<String, Object> round, String q) {
		return ((Map<?, ?>) round.get("latency_ms")).get(q);
	}

	private static double number(Object value) {
		return value instanceof Number n ? n.doubleValue() : Double.NaN;
	}
}
