package com.feynman.rpc.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * 卡片声明了 {@code load.steps} 或 {@code load.faults} 时，一轮的每秒时间线、阶梯分段统计与故障事件（进 {@code rounds[].timeline} /
 * {@code rounds[].steps} / {@code rounds[].faults}）。没有阶梯与故障的卡不创建它，JSON 与原口径完全相同。
 *
 * <ul>
 *   <li><b>按完成时刻归秒</b>（与 {@code timeseries} 同一口径）：成功数、按类型的失败数、成功调用延迟的 p50 / p99（从计划时刻算，
 *       每秒一个 2 位有效数字的直方图）；</li>
 *   <li><b>每秒采样</b>（{@link #sample}，采样线程在本轮开始后第 k 秒取第 k 个样本）：压测端客户端的累计计数（按节点的尝试 / 失败 / 超时、
 *       重试与换节点）与每个提供者的 {@code counters()}（执行器 / 停机拒绝、排队数、排队等待累计直方图），相邻两个样本做差得到第 k 秒的值。
 *       节点份额 = 该秒各节点的<b>尝试数</b>（含重试，按发出计）/ 总尝试数；</li>
 *   <li><b>阶梯</b>：每一级的请求 / 成功 / 失败、吞吐、延迟分位数（卡片的精度）、失败分类，以及该级起止样本之差得到的服务端拒绝数与排队等待；</li>
 *   <li><b>故障</b>：计划时刻、驱动脚本回报的实际执行时刻与结果，以及按时间线算出的恢复时间（见 {@link #recovery}）。</li>
 * </ul>
 */
final class FaultTimeline {

	/** 每秒延迟直方图：2 位有效数字（p99 精度 1%），最低 1µs。 */
	static final int SECOND_DIGITS = 2;
	static final long SECOND_LOWEST_NANOS = 1_000;
	/** 恢复判据：连续这么多秒满足条件。 */
	static final int RECOVERY_CLEAN_SECONDS = 3;
	/** p99 恢复判据：每秒 p99 不超过基线的这个倍数。 */
	static final double P99_RECOVERY_FACTOR = 2.0;
	/** 份额恢复判据：目标节点每秒份额不低于基线份额的这个比例。 */
	static final double SHARE_RECOVERY_FRACTION = 0.8;

	private final long startNanos;
	private final long maxTrackable;
	private final int seconds;
	private final int roundSeconds;
	private final AtomicLongArray success;
	private final AtomicLongArray errors;
	private final Map<String, AtomicLongArray> errorsByType = new ConcurrentHashMap<>();
	private final Histogram[] latency;
	private final List<Profile.Step> steps;
	private final long[] stepStartNanos;
	private final long[] stepEndNanos;
	private final Histogram[] stepLatency;
	private final AtomicLongArray stepSuccess;
	private final AtomicLongArray stepErrors;
	private final List<Map<String, java.util.concurrent.atomic.LongAdder>> stepErrorsByType = new ArrayList<>();
	/** 第 k 个样本（本轮开始后第 k 秒取）：{@code {offset_ms, client, providers}}。 */
	private final AtomicReferenceArray<Sample> samples;
	private final List<Profile.Fault> faults;
	private volatile List<Map<String, Object>> executed = List.of();
	private volatile Map<String, String> topology = Map.of();

	record Sample(double offsetMs, Map<String, String> client, Map<String, Map<String, String>> providers) {
	}

	FaultTimeline(long startNanos, Profile profile, List<Profile.Fault> faults) {
		Profile.Load load = profile.load();
		this.startNanos = startNanos;
		this.maxTrackable = profile.measure().histogramMax().toNanos();
		this.roundSeconds = (int) Math.ceil(load.duration().toNanos() / 1e9);
		this.seconds = (int) Math.ceil((load.duration().toNanos() + load.timeout().toNanos()) / 1e9) + 1;
		this.success = new AtomicLongArray(seconds);
		this.errors = new AtomicLongArray(seconds);
		this.latency = new Histogram[seconds];
		for (int i = 0; i < seconds; i++) {
			latency[i] = new ConcurrentHistogram(SECOND_LOWEST_NANOS, Math.max(SECOND_LOWEST_NANOS * 2, maxTrackable), SECOND_DIGITS);
		}
		this.steps = load.steps();
		int n = steps.size();
		this.stepStartNanos = new long[n];
		this.stepEndNanos = new long[n];
		this.stepLatency = new Histogram[n];
		this.stepSuccess = new AtomicLongArray(n);
		this.stepErrors = new AtomicLongArray(n);
		long offset = 0;
		for (int k = 0; k < n; k++) {
			stepStartNanos[k] = startNanos + offset;
			offset += steps.get(k).duration().toNanos();
			stepEndNanos[k] = startNanos + offset;
			stepLatency[k] = new ConcurrentHistogram(1, maxTrackable, profile.measure().significantDigits());
			stepErrorsByType.add(new ConcurrentHashMap<>());
		}
		this.samples = new AtomicReferenceArray<>(seconds + 1);
		this.faults = List.copyOf(faults);
	}

	private int second(long nanos) {
		long index = (nanos - startNanos) / 1_000_000_000L;
		return index >= 0 && index < seconds ? (int) index : -1;
	}

	/** 完成时刻落在哪一级（按完成时刻归级，与窗口同一口径）；最后一级之后完成的（在途尾巴）算最后一级。 */
	private int step(long nanos) {
		for (int k = 0; k < stepStartNanos.length; k++) {
			if (nanos < stepEndNanos[k] || k == stepStartNanos.length - 1) {
				return nanos >= stepStartNanos[0] ? k : -1;
			}
		}
		return -1;
	}

	void success(long intendedStart, long done) {
		long value = Math.min(maxTrackable, Math.max(1, done - intendedStart));
		int s = second(done);
		if (s >= 0) {
			success.incrementAndGet(s);
			latency[s].recordValue(Math.max(SECOND_LOWEST_NANOS, value));
		}
		int k = step(done);
		if (k >= 0) {
			stepSuccess.incrementAndGet(k);
			stepLatency[k].recordValue(value);
		}
	}

	void error(String type, long done) {
		int s = second(done);
		if (s >= 0) {
			errors.incrementAndGet(s);
			errorsByType.computeIfAbsent(type, t -> new AtomicLongArray(seconds)).incrementAndGet(s);
		}
		int k = step(done);
		if (k >= 0) {
			stepErrors.incrementAndGet(k);
			stepErrorsByType.get(k).computeIfAbsent(type, t -> new java.util.concurrent.atomic.LongAdder()).increment();
		}
	}

	/** 第 {@code index} 个样本（0 = 本轮开始时）。 */
	void sample(int index, double offsetMs, Map<String, String> client, Map<String, Map<String, String>> providers) {
		if (index >= 0 && index < samples.length()) {
			samples.set(index, new Sample(offsetMs, client, providers));
		}
	}

	/** 采样的秒数上限（样本 0 .. {@code seconds}）。 */
	int sampleSlots() {
		return samples.length();
	}

	/** 驱动脚本回报的执行记录（{@link FaultSchedule#readExecuted}），按故障序号。 */
	void executed(List<Map<String, Object>> executed) {
		this.executed = List.copyOf(executed);
	}

	/** 拓扑（提供者编号 → 公布地址），用来把故障目标对应到节点份额；没有时份额恢复不计算。 */
	void topology(Map<String, String> topology) {
		this.topology = Map.copyOf(topology);
	}

	// ---------------------------------------------------------------- JSON

	Map<String, Object> toJson() {
		List<Map<String, Object>> rows = rows();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("timeline", timelineJson(rows));
		if (!steps.isEmpty()) {
			out.put("steps", stepsJson(rows));
		}
		if (!faults.isEmpty()) {
			out.put("faults", faultsJson(rows));
		}
		return out;
	}

	private Map<String, Object> timelineJson(List<Map<String, Object>> rows) {
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("interval_ms", 1000);
		t.put("basis", "success / errors / latency by completion time (latency from the scheduled send time, 2 significant digits); "
				+ "node_attempts / retries from the harness RpcClient (attempts incl. retries, by send time); ejected_nodes = providers in "
				+ "outlier ejection at the end of the second, ejections / restores = cumulative outlier ejections / restores at the end of the "
				+ "second; server_* from each provider's counters() sampled every second (delta of two samples)");
		t.put("rows", rows);
		return t;
	}

	private List<Map<String, Object>> rows() {
		List<Map<String, Object>> rows = new ArrayList<>();
		int last = lastSecond();
		for (int s = 0; s <= last; s++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("t", s);
			row.put("success", success.get(s));
			row.put("errors", errors.get(s));
			Map<String, Object> byType = new TreeMap<>();
			for (Map.Entry<String, AtomicLongArray> e : errorsByType.entrySet()) {
				long n = e.getValue().get(s);
				if (n > 0) {
					byType.put(e.getKey(), n);
				}
			}
			row.put("errors_by_type", new LinkedHashMap<>(byType));
			Histogram h = latency[s];
			row.put("p50_ms", h.getTotalCount() == 0 ? null : ms(h.getValueAtPercentile(50)));
			row.put("p99_ms", h.getTotalCount() == 0 ? null : ms(h.getValueAtPercentile(99)));
			sampledColumns(row, samples.get(s), samples.get(s + 1));
			rows.add(row);
		}
		return rows;
	}

	/** 有数据的最后一秒：本轮时长内的每一秒，加上之后仍有完成（在途尾巴）的秒。 */
	private int lastSecond() {
		int last = Math.min(seconds - 1, Math.max(0, roundSeconds - 1));
		for (int s = seconds - 1; s > last; s--) {
			if (success.get(s) > 0 || errors.get(s) > 0) {
				return s;
			}
		}
		return last;
	}

	/** 第 s 秒的采样列（两个样本之差）；缺样本时这些列为 null。 */
	private static void sampledColumns(Map<String, Object> row, Sample before, Sample after) {
		if (before == null || after == null) {
			row.put("node_attempts", null);
			row.put("node_share", null);
			row.put("retries", null);
			row.put("ejected_nodes", null);
			row.put("ejections", null);
			row.put("server_executor_rejections", null);
			return;
		}
		Map<String, Object> attempts = new TreeMap<>();
		Map<String, Object> failures = new TreeMap<>();
		Map<String, Object> timeouts = new TreeMap<>();
		long total = 0;
		for (String key : after.client().keySet()) {
			if (!key.startsWith(RpcCallTarget.NODE_KEY_PREFIX)) {
				continue;
			}
			String rest = key.substring(RpcCallTarget.NODE_KEY_PREFIX.length());
			int dot = rest.lastIndexOf('.');
			String address = rest.substring(0, dot);
			String kind = rest.substring(dot + 1);
			long b = ServerCounters.number(before.client(), key) == null ? 0 : ServerCounters.number(before.client(), key);
			long a = ServerCounters.number(after.client(), key);
			long d = a - b;
			switch (kind) {
				case "attempts" -> {
					attempts.put(address, d);
					total += d;
				}
				case "failures" -> failures.put(address, d);
				case "timeouts" -> timeouts.put(address, d);
				default -> {
				}
			}
		}
		Map<String, Object> share = new TreeMap<>();
		for (Map.Entry<String, Object> e : attempts.entrySet()) {
			share.put(e.getKey(), total == 0 ? null : RoundRecorder.round3(((Long) e.getValue()) / (double) total));
		}
		row.put("node_attempts", new LinkedHashMap<>(attempts));
		row.put("node_share", new LinkedHashMap<>(share));
		row.put("node_failures", new LinkedHashMap<>(failures));
		row.put("node_timeouts", new LinkedHashMap<>(timeouts));
		row.put("retries", ServerCounters.diff(before.client(), after.client(), RpcCallTarget.CLIENT_RETRY_ATTEMPTS_KEY));
		row.put("retry_node_switches", ServerCounters.diff(before.client(), after.client(), RpcCallTarget.CLIENT_RETRY_NODE_SWITCHES_KEY));
		// 离群摘除：该秒末处于摘除期的地址数（瞬时值）与累计摘除 / 放回次数（不是差值）
		row.put("ejected_nodes", ServerCounters.number(after.client(), RpcCallTarget.CLIENT_OUTLIER_EJECTED_KEY));
		row.put("ejections", ServerCounters.number(after.client(), RpcCallTarget.CLIENT_OUTLIER_EJECTIONS_KEY));
		row.put("restores", ServerCounters.number(after.client(), RpcCallTarget.CLIENT_OUTLIER_RESTORES_KEY));
		long executorRejections = 0;
		long shutdownRejections = 0;
		long queued = 0;
		Map<String, Object> rejectionsByProvider = new TreeMap<>();
		List<String> unreachable = new ArrayList<>();
		Histogram wait = null;
		for (Map.Entry<String, Map<String, String>> e : after.providers().entrySet()) {
			Map<String, String> a = e.getValue();
			Map<String, String> b = before.providers().get(e.getKey());
			if (a == null) {
				unreachable.add(e.getKey());
				continue;
			}
			Long ex = ServerCounters.diff(b, a, ServerCounters.EXECUTOR_REJECTIONS);
			Long sh = ServerCounters.diff(b, a, ServerCounters.SHUTDOWN_REJECTIONS);
			executorRejections += ex == null ? 0 : ex;
			shutdownRejections += sh == null ? 0 : sh;
			rejectionsByProvider.put(e.getKey(), (ex == null ? 0 : ex) + (sh == null ? 0 : sh));
			Long q = ServerCounters.number(a, ServerCounters.QUEUED);
			queued += q == null ? 0 : q;
			Histogram h = b == null ? null : ServerCounters.queueWait(b, a);
			if (h != null) {
				if (wait == null) {
					wait = h;
				} else {
					wait.add(h);
				}
			}
		}
		row.put("server_executor_rejections", executorRejections);
		row.put("server_shutdown_rejections", shutdownRejections);
		row.put("server_rejections_by_provider", new LinkedHashMap<>(rejectionsByProvider));
		row.put("server_queued", queued);
		row.put("server_queue_wait_p99_ms", wait == null || wait.getTotalCount() == 0 ? null : ms(wait.getValueAtPercentile(99)));
		row.put("providers_unreachable", unreachable);
	}

	private List<Object> stepsJson(List<Map<String, Object>> rows) {
		List<Object> out = new ArrayList<>();
		for (int k = 0; k < steps.size(); k++) {
			Map<String, Object> step = new LinkedHashMap<>();
			double startMs = (stepStartNanos[k] - startNanos) / 1e6;
			double endMs = (stepEndNanos[k] - startNanos) / 1e6;
			step.put("index", k);
			step.put("rate", steps.get(k).rate());
			step.put("start_ms", RoundRecorder.round3(startMs));
			step.put("end_ms", RoundRecorder.round3(endMs));
			long ok = stepSuccess.get(k);
			long err = stepErrors.get(k);
			step.put("requests", ok + err);
			step.put("successes", ok);
			step.put("errors", err);
			step.put("success_rate", ok + err == 0 ? null : RoundRecorder.round3(ok / (double) (ok + err)));
			step.put("throughput_rps", RoundRecorder.round3(ok / ((endMs - startMs) / 1000.0)));
			step.put("latency_ms", quantiles(stepLatency[k]));
			Map<String, Object> byType = new TreeMap<>();
			stepErrorsByType.get(k).forEach((type, n) -> byType.put(type, n.sum()));
			step.put("errors_by_type", new LinkedHashMap<>(byType));
			// 服务端：该级起止两个样本之差（级的边界是整秒时，样本 i 就在边界上）
			int from = (int) Math.round(startMs / 1000.0);
			int to = (int) Math.round(endMs / 1000.0);
			Sample b = from < samples.length() ? samples.get(from) : null;
			Sample a = to < samples.length() ? samples.get(to) : null;
			step.put("server", stepServer(b, a));
			step.put("recovery", k == 0 ? null : stepRecovery(rows, k));
			step.put("note", k == steps.size() - 1 ? "the last step also collects completions after the round end (in-flight tail)" : null);
			out.add(step);
		}
		return out;
	}

	private static Object stepServer(Sample before, Sample after) {
		if (before == null || after == null) {
			return EnvironmentProbe.UNKNOWN;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		long executorRejections = 0;
		long shutdownRejections = 0;
		Histogram wait = null;
		for (Map.Entry<String, Map<String, String>> e : after.providers().entrySet()) {
			Map<String, String> b = before.providers().get(e.getKey());
			Map<String, String> a = e.getValue();
			Long ex = ServerCounters.diff(b, a, ServerCounters.EXECUTOR_REJECTIONS);
			Long sh = ServerCounters.diff(b, a, ServerCounters.SHUTDOWN_REJECTIONS);
			executorRejections += ex == null ? 0 : ex;
			shutdownRejections += sh == null ? 0 : sh;
			Histogram h = b == null || a == null ? null : ServerCounters.queueWait(b, a);
			if (h != null) {
				if (wait == null) {
					wait = h;
				} else {
					wait.add(h);
				}
			}
		}
		out.put("executor_rejections", executorRejections);
		out.put("shutdown_rejections", shutdownRejections);
		out.put("queue_wait_ms", ServerCounters.quantiles(wait));
		out.put("retries", ServerCounters.diff(before.client(), after.client(), RpcCallTarget.CLIENT_RETRY_ATTEMPTS_KEY));
		return out;
	}

	private List<Object> faultsJson(List<Map<String, Object>> rows) {
		double baselineP99 = baselineP99(rows, firstEventSeconds());
		List<Object> out = new ArrayList<>();
		for (int i = 0; i < faults.size(); i++) {
			Profile.Fault fault = faults.get(i);
			Map<String, Object> f = new LinkedHashMap<>(Profile.faultJson(fault));
			f.put("index", i);
			Map<String, Object> exec = i < executed.size() ? executed.get(i) : null;
			Double executedMs = exec != null && exec.get("executed_at_ms") instanceof Number n ? n.doubleValue() : null;
			if (executedMs != null && (executedMs < -1000 || executedMs > seconds * 1000.0)) {
				// 驱动脚本回报的时刻不在本轮之内（时钟问题）：照实记下，但恢复按计划时刻算
				f.put("executed_at_ms_out_of_round", executedMs);
				executedMs = null;
			}
			f.put("executed_at_ms", executedMs);
			f.put("status", exec == null ? "not_executed" : exec.get("status"));
			if (exec != null && exec.get("detail") != null) {
				f.put("detail", exec.get("detail"));
			}
			String address = topology.get(String.valueOf(fault.target()));
			f.put("target_address", address);
			double at = (executedMs != null ? executedMs : Durations.millis(fault.at())) / 1000.0;
			f.put("recovery", recovery(rows, at, baselineP99, address, i));
			int from = (int) Math.max(0, Math.min(rows.size(), Math.floor(at)));
			f.put("outlier_ejections", cumulativeSince(rows, from, "ejections"));
			f.put("outlier_restores", cumulativeSince(rows, from, "restores"));
			out.add(f);
		}
		return out;
	}

	/**
	 * 累计列 {@code key} 从第 {@code from} 秒开始到最后一个有样本的行的增量（第 from − 1 秒末的值作起点，from = 0 时起点为 0）；
	 * 列缺失（旧结果、非 rpc 目标）时为 null。
	 */
	static Long cumulativeSince(List<Map<String, Object>> rows, int from, String key) {
		Number last = null;
		for (int s = rows.size() - 1; s >= 0 && last == null; s--) {
			if (rows.get(s).get(key) instanceof Number n) {
				last = n;
			}
		}
		if (last == null) {
			return null;
		}
		long start = 0;
		if (from > 0 && from - 1 < rows.size() && rows.get(from - 1).get(key) instanceof Number n) {
			start = n.longValue();
		}
		return last.longValue() - start;
	}

	/** 第一个"事件"（故障，或第 2 级阶梯）的时刻（秒）：此前的各秒作基线。 */
	private double firstEventSeconds() {
		double first = Double.MAX_VALUE;
		if (!faults.isEmpty()) {
			first = Durations.millis(faults.get(0).at()) / 1000.0;
		}
		if (steps.size() > 1) {
			first = Math.min(first, (stepStartNanos[1] - startNanos) / 1e9);
		}
		return first == Double.MAX_VALUE ? roundSeconds : first;
	}

	/** 基线 p99：第一个事件之前、去掉开头 2 秒（建连与 JIT）的各秒 p99 的中位数；没有可用的秒时 NaN。 */
	static double baselineP99(List<Map<String, Object>> rows, double beforeSeconds) {
		List<Double> values = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			int t = ((Number) row.get("t")).intValue();
			if (t >= 2 && t + 1 <= beforeSeconds && row.get("p99_ms") instanceof Number p) {
				values.add(p.doubleValue());
			}
		}
		if (values.isEmpty()) {
			return Double.NaN;
		}
		values.sort(null);
		return values.get((values.size() - 1) / 2);
	}

	/**
	 * 一个事件（时刻 {@code at} 秒）之后的恢复：
	 * <ul>
	 *   <li>{@code errors_recovery_s}：从事件时刻到"连续 {@value #RECOVERY_CLEAN_SECONDS} 秒错误为 0"的第一秒的起点；事件之后一个错误都没有时为 0；
	 *       到本轮结束都没满足为 null（{@code recovered: false}）；</li>
	 *   <li>{@code p99_recovery_s}：同上，条件再加"该秒 p99 ≤ {@value #P99_RECOVERY_FACTOR} × 基线 p99"（基线 = 第一个事件之前各秒 p99 的中位数）；</li>
	 *   <li>{@code share_recovery_s}（只对 {@code remove-toxic} / {@code sigcont}，且知道目标节点地址时）：目标节点每秒份额
	 *       ≥ {@value #SHARE_RECOVERY_FRACTION} × 基线份额（第一个事件之前的平均份额）连续 3 秒的第一秒起点。</li>
	 * </ul>
	 * 另给出事件之后到恢复之前的错误总数、第一 / 最后一个有错误的秒。秒粒度：恢复时间的分辨率是 1 秒。
	 */
	Map<String, Object> recovery(List<Map<String, Object>> rows, double at, double baselineP99, String targetAddress, int faultIndex) {
		Map<String, Object> r = new LinkedHashMap<>();
		int from = (int) Math.max(0, Math.min(rows.size(), Math.floor(at)));
		int last = Math.min(rows.size() - 1, roundSeconds - 1);
		Integer clean = firstClean(rows, from, last, row -> number(row.get("errors")) == 0);
		if (clean == null && noErrors(rows, from, last)) {
			// 事件之后到本轮结束一个错误都没有（剩下的秒数不够连续 3 秒也算）：没有需要恢复的
			clean = from;
		}
		long errorsAfter = 0;
		Integer firstError = null;
		Integer lastError = null;
		int end = clean == null ? last : clean - 1;
		for (int s = from; s <= end && s < rows.size(); s++) {
			long e = number(rows.get(s).get("errors"));
			errorsAfter += e;
			if (e > 0) {
				if (firstError == null) {
					firstError = s;
				}
				lastError = s;
			}
		}
		r.put("criterion", "first second s >= floor(event) where errors[s..s+" + (RECOVERY_CLEAN_SECONDS - 1)
				+ "] == 0; recovery = max(0, s - event); no error at all after the event = 0");
		r.put("recovered", clean != null);
		r.put("errors_recovery_s", clean == null ? null : RoundRecorder.round3(Math.max(0, clean - at)));
		r.put("errors_until_recovery", errorsAfter);
		r.put("first_error_second", firstError);
		r.put("last_error_second", lastError);
		r.put("baseline_p99_ms", Double.isNaN(baselineP99) ? null : baselineP99);
		if (!Double.isNaN(baselineP99)) {
			double limit = baselineP99 * P99_RECOVERY_FACTOR;
			Integer p99Clean = firstClean(rows, from, last, row -> number(row.get("errors")) == 0 && row.get("p99_ms") instanceof Number p
					&& p.doubleValue() <= limit);
			r.put("p99_recovery_s", p99Clean == null ? null : RoundRecorder.round3(Math.max(0, p99Clean - at)));
		}
		Profile.Fault fault = faultIndex >= 0 ? faults.get(faultIndex) : null;
		if (fault != null && targetAddress != null
				&& (Profile.REMOVE_TOXIC.equals(fault.kind()) || Profile.SIGCONT.equals(fault.kind()))) {
			double baselineShare = baselineShare(rows, Durations.millis(faults.get(0).at()) / 1000.0, targetAddress);
			r.put("baseline_share", Double.isNaN(baselineShare) ? null : RoundRecorder.round3(baselineShare));
			if (!Double.isNaN(baselineShare)) {
				double limit = baselineShare * SHARE_RECOVERY_FRACTION;
				Integer shareClean = firstClean(rows, from, last, row -> share(row, targetAddress) >= limit);
				r.put("share_recovery_s", shareClean == null ? null : RoundRecorder.round3(Math.max(0, shareClean - at)));
			}
		}
		return r;
	}

	/** 事件 {@code index} 级阶梯（k ≥ 1）开始之后的恢复（与故障同一判据），进 {@code rounds[].steps[k].recovery}。 */
	Map<String, Object> stepRecovery(List<Map<String, Object>> rows, int k) {
		double at = (stepStartNanos[k] - startNanos) / 1e9;
		return recovery(rows, at, baselineP99(rows, firstEventSeconds()), null, -1);
	}

	private static boolean noErrors(List<Map<String, Object>> rows, int from, int last) {
		for (int s = Math.max(0, from); s <= last && s < rows.size(); s++) {
			if (number(rows.get(s).get("errors")) != 0) {
				return false;
			}
		}
		return true;
	}

	private static Integer firstClean(List<Map<String, Object>> rows, int from, int last, java.util.function.Predicate<Map<String, Object>> ok) {
		for (int s = Math.max(0, from); s + RECOVERY_CLEAN_SECONDS - 1 <= last; s++) {
			boolean all = true;
			for (int j = 0; j < RECOVERY_CLEAN_SECONDS; j++) {
				if (!ok.test(rows.get(s + j))) {
					all = false;
					break;
				}
			}
			if (all) {
				return s;
			}
		}
		return null;
	}

	private static double baselineShare(List<Map<String, Object>> rows, double beforeSeconds, String address) {
		double sum = 0;
		int n = 0;
		for (Map<String, Object> row : rows) {
			int t = ((Number) row.get("t")).intValue();
			if (t >= 2 && t + 1 <= beforeSeconds) {
				double s = share(row, address);
				if (!Double.isNaN(s)) {
					sum += s;
					n++;
				}
			}
		}
		return n == 0 ? Double.NaN : sum / n;
	}

	private static double share(Map<String, Object> row, String address) {
		return row.get("node_share") instanceof Map<?, ?> m && m.get(address) instanceof Number n ? n.doubleValue() : Double.NaN;
	}

	private static long number(Object value) {
		return value instanceof Number n ? n.longValue() : 0;
	}

	private static Map<String, Object> quantiles(Histogram h) {
		Map<String, Object> q = new LinkedHashMap<>();
		long count = h.getTotalCount();
		q.put("count", count);
		for (String key : List.of("p50", "p90", "p99", "p999", "max")) {
			q.put(key, null);
		}
		if (count > 0) {
			q.put("p50", ms(h.getValueAtPercentile(50)));
			q.put("p90", ms(h.getValueAtPercentile(90)));
			q.put("p99", ms(h.getValueAtPercentile(99)));
			q.put("p999", ms(h.getValueAtPercentile(99.9)));
			q.put("max", ms(h.getMaxValue()));
		}
		return q;
	}

	private static double ms(double nanos) {
		return RoundRecorder.round3(nanos / 1e6);
	}
}
