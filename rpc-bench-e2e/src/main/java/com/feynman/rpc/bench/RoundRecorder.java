package com.feynman.rpc.bench;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

/**
 * 一轮的全部计数。时间一律是 {@link System#nanoTime()}，相对 {@link #startNanos}（本轮开始）换算。
 *
 * <ul>
 *   <li>延迟直方图只收<b>完成时刻落在 steady_window 内</b>的<b>成功</b>调用；失败按类型分桶单独计数
 *       （窗口内 / 全程各一份），不混进延迟分布。</li>
 *   <li>延迟的起点由驱动决定：闭环 = 调用前的 nanoTime；开环 = 计划发出时刻（修正 coordinated omission），
 *       开环另记"实际发出 → 完成"的服务时间。</li>
 *   <li>每秒成功数 / 错误数的时间序列覆盖全程（含预热），按完成时刻归桶，用来看降频、GC 停顿与抖动。</li>
 *   <li>超出直方图上限（{@code measure.histogram.max}）的值按上限记录并计入 {@code histogram_clamped}。</li>
 * </ul>
 * 写入方是多个虚拟线程：直方图用 {@link ConcurrentHistogram}，计数用 {@link LongAdder}；读取只在本轮所有调用结束之后。
 */
final class RoundRecorder {

	static final String BACKLOG_DROPPED = "backlog_dropped";

	final int round;
	final long startNanos;
	final Instant startedAt;
	private final long windowStart;
	private final long windowEnd;
	private final long maxTrackable;
	private final boolean open;

	private final Histogram latency;
	private final Histogram serviceTime;
	/** 开环调度器"实际发出 − 计划发出"，只由调度线程写。 */
	private final Histogram dispatchLag;

	private final LongAdder issued = new LongAdder();
	private final LongAdder successTotal = new LongAdder();
	private final LongAdder errorTotal = new LongAdder();
	private final LongAdder windowSuccess = new LongAdder();
	private final LongAdder windowErrors = new LongAdder();
	private final LongAdder successBeforeWindow = new LongAdder();
	private final LongAdder clamped = new LongAdder();
	private final LongAdder backlogDropped = new LongAdder();
	private final Map<String, LongAdder> windowErrorsByType = new ConcurrentHashMap<>();
	private final Map<String, LongAdder> totalErrorsByType = new ConcurrentHashMap<>();
	private final AtomicLongArray seriesSuccess;
	private final AtomicLongArray seriesErrors;

	private volatile long endNanos;
	private volatile Instant endedAt;
	private volatile Map<String, Object> schedulerInfo;
	/** 统计窗口内的 CPU 忙碌率（{@link CpuSampler}）；不采样时为 n/a，采样失败为 unknown。 */
	private volatile Object cpu = EnvironmentProbe.NA;

	RoundRecorder(int round, Profile profile) {
		this.round = round;
		Profile.Load load = profile.load();
		this.open = !load.closed();
		this.maxTrackable = profile.measure().histogramMax().toNanos();
		int digits = profile.measure().significantDigits();
		this.latency = new ConcurrentHistogram(1, maxTrackable, digits);
		this.serviceTime = open ? new ConcurrentHistogram(1, maxTrackable, digits) : null;
		this.dispatchLag = open ? new Histogram(1, maxTrackable, digits) : null;
		// 时间序列覆盖 duration + timeout（最后发出的调用最晚在那时完成）再多 1 秒
		int buckets = (int) Math.ceil((load.duration().toNanos() + load.timeout().toNanos()) / 1e9) + 1;
		this.seriesSuccess = new AtomicLongArray(buckets);
		this.seriesErrors = new AtomicLongArray(buckets);
		this.startedAt = Instant.now();
		this.startNanos = System.nanoTime();
		this.windowStart = startNanos + load.windowStart().toNanos();
		this.windowEnd = startNanos + load.windowEnd().toNanos();
	}

	boolean inWindow(long nanos) {
		return nanos >= windowStart && nanos < windowEnd;
	}

	void issued() {
		issued.increment();
	}

	/**
	 * @param intendedStart 延迟起点（闭环 = 实际发出；开环 = 计划发出）
	 * @param actualStart 实际发出（开环用于服务时间）
	 */
	void success(long intendedStart, long actualStart, long done) {
		successTotal.increment();
		bucket(seriesSuccess, done);
		if (done < windowStart) {
			successBeforeWindow.increment();
			return;
		}
		if (done >= windowEnd) {
			return;
		}
		windowSuccess.increment();
		record(latency, done - intendedStart);
		if (serviceTime != null) {
			record(serviceTime, done - actualStart);
		}
	}

	void error(String type, long done) {
		errorTotal.increment();
		bucket(seriesErrors, done);
		totalErrorsByType.computeIfAbsent(type, k -> new LongAdder()).increment();
		if (inWindow(done)) {
			windowErrors.increment();
			windowErrorsByType.computeIfAbsent(type, k -> new LongAdder()).increment();
		}
		if (BACKLOG_DROPPED.equals(type)) {
			backlogDropped.increment();
		}
	}

	/** 调度线程专用。 */
	void dispatchLag(long lagNanos) {
		record(dispatchLag, Math.max(0, lagNanos));
	}

	void schedulerInfo(Map<String, Object> info) {
		this.schedulerInfo = info;
	}

	void cpu(Object cpu) {
		this.cpu = cpu;
	}

	Object cpu() {
		return cpu;
	}

	void finish() {
		this.endNanos = System.nanoTime();
		this.endedAt = Instant.now();
	}

	private void record(Histogram histogram, long nanos) {
		long value = Math.max(1, nanos);
		if (value > maxTrackable) {
			clamped.increment();
			value = maxTrackable;
		}
		histogram.recordValue(value);
	}

	private void bucket(AtomicLongArray series, long nanos) {
		long index = (nanos - startNanos) / 1_000_000_000L;
		if (index >= 0 && index < series.length()) {
			series.incrementAndGet((int) index);
		}
	}

	long windowSuccesses() {
		return windowSuccess.sum();
	}

	long windowErrors() {
		return windowErrors.sum();
	}

	long backlogDropped() {
		return backlogDropped.sum();
	}

	boolean warmupCompleted() {
		return successBeforeWindow.sum() > 0;
	}

	double throughput() {
		return windowSuccess.sum() / ((windowEnd - windowStart) / 1e9);
	}

	/** 本轮的 JSON（rounds[] 的一项），含有效性判定。 */
	Map<String, Object> toJson() {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("round", round);
		json.put("started_at", startedAt.toString());
		json.put("ended_at", endedAt == null ? null : endedAt.toString());
		json.put("elapsed_ms", round3((endNanos - startNanos) / 1e6));
		json.put("issued", issued.sum());
		long completed = successTotal.sum() + errorTotal.sum();
		json.put("requests_total", completed);
		json.put("successes_total", successTotal.sum());
		json.put("errors_total", errorTotal.sum());
		Map<String, Object> window = new LinkedHashMap<>();
		window.put("start_ms", round3((windowStart - startNanos) / 1e6));
		window.put("end_ms", round3((windowEnd - startNanos) / 1e6));
		window.put("requests", windowSuccess.sum() + windowErrors.sum());
		window.put("successes", windowSuccess.sum());
		window.put("errors", windowErrors.sum());
		json.put("window", window);
		json.put("throughput_rps", round3(throughput()));
		json.put("latency_ms", quantiles(latency));
		json.put("service_time_ms", serviceTime == null ? null : quantiles(serviceTime));
		json.put("dispatch_lag_ms", dispatchLag == null ? null : quantiles(dispatchLag));
		json.put("errors_by_type", counts(windowErrorsByType));
		json.put("errors_total_by_type", counts(totalErrorsByType));
		json.put("backlog_dropped", backlogDropped.sum());
		json.put("histogram_clamped", clamped.sum());
		json.put("scheduler", schedulerInfo);
		json.put("cpu", cpu);
		boolean warmup = warmupCompleted();
		List<String> reasons = new ArrayList<>();
		if (!warmup) {
			reasons.add("warmup not completed: no call succeeded before the steady window");
		}
		if (backlogDropped.sum() > 0) {
			reasons.add("backlog_dropped: 在途调用达到上限 rate × timeout × 2，压测端已跟不上计划");
		}
		if (windowSuccess.sum() == 0) {
			reasons.add("no successful call completed inside the steady window");
		}
		json.put("warmup_completed", warmup);
		json.put("valid", reasons.isEmpty());
		json.put("invalid_reasons", reasons);
		Map<String, Object> series = new LinkedHashMap<>();
		series.put("interval_ms", 1000);
		series.put("success", toList(seriesSuccess));
		series.put("errors", toList(seriesErrors));
		json.put("timeseries", series);
		return json;
	}

	private static Map<String, Object> quantiles(Histogram h) {
		Map<String, Object> q = new LinkedHashMap<>();
		long count = h.getTotalCount();
		q.put("count", count);
		if (count == 0) {
			for (String key : List.of("mean", "min", "p50", "p90", "p95", "p99", "p999", "max")) {
				q.put(key, null);
			}
			return q;
		}
		q.put("mean", ms(h.getMean()));
		q.put("min", ms(h.getMinValue()));
		q.put("p50", ms(h.getValueAtPercentile(50)));
		q.put("p90", ms(h.getValueAtPercentile(90)));
		q.put("p95", ms(h.getValueAtPercentile(95)));
		q.put("p99", ms(h.getValueAtPercentile(99)));
		q.put("p999", ms(h.getValueAtPercentile(99.9)));
		q.put("max", ms(h.getMaxValue()));
		return q;
	}

	private static Map<String, Object> counts(Map<String, LongAdder> byType) {
		Map<String, Object> sorted = new TreeMap<>();
		byType.forEach((k, v) -> sorted.put(k, v.sum()));
		return new LinkedHashMap<>(sorted);
	}

	private static List<Object> toList(AtomicLongArray array) {
		List<Object> list = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++) {
			list.add(array.get(i));
		}
		return list;
	}

	private static double ms(double nanos) {
		return round3(nanos / 1e6);
	}

	/** 毫秒保留 3 位小数（微秒）：HdrHistogram 3 位有效数字下更多位数没有意义。 */
	static double round3(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}
}
