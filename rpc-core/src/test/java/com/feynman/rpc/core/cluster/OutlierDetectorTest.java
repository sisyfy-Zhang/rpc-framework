package com.feynman.rpc.core.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.OutlierDetectionConfig;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.testutil.Instances;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutlierDetector} 的规则（假时钟驱动）：桶的滑动与过期、比例条件与连续条件、时长倍增与上限、到期放回、
 * 最大摘除比例与"全被摘退回全部"、推送移除清理状态、并发计数。
 */
class OutlierDetectorTest {

	private static final ServiceInstance A = Instances.at(1);
	private static final ServiceInstance B = Instances.at(2);
	private static final ServiceInstance C = Instances.at(3);

	/** 可拨的单调时钟（纳秒）。 */
	static final class FakeClock {
		final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);

		long now() {
			return nanos.get();
		}

		void advance(Duration d) {
			nanos.addAndGet(d.toNanos());
		}
	}

	private final FakeClock clock = new FakeClock();
	private final OutlierDetector.Stats stats = new OutlierDetector.Stats();
	private final AtomicReference<List<ServiceInstance>> population = new AtomicReference<>(List.of(A, B, C));

	private OutlierDetector detector(OutlierDetectionConfig config) {
		return new OutlierDetector(config, "test", population::get, stats, clock::now);
	}

	private static OutlierDetectionConfig.Builder config() {
		return OutlierDetectionConfig.builder();
	}

	private static List<String> addresses(List<ServiceInstance> instances) {
		return instances.stream().map(ServiceInstance::address).toList();
	}

	private static void fail(OutlierDetector d, ServiceInstance node, int times) {
		for (int i = 0; i < times; i++) {
			d.onFailure(node.address(), OutlierDetector.TIMEOUT);
		}
	}

	private static void succeed(OutlierDetector d, ServiceInstance node, int times) {
		for (int i = 0; i < times; i++) {
			d.onSuccess(node.address());
		}
	}

	// ---------------------------------------------------------------- 桶

	@Test
	void bucketsSlideAndExpireAfterTenSeconds() {
		OutlierDetector d = detector(config().consecutiveFailures(0).minRequests(1000).build());
		succeed(d, A, 3);
		fail(d, A, 2);
		assertEquals(5, d.snapshot(A.address()).get("attempts"));
		assertEquals(2, d.snapshot(A.address()).get("failures"));
		clock.advance(Duration.ofSeconds(5));
		succeed(d, A, 4);
		assertEquals(9, d.snapshot(A.address()).get("attempts"), "both buckets are inside the 10s window");
		clock.advance(Duration.ofSeconds(5));
		assertEquals(4, d.snapshot(A.address()).get("attempts"), "the first bucket (10s old) left the window");
		assertEquals(0, d.snapshot(A.address()).get("failures"));
		clock.advance(Duration.ofSeconds(5));
		assertEquals(0, d.snapshot(A.address()).get("attempts"));
		// 同一个桶位（第 20 秒与第 0 秒同余）重新使用时先清零
		succeed(d, A, 1);
		assertEquals(1, d.snapshot(A.address()).get("attempts"));
	}

	/** 单线程模型对照：任意的 (前进秒数, 计数) 序列之后，窗口和等于模型里最近 10 秒的计数之和。 */
	@Property(tries = 200)
	void windowSumMatchesModel(@ForAll @Size(max = 60) List<@IntRange(min = 0, max = 4) Integer> steps,
			@ForAll @Size(max = 60) List<@IntRange(min = 0, max = 7) Integer> counts) {
		AtomicLongArray buckets = new AtomicLongArray(OutlierDetectionConfig.WINDOW_SECONDS);
		List<long[]> model = new ArrayList<>();
		long second = 0;
		int n = Math.min(steps.size(), counts.size());
		for (int i = 0; i < n; i++) {
			second += steps.get(i);
			for (int k = 0; k < counts.get(i); k++) {
				OutlierDetector.increment(buckets, second);
			}
			model.add(new long[] { second, counts.get(i) });
			long expected = 0;
			for (long[] m : model) {
				if (second - m[0] < OutlierDetectionConfig.WINDOW_SECONDS) {
					expected += m[1];
				}
			}
			assertEquals(expected, OutlierDetector.sum(buckets, second), "after " + model.size() + " steps at second " + second);
		}
	}

	// ---------------------------------------------------------------- 条件

	@Test
	void ratioConditionNeedsMinRequests() {
		OutlierDetector d = detector(config().consecutiveFailures(0).minRequests(20).failureRatio(0.5).build());
		// 19 次尝试、10 次坏结果：占比够了但样本不够
		for (int i = 0; i < 9; i++) {
			d.onSuccess(A.address());
			d.onFailure(A.address(), OutlierDetector.TRANSPORT);
		}
		d.onFailure(A.address(), OutlierDetector.TRANSPORT);
		assertEquals(0, stats.ejections(), "19 attempts < minRequests 20");
		d.onSuccess(A.address());
		assertEquals(0, stats.ejections(), "20 attempts, 10 failures, but a success never ejects");
		d.onFailure(A.address(), OutlierDetector.TRANSPORT);
		assertEquals(1, stats.ejections(), "21 attempts, 11 failures >= 0.5");
		assertEquals(List.of(B.address(), C.address()), addresses(d.filter(population.get())));
	}

	@Test
	void ratioBelowThresholdDoesNotEject() {
		OutlierDetector d = detector(config().consecutiveFailures(0).minRequests(20).failureRatio(0.5).build());
		for (int i = 0; i < 100; i++) {
			d.onSuccess(A.address());
			d.onSuccess(A.address());
			d.onFailure(A.address(), OutlierDetector.TIMEOUT);
		}
		assertEquals(0, stats.ejections(), "a third of the attempts failed: below 0.5");
	}

	@Test
	void consecutiveConditionAndSuccessResets() {
		OutlierDetector d = detector(config().consecutiveFailures(5).minRequests(1000).build());
		fail(d, A, 4);
		d.onSuccess(A.address());
		fail(d, A, 4);
		assertEquals(0, stats.ejections(), "a success in between resets the streak");
		assertEquals(4, d.snapshot(A.address()).get("consecutive"));
		d.onFailure(A.address(), OutlierDetector.OVERLOADED);
		assertEquals(1, stats.ejections());
		assertTrue(d.ejectedNodes().containsKey(A.address()));
		assertEquals(1, d.ejectedCount());
	}

	// ---------------------------------------------------------------- 时长与放回

	@Test
	void ejectionTimeMultipliesWithEjectionCountUpToMax() {
		OutlierDetector d = detector(config().consecutiveFailures(3).baseEjection(Duration.ofSeconds(10)).maxEjection(Duration.ofSeconds(25))
				.build());
		long[] expectedSeconds = { 10, 20, 25, 25 };
		for (int round = 0; round < expectedSeconds.length; round++) {
			fail(d, A, 3);
			assertEquals(round + 1, stats.ejections());
			clock.advance(Duration.ofSeconds(expectedSeconds[round]).minusMillis(1));
			assertEquals(List.of(B.address(), C.address()), addresses(d.filter(population.get())),
					"still ejected 1ms before " + expectedSeconds[round] + "s (ejection #" + (round + 1) + ")");
			clock.advance(Duration.ofMillis(1));
			assertEquals(List.of(A.address(), B.address(), C.address()), addresses(d.filter(population.get())),
					"back after " + expectedSeconds[round] + "s");
			assertEquals(round + 1, stats.restores());
		}
	}

	@Test
	void restoreResetsTheStreakButKeepsTheWindow() {
		OutlierDetector d = detector(config().consecutiveFailures(5).minRequests(1000).baseEjection(Duration.ofSeconds(3)).build());
		succeed(d, A, 10);
		fail(d, A, 5);
		assertEquals(1, stats.ejections());
		clock.advance(Duration.ofSeconds(3));
		assertEquals(0, d.ejectedCount(), "expired ejections are restored when observed");
		assertEquals(1, stats.restores());
		assertEquals(0, d.snapshot(A.address()).get("consecutive"), "the streak restarts after the ejection");
		assertEquals(15, d.snapshot(A.address()).get("attempts"), "the window survives the ejection");
		fail(d, A, 4);
		assertEquals(1, stats.ejections(), "4 new failures are below the threshold again");
		d.onFailure(A.address(), OutlierDetector.TIMEOUT);
		assertEquals(2, stats.ejections());
	}

	@Test
	void failuresWhileEjectedAreCountedButDoNotReeject() {
		OutlierDetector d = detector(config().consecutiveFailures(2).build());
		fail(d, A, 2);
		fail(d, A, 50);
		assertEquals(1, stats.ejections());
		assertEquals(52, d.snapshot(A.address()).get("failures"));
	}

	/** 摘掉 A（3 个连续坏结果），断言它恰好被摘 {@code expected}：差 1ms 时仍在摘除期，到点即放回。 */
	private void ejectAndExpectDuration(OutlierDetector d, Duration expected) {
		fail(d, A, 3);
		assertEquals(List.of(B.address(), C.address()), addresses(d.filter(population.get())), "ejected");
		clock.advance(expected.minusMillis(1));
		assertEquals(List.of(B.address(), C.address()), addresses(d.filter(population.get())), "still ejected 1ms before " + expected);
		clock.advance(Duration.ofMillis(1));
		assertEquals(List.of(A.address(), B.address(), C.address()), addresses(d.filter(population.get())), "back after " + expected);
	}

	@Test
	void ejectionMultiplierDecaysAfterHealthyMaxEjectionPeriods() {
		OutlierDetector d = detector(config().consecutiveFailures(3).baseEjection(Duration.ofSeconds(10)).maxEjection(Duration.ofSeconds(40))
				.build());
		ejectAndExpectDuration(d, Duration.ofSeconds(10));
		ejectAndExpectDuration(d, Duration.ofSeconds(20));
		ejectAndExpectDuration(d, Duration.ofSeconds(30));
		assertEquals(3, d.snapshot(A.address()).get("ejections"));
		// 放回后健康不满一个 maxEjection（40s）：不衰减，下一次是第 4 次（40s）
		clock.advance(Duration.ofSeconds(40).minusMillis(1));
		assertEquals(3, d.snapshot(A.address()).get("ejections"));
		ejectAndExpectDuration(d, Duration.ofSeconds(40));
		assertEquals(4, d.snapshot(A.address()).get("ejections"));
		// 健康满一个 maxEjection：4 → 3，下一次摘除是第 4 次的时长（40s）而不是第 5 次（上限同为 40s，用次数断言）
		clock.advance(Duration.ofSeconds(40));
		assertEquals(3, d.snapshot(A.address()).get("ejections"), "one healthy maxEjection: -1");
		// 再满一个（零头不丢：已过 40s，再 40s 共两个周期）：3 → 2，下一次摘除 = 第 3 次 = 30s
		clock.advance(Duration.ofSeconds(40));
		assertEquals(2, d.snapshot(A.address()).get("ejections"), "another healthy maxEjection: -1 again");
		ejectAndExpectDuration(d, Duration.ofSeconds(30));
		// 很久之后：衰减到下限 0，下一次摘除 = 第 1 次 = baseEjection（10s）
		clock.advance(Duration.ofSeconds(4000));
		assertEquals(0, d.snapshot(A.address()).get("ejections"), "floor 0");
		ejectAndExpectDuration(d, Duration.ofSeconds(10));
	}

	@Test
	void noDecayWhileEjected() {
		OutlierDetector d = detector(config().consecutiveFailures(3).baseEjection(Duration.ofSeconds(10)).maxEjection(Duration.ofSeconds(60))
				.build());
		ejectAndExpectDuration(d, Duration.ofSeconds(10));
		ejectAndExpectDuration(d, Duration.ofSeconds(20));
		fail(d, A, 3);
		assertEquals(3, d.snapshot(A.address()).get("ejections"));
		clock.advance(Duration.ofSeconds(29));
		assertEquals(3, d.snapshot(A.address()).get("ejections"), "still in the 30s ejection: the healthy clock has not started");
	}

	// ---------------------------------------------------------------- 最大摘除比例与全被摘

	@Test
	void maxEjectionPercentKeepsHalfOfThreeProviders() {
		OutlierDetector d = detector(config().consecutiveFailures(3).build());
		fail(d, A, 3);
		fail(d, B, 3);
		fail(d, C, 30);
		assertEquals(1, stats.ejections(), "3 providers, 50%: at least ceil(1.5) = 2 stay");
		assertEquals(List.of(B.address(), C.address()), addresses(d.filter(population.get())));
	}

	@Test
	void maxEjectionPercentWithTwoProvidersAndOne() {
		population.set(List.of(A, B));
		OutlierDetector two = detector(config().consecutiveFailures(3).build());
		fail(two, A, 3);
		fail(two, B, 3);
		assertEquals(1, stats.ejections(), "2 providers, 50%: one may go");
		population.set(List.of(C));
		OutlierDetector one = detector(config().consecutiveFailures(3).build());
		fail(one, C, 100);
		assertEquals(1, stats.ejections(), "a single provider is never ejected");
		assertEquals(List.of(C.address()), addresses(one.filter(List.of(C))));
	}

	@Test
	void maxEjectionPercentHundredStillKeepsOne() {
		population.set(List.of(A, B));
		OutlierDetector d = detector(config().consecutiveFailures(3).maxEjectionPercent(100).build());
		fail(d, A, 3);
		fail(d, B, 3);
		assertEquals(1, stats.ejections(), "at least one provider always stays");
		population.set(List.of(A, B, C));
		OutlierDetector zero = detector(config().consecutiveFailures(3).maxEjectionPercent(0).build());
		fail(zero, A, 30);
		assertEquals(1, stats.ejections(), "0% = never eject");
	}

	@Test
	void allCandidatesEjectedFallsBackToAllAndCounts() {
		OutlierDetector d = detector(config().consecutiveFailures(3).build());
		fail(d, A, 3);
		assertEquals(1, stats.ejections());
		// 推送收窄了列表（只剩被摘的那个）：选点退回全部候选，不是"无节点可用"
		List<ServiceInstance> onlyA = List.of(A);
		assertSame(onlyA, d.filter(onlyA));
		assertSame(onlyA, d.filter(onlyA));
		assertEquals(2, stats.allEjectedFallbacks());
		assertEquals(List.of(), d.filter(List.of()), "no candidates at all is not a fallback");
		assertEquals(2, stats.allEjectedFallbacks());
	}

	@Test
	void filterReturnsTheSameListWhenNothingIsEjected() {
		OutlierDetector d = detector(OutlierDetectionConfig.defaults());
		List<ServiceInstance> candidates = population.get();
		succeed(d, A, 100);
		fail(d, B, 2);
		assertSame(candidates, d.filter(candidates), "no copy on the hot path");
	}

	// ---------------------------------------------------------------- 推送移除

	@Test
	void retainDropsStateOfRemovedAddresses() {
		OutlierDetector d = detector(config().consecutiveFailures(3).build());
		fail(d, A, 3);
		assertEquals(1, d.ejectedCount());
		population.set(List.of(B, C));
		d.retain(List.of(B, C));
		assertEquals(0, d.ejectedCount());
		assertTrue(d.ejectedNodes().isEmpty());
		assertNull(d.snapshot(A.address()));
		// 被移除的地址上迟到的完成不再建出状态
		fail(d, A, 10);
		assertNull(d.snapshot(A.address()));
		// 重新上线：状态从零开始（累计被摘次数也清零，下次摘除仍是 baseEjection）
		population.set(List.of(A, B, C));
		fail(d, A, 3);
		assertEquals(2, stats.ejections());
		assertEquals(1, d.snapshot(A.address()).get("ejections"));
		assertEquals(0, stats.restores(), "removal is not a restore");
	}

	@Test
	void addressesOutsideThePopulationAreIgnored() {
		OutlierDetector d = detector(config().consecutiveFailures(1).build());
		d.onFailure("10.0.0.9:1", OutlierDetector.TRANSPORT);
		assertNull(d.snapshot("10.0.0.9:1"));
		assertEquals(0, stats.ejections());
	}

	// ---------------------------------------------------------------- 并发

	@Test
	void concurrentRecordingCountsEveryAttempt() throws Exception {
		OutlierDetector d = detector(config().consecutiveFailures(0).minRequests(Integer.MAX_VALUE).build());
		int threads = 8;
		int perThread = 20_000;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int id = t;
			futures.add(pool.submit(() -> {
				start.await();
				for (int i = 0; i < perThread; i++) {
					if ((i + id) % 4 == 0) {
						d.onFailure(A.address(), OutlierDetector.TIMEOUT);
					} else {
						d.onSuccess(A.address());
					}
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertEquals((long) threads * perThread, d.snapshot(A.address()).get("attempts"));
		assertEquals((long) threads * perThread / 4, d.snapshot(A.address()).get("failures"));
	}

	@Test
	void concurrentFailuresEjectExactlyOnce() throws Exception {
		OutlierDetector d = detector(config().consecutiveFailures(5).build());
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			futures.add(pool.submit(() -> {
				start.await();
				for (int i = 0; i < 1000; i++) {
					d.onFailure(A.address(), OutlierDetector.TIMEOUT);
					d.onFailure(B.address(), OutlierDetector.TIMEOUT);
				}
				return null;
			}));
		}
		start.countDown();
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertEquals(1, stats.ejections(), "two failing providers of three, 50%: exactly one ejection, never both");
		assertEquals(1, d.ejectedCount());
	}

	// ---------------------------------------------------------------- 配置

	@Test
	void configDefaultsAndValidation() {
		OutlierDetectionConfig defaults = OutlierDetectionConfig.defaults();
		assertTrue(defaults.enabled());
		assertEquals(20, defaults.minRequests());
		assertEquals(0.5, defaults.failureRatio());
		assertEquals(5, defaults.consecutiveFailures());
		assertEquals(Duration.ofSeconds(10), defaults.baseEjection());
		assertEquals(Duration.ofSeconds(60), defaults.maxEjection());
		assertEquals(50, defaults.maxEjectionPercent());
		assertFalse(OutlierDetectionConfig.disabled().enabled());
		assertThrows(IllegalArgumentException.class, () -> config().minRequests(0).build());
		assertThrows(IllegalArgumentException.class, () -> config().failureRatio(0).build());
		assertThrows(IllegalArgumentException.class, () -> config().failureRatio(1.01).build());
		assertThrows(IllegalArgumentException.class, () -> config().failureRatio(Double.NaN).build());
		assertThrows(IllegalArgumentException.class, () -> config().consecutiveFailures(-1).build());
		assertThrows(IllegalArgumentException.class, () -> config().baseEjection(Duration.ZERO).build());
		assertThrows(IllegalArgumentException.class, () -> config().baseEjection(Duration.ofSeconds(5)).maxEjection(Duration.ofSeconds(4)).build());
		assertThrows(IllegalArgumentException.class, () -> config().maxEjectionPercent(101).build());
		assertThrows(IllegalArgumentException.class, () -> config().maxEjectionPercent(-1).build());
		assertEquals(Duration.ofSeconds(90), config().baseEjection(Duration.ofSeconds(90)).maxEjection(null).build().maxEjection(),
				"a missing max follows a base above the default max");
	}
}
