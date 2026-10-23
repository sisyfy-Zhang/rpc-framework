package io.github.sisyfyzhang.steadyrpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 规则：开环轮内发出滞后 p99 &gt; 1ms 的轮 {@code valid = false}（原因 {@code dispatcher_delayed}），不参与正式轮选取，
 * {@code validity.statements} 写明；数字仍留在 {@code rounds[]} 里。用固定的计时样本（fixture）判定，不依赖真实调度。
 */
class DispatcherDelayedTest {

	static String openCard(String id, String rateLine, String duration, String warmup, String window, String timeout) {
		return """
				profile: %s
				question: 开环规则的测试卡
				target:
				  transport: http
				  service: BenchService#echo
				  payload: { kind: bytes, size: 16 }
				  http: { url: "http://127.0.0.1:1/echo", connections: 4 }
				load:
				  model: open
				  %s
				  duration: %s
				  warmup: %s
				  steady_window: %s
				  rounds: 3
				  timeout: %s
				isolation:
				  client_process: same_jvm
				  server_process: same_jvm
				  same_host: true
				  registry: none (in-process test)
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p99, errors_by_status]
				""".formatted(id, rateLine, duration, warmup, window, timeout);
	}

	private static final Profile PROFILE = ProfileLoader.parse(openCard("it-dispatch", "rate: 1000", "1s", "400ms", "[400ms, 1s]", "1s"),
			null, "test");

	/**
	 * 一轮的固定样本：1000 次发出，其中 {@code delayed} 次发出滞后为 {@code lagNanos}、其余 10µs；预热期与窗口内各有成功调用，
	 * 窗口内延迟 = {@code latencyNanos}。
	 */
	private static Map<String, Object> round(int number, int delayed, long lagNanos, long latencyNanos) {
		RoundRecorder recorder = new RoundRecorder(number, PROFILE);
		for (int i = 0; i < 1000; i++) {
			recorder.issued();
			recorder.dispatchLag(i < delayed ? lagNanos : 10_000);
		}
		long beforeWindow = recorder.startNanos + 100_000_000L;
		recorder.success(beforeWindow - latencyNanos, beforeWindow - latencyNanos, beforeWindow);
		for (int i = 0; i < 100; i++) {
			long done = recorder.startNanos + 500_000_000L + i * 1_000_000L;
			recorder.success(done - latencyNanos, done - latencyNanos, done);
		}
		recorder.finish();
		return recorder.toJson();
	}

	@Test
	void dispatchLagP99AboveOneMillisecondInvalidatesTheRound() {
		Map<String, Object> json = round(1, 20, 2_000_000, 300_000); // 2% 的发出滞后 2ms → p99 = 2ms
		assertEquals(Boolean.FALSE, json.get("valid"));
		assertEquals(Boolean.TRUE, json.get("dispatcher_delayed"));
		List<?> reasons = (List<?>) json.get("invalid_reasons");
		assertEquals(1, reasons.size(), reasons.toString());
		assertTrue(String.valueOf(reasons.get(0)).startsWith(RoundRecorder.DISPATCHER_DELAYED + ":"), reasons.toString());
		assertTrue(((Number) MarkdownReport.path(json, "dispatch_lag_ms", "p99")).doubleValue() > 1.0);
	}

	@Test
	void aLagTailBelowThePercentileDoesNotInvalidate() {
		// 0.5% 的发出滞后 5ms：p99.9 很高，但 p99 仍在 10µs 级——规则按 p99 判定
		Map<String, Object> json = round(1, 5, 5_000_000, 300_000);
		assertEquals(Boolean.TRUE, json.get("valid"), String.valueOf(json.get("invalid_reasons")));
		assertEquals(Boolean.FALSE, json.get("dispatcher_delayed"));
	}

	@Test
	void exactlyOneMillisecondIsStillValid() {
		Map<String, Object> json = round(1, 50, 1_000_000, 300_000); // p99 = 1ms（3 位有效数字下等价值 ≤ 1.0005ms）
		assertEquals(1.0, ((Number) MarkdownReport.path(json, "dispatch_lag_ms", "p99")).doubleValue(), "reported as 1.000ms");
		assertEquals(Boolean.TRUE, json.get("valid"), "p99 > 1ms is the rule; exactly 1ms passes: " + json.get("invalid_reasons"));
	}

	@Test
	void delayedRoundsAreExcludedFromTheOfficialPickAndDeclared() {
		// 第 1 轮 p99 最低但调度线程被推迟：不能被选为正式轮
		List<Map<String, Object>> rounds = List.of(round(1, 20, 3_000_000, 100_000), round(2, 0, 0, 300_000), round(3, 0, 0, 500_000));
		Map<String, Object> summary = BenchRunner.summary(PROFILE, rounds);
		assertTrue(List.of(2, 3).contains(((Number) summary.get("official_round")).intValue()), "official round " + summary.get("official_round"));
		Map<String, Object> validity = BenchRunner.validity(PROFILE, rounds, summary, Map.of("providers", List.of("x"), "address", "x"));
		assertEquals(Boolean.FALSE, validity.get("all_rounds_valid"));
		assertEquals(Boolean.FALSE, validity.get("passed"));
		assertEquals(List.of(1), validity.get("dispatcher_delayed_rounds"));
		String statements = String.valueOf(validity.get("statements"));
		assertTrue(statements.contains("dispatcher_delayed") && statements.contains("第 1 轮"), statements);
	}

	@Test
	void undelayedRunsDeclareNothing() {
		List<Map<String, Object>> rounds = List.of(round(1, 0, 0, 300_000), round(2, 0, 0, 300_000), round(3, 0, 0, 300_000));
		Map<String, Object> summary = BenchRunner.summary(PROFILE, rounds);
		Map<String, Object> validity = BenchRunner.validity(PROFILE, rounds, summary, Map.of("providers", List.of("x"), "address", "x"));
		assertEquals(Boolean.TRUE, validity.get("all_rounds_valid"));
		assertEquals(List.of(), validity.get("dispatcher_delayed_rounds"));
		assertFalse(String.valueOf(validity.get("statements")).contains("dispatcher_delayed"));
	}
}
