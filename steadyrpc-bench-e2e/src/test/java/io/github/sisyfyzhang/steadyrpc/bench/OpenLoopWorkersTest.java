package io.github.sisyfyzhang.steadyrpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * {@code --open-workers pool} 的排队与背压语义：计划时刻按入队先后执行（保序）；队列满时 {@link OpenLoopWorkers#offer}
 * 返回 false，驱动把它计为 {@code backlog_dropped}；close 等已入队的全部执行完。
 */
class OpenLoopWorkersTest {

	@Test
	void scheduledTimesRunInEnqueueOrder() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		List<Long> seen = Collections.synchronizedList(new ArrayList<>());
		try (OpenLoopWorkers workers = new OpenLoopWorkers(1, 1000, (scheduled, actual) -> {
			await(release);
			seen.add(scheduled);
		})) {
			for (long i = 1; i <= 200; i++) {
				assertTrue(workers.offer(i), "room for " + i);
			}
			release.countDown();
		} // close：全部执行完才返回
		List<Long> expected = new ArrayList<>();
		for (long i = 1; i <= 200; i++) {
			expected.add(i);
		}
		assertEquals(expected, seen, "FIFO: no scheduled time overtakes an earlier one");
	}

	/** 多个工作者时各自取的顺序仍是入队顺序：每个工作者看到的序列单调递增。 */
	@Test
	void eachWorkerTakesItemsInScheduleOrder() {
		Map<Thread, List<Long>> byWorker = new java.util.concurrent.ConcurrentHashMap<>();
		try (OpenLoopWorkers workers = new OpenLoopWorkers(4, 10_000, (scheduled, actual) -> byWorker
				.computeIfAbsent(Thread.currentThread(), t -> Collections.synchronizedList(new ArrayList<>())).add(scheduled))) {
			assertEquals(4, workers.workerCount());
			for (long i = 0; i < 5000; i++) {
				assertTrue(workers.offer(i));
			}
		}
		long total = 0;
		for (List<Long> taken : byWorker.values()) {
			for (int i = 1; i < taken.size(); i++) {
				assertTrue(taken.get(i - 1) < taken.get(i), "a worker never takes a later time before an earlier one: " + taken);
			}
			total += taken.size();
		}
		assertEquals(5000, total, "every scheduled time ran exactly once");
	}

	@Test
	void aFullQueueRejectsWithoutBlocking() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Long> seen = Collections.synchronizedList(new ArrayList<>());
		try (OpenLoopWorkers workers = new OpenLoopWorkers(1, 3, (scheduled, actual) -> {
			started.countDown();
			await(release);
			seen.add(scheduled);
		})) {
			assertTrue(workers.offer(1));
			assertTrue(started.await(10, TimeUnit.SECONDS), "the worker picked up the first item");
			assertTrue(workers.offer(2));
			assertTrue(workers.offer(3));
			assertTrue(workers.offer(4));
			long before = System.nanoTime();
			assertFalse(workers.offer(5), "capacity 3 waiting -> the 4th waiting item is rejected (backlog)");
			assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "offer does not block");
			release.countDown();
		}
		assertEquals(List.of(1L, 2L, 3L, 4L), seen);
	}

	/**
	 * 驱动层：pool 模式下在途（排队 + 执行中）达到上限 rate × timeout × 2 时 backlog_dropped，本轮无效；
	 * 与每请求新线程的语义相同（HttpCallTargetTest#onlyInFlightAboveTheCapIsBacklogDropped）。
	 */
	@Test
	void poolWorkersStillDropAboveTheInFlightCap() {
		Profile profile = ProfileLoader.parse(DispatcherDelayedTest.openCard("it-pool-cap", "rate: 100", "1s", "400ms", "[400ms, 1s]", "10ms"),
				null, "test");
		assertEquals(2, profile.load().inflightCap());
		CallTarget slow = new SlowTarget(300);
		HarnessOptions pool = HarnessOptions.parse("pool:1", null, null, "0");
		Map<String, Object> round = LoadDriver.runRound(1, profile, slow, null, pool).toJson();
		assertTrue(((Number) round.get("backlog_dropped")).longValue() > 0, "in-flight above the cap is dropped");
		assertTrue(((Number) MarkdownReport.path(round, "scheduler", "max_inflight")).longValue() <= 2, "never more than the cap in flight");
		assertEquals("pool", MarkdownReport.path(round, "scheduler", "workers"));
		assertEquals(1L, ((Number) MarkdownReport.path(round, "scheduler", "pool_size")).longValue());
		assertEquals(Boolean.FALSE, round.get("valid"));
	}

	/** pool 模式的一轮正常完成：每个计划时刻恰好执行一次，服务时间与发出滞后都有记录。 */
	@Test
	void poolWorkersCompleteEveryScheduledCall() {
		Profile profile = ProfileLoader.parse(DispatcherDelayedTest.openCard("it-pool", "rate: 200", "1s", "200ms", "[200ms, 1s]", "1s"),
				null, "test");
		CallTarget fast = new SlowTarget(0);
		Map<String, Object> round = LoadDriver.runRound(1, profile, fast, null, HarnessOptions.parse("pool:4", null, null, "0")).toJson();
		assertEquals(200L, ((Number) round.get("issued")).longValue(), "rate x duration");
		assertEquals(200L, ((Number) round.get("successes_total")).longValue());
		assertEquals(0L, ((Number) round.get("backlog_dropped")).longValue());
		assertTrue(((Number) MarkdownReport.path(round, "service_time_ms", "count")).longValue() > 0);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 无视超时、每次占 {@code millis} 的替身目标。 */
	static final class SlowTarget implements CallTarget {
		private final long millis;

		SlowTarget(long millis) {
			this.millis = millis;
		}

		@Override
		public void call() throws Exception {
			if (millis > 0) {
				Thread.sleep(millis);
			}
		}

		@Override
		public void verify() {
		}

		@Override
		public Map<String, String> serverEnvironment() {
			return Map.of();
		}

		@Override
		public Map<String, Object> describe() {
			return Map.of();
		}

		@Override
		public void close() {
		}
	}
}
