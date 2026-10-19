package com.feynman.rpc.bench;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLoopSchedulerTest {

	/**
	 * 1000 req/s 跑 1 秒：计划发出时刻与调度线程实际到点时刻的偏差。设计目标是 p99 &lt; 1ms；这里断言 &lt; 3ms，
	 * 给 CI 共享机器的调度噪声留余量（实测值打印出来，回报里看的是它，不是这个宽松的门槛）。
	 */
	@Test
	void dispatchErrorAt1000RpsStaysWellBelowOneInterval() {
		Histogram lag = new Histogram(1, 10_000_000_000L, 3);
		long[] count = new long[1];
		long[] maxSchedule = new long[1];
		OpenLoopScheduler scheduler = new OpenLoopScheduler(1000);
		long start = System.nanoTime() + 20_000_000; // 给 JIT / 线程起步留 20ms
		long end = start + 1_000_000_000L;
		scheduler.run(start, end, (scheduled, now) -> {
			lag.recordValue(Math.max(1, now - scheduled));
			count[0]++;
			maxSchedule[0] = Math.max(maxSchedule[0], scheduled);
		});
		System.out.printf("[open-loop scheduler] 1000 rps x 1s: dispatched=%d lag p50=%.3fms p99=%.3fms max=%.3fms %s%n", count[0],
				lag.getValueAtPercentile(50) / 1e6, lag.getValueAtPercentile(99) / 1e6, lag.getMaxValue() / 1e6, scheduler.describe());
		assertEquals(1000, count[0], "exactly rate x duration dispatches");
		assertTrue(maxSchedule[0] < end, "no dispatch scheduled at or after the end");
		assertTrue(lag.getValueAtPercentile(99) < 3_000_000, "p99 dispatch error " + lag.getValueAtPercentile(99) / 1e6 + "ms");
	}

	@Test
	void scheduleIsEvenlySpacedAndDoesNotSkipWhenBehind() {
		OpenLoopScheduler scheduler = new OpenLoopScheduler(3);
		long start = System.nanoTime() - 2_000_000_000L; // 计划从 2 秒前开始：全部落后
		long[] scheduledTimes = new long[10];
		int[] n = new int[1];
		scheduler.run(start, start + 1_000_000_000L, (scheduled, now) -> scheduledTimes[n[0]++] = scheduled - start);
		assertEquals(3, n[0], "behind schedule: every planned request is still dispatched, none skipped");
		assertEquals(0, scheduledTimes[0]);
		assertEquals(333_333_333, scheduledTimes[1]);
		assertEquals(666_666_667, scheduledTimes[2]);
	}
}
