package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * 开环的发出节拍：第 i 个请求的计划发出时刻 = {@code start + round(i × 1e9 / rate)}，单个调度线程逐个对齐，
 * 到点即交给 {@link Dispatcher}。计划时刻只由 rate 决定，<b>与上一个请求是否完成无关</b>——这正是开环与闭环的区别。
 *
 * <p>对齐方式：离计划时刻还远时 {@link LockSupport#parkNanos} 睡到"计划时刻 − 自旋窗口"，最后一段
 * {@link Thread#onSpinWait()} 忙等。自旋窗口初始 50µs，并按实测的 park 超睡量自适应放大（上限 {@value #MAX_SPIN_NANOS}ns）：
 * Linux 上 parkNanos 的超睡通常在几十微秒，窗口基本不动；Windows 的定时器粒度是毫秒级，窗口会放大到能吸收超睡的程度，
 * 代价是调度线程更多地忙等（占一个核）。落后于计划时（GC、调度抖动）不跳过、不补偿间隔：立即发出，延迟仍从计划时刻算。
 */
final class OpenLoopScheduler {

	static final long INITIAL_SPIN_NANOS = 50_000;
	static final long MAX_SPIN_NANOS = 20_000_000;

	/** 到点回调：{@code scheduledNanos} 为计划时刻，{@code nowNanos} 为调度线程实际到点的时刻。 */
	@FunctionalInterface
	interface Dispatcher {
		void dispatch(long scheduledNanos, long nowNanos);
	}

	static final int CALIBRATION_PARKS = 10;

	private final double intervalNanos;
	/** 阶梯（{@code load.steps}）：第 k 级的起点 / 终点（相对本轮开始，纳秒）与间隔；没有阶梯时只有一级、终点无穷远。 */
	private final long[] stepStarts;
	private final long[] stepEnds;
	private final double[] stepIntervals;
	private final int[] stepRates;
	private long spinNanos = INITIAL_SPIN_NANOS;
	private long calibratedOversleepNanos;
	private long parks;
	private long maxOversleepNanos;
	private long dispatched;

	/** 构造时先做一次校准（见 {@link #calibrate()}），所以要在本轮计划开始之前构造。 */
	OpenLoopScheduler(int rate) {
		this(List.of(new Profile.Step(rate, null)));
	}

	/**
	 * 阶梯速率：按顺序每一级以自己的 rate 等间隔发出，级内第 i 个的计划时刻 = 级起点 + round(i × 1e9 / rate)；到下一级起点即切换
	 * （下一级从它的起点重新计数，不继承上一级的相位）。最后一级的 duration 为 null 时持续到本轮结束。校准按第一级的间隔做。
	 */
	OpenLoopScheduler(List<Profile.Step> steps) {
		int n = steps.size();
		this.stepStarts = new long[n];
		this.stepEnds = new long[n];
		this.stepIntervals = new double[n];
		this.stepRates = new int[n];
		long offset = 0;
		for (int k = 0; k < n; k++) {
			Profile.Step step = steps.get(k);
			stepStarts[k] = offset;
			stepEnds[k] = step.duration() == null ? Long.MAX_VALUE : offset + step.duration().toNanos();
			stepIntervals[k] = 1e9 / step.rate();
			stepRates[k] = step.rate();
			offset = stepEnds[k];
		}
		this.intervalNanos = stepIntervals[0];
		calibrate();
	}

	/**
	 * 开跑前探测 {@value #CALIBRATION_PARKS} 次 park 的超睡量（短探测 = 半个请求间隔、至多 1ms，与长探测 3ms 交替：
	 * Windows 上短 park 可能走快路径，几毫秒的 park 才会被系统定时器取整到约 15.6ms 的节拍），自旋窗口直接取
	 * max(初始 50µs, 2 × 最大超睡)，上限 {@value #MAX_SPIN_NANOS}ns。不校准时窗口只能在第一次超睡"之后"才放大：
	 * Windows 上第一次 park 就可能超睡 5 ~ 15ms，开头一串请求全部迟发，1 秒的样本里足以把 p99 推过数毫秒。
	 * Linux 上超睡通常在百微秒以内，窗口基本不动。
	 */
	private void calibrate() {
		long shortProbe = Math.max(10_000, Math.min(1_000_000, (long) (intervalNanos / 2)));
		long longProbe = 3_000_000;
		long worst = 0;
		for (int i = 0; i < CALIBRATION_PARKS; i++) {
			long probe = i % 2 == 0 ? shortProbe : longProbe;
			long before = System.nanoTime();
			LockSupport.parkNanos(probe);
			worst = Math.max(worst, System.nanoTime() - before - probe);
		}
		calibratedOversleepNanos = worst;
		spinNanos = Math.min(MAX_SPIN_NANOS, Math.max(INITIAL_SPIN_NANOS, worst * 2));
	}

	/** 在调用线程上运行，直到计划时刻到达 {@code endNanos}（不含）。 */
	void run(long startNanos, long endNanos, Dispatcher dispatcher) {
		for (int k = 0; k < stepStarts.length; k++) {
			long stepStart = startNanos + stepStarts[k];
			long stepEnd = stepEnds[k] == Long.MAX_VALUE ? endNanos : startNanos + stepEnds[k];
			for (long i = 0;; i++) {
				long scheduled = stepStart + Math.round(i * stepIntervals[k]);
				if (scheduled - endNanos >= 0) {
					return;
				}
				if (scheduled - stepEnd >= 0) {
					break;
				}
				long now = awaitUntil(scheduled);
				dispatched++;
				dispatcher.dispatch(scheduled, now);
			}
		}
	}

	private long awaitUntil(long target) {
		long now = System.nanoTime();
		while (target - now > spinNanos) {
			long wakeAt = target - spinNanos;
			LockSupport.parkNanos(wakeAt - now);
			now = System.nanoTime();
			parks++;
			long oversleep = now - wakeAt;
			if (oversleep > maxOversleepNanos) {
				maxOversleepNanos = oversleep;
			}
			if (oversleep > spinNanos / 2 && spinNanos < MAX_SPIN_NANOS) {
				// 超睡吃掉了大半个自旋窗口：放大窗口，下次早点醒
				spinNanos = Math.min(MAX_SPIN_NANOS, Math.max(spinNanos * 2, oversleep * 2));
			}
		}
		while (target - now > 0) {
			Thread.onSpinWait();
			now = System.nanoTime();
		}
		return now;
	}

	/** 进 JSON 的 {@code rounds[].scheduler}：节拍与对齐方式的实况，便于判断调度线程本身是否可信。 */
	Map<String, Object> describe() {
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("interval_ns", Math.round(intervalNanos));
		if (stepStarts.length > 1) {
			List<Object> steps = new java.util.ArrayList<>();
			for (int k = 0; k < stepStarts.length; k++) {
				Map<String, Object> step = new LinkedHashMap<>();
				step.put("rate", stepRates[k]);
				step.put("start_ms", stepStarts[k] / 1_000_000);
				step.put("interval_ns", Math.round(stepIntervals[k]));
				steps.add(step);
			}
			info.put("steps", steps);
		}
		info.put("dispatched", dispatched);
		info.put("parks", parks);
		info.put("calibrated_max_oversleep_ns", calibratedOversleepNanos);
		info.put("spin_window_final_ns", spinNanos);
		info.put("max_park_oversleep_ns", maxOversleepNanos);
		return info;
	}
}
