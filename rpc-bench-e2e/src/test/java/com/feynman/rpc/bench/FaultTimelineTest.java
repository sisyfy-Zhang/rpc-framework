package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FaultTimeline}：每秒归桶、采样差值（节点份额、重试、服务端拒绝与排队等待）、阶梯分段与恢复时间的计算。用合成的时刻直接喂，
 * 不起网络。
 */
class FaultTimelineTest {

	private static final long S = 1_000_000_000L;
	private static final long MS = 1_000_000L;

	private static Profile profile(String loadBody) {
		return ProfileLoader.parse(FaultCardSchemaTest.card("", loadBody).replace("duration: 60s", "duration: 10s")
				.replace("warmup: 10s", "warmup: 1s").replace("steady_window: [10s, 60s]", "steady_window: [1s, 10s]"), null, "t.yaml");
	}

	private static Map<String, String> client(long attemptsA, long attemptsB, long retries) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put(RpcCallTarget.CLIENT_RETRY_ATTEMPTS_KEY, String.valueOf(retries));
		m.put(RpcCallTarget.CLIENT_RETRY_NODE_SWITCHES_KEY, String.valueOf(retries));
		m.put("node.127.0.0.1:1.attempts", String.valueOf(attemptsA));
		m.put("node.127.0.0.1:1.failures", "0");
		m.put("node.127.0.0.1:1.timeouts", "0");
		m.put("node.127.0.0.1:2.attempts", String.valueOf(attemptsB));
		m.put("node.127.0.0.1:2.failures", "0");
		m.put("node.127.0.0.1:2.timeouts", "0");
		return m;
	}

	private static Map<String, Map<String, String>> server(long rejections, long... queueWaitsMs) {
		QueueWaitFilterHarness h = new QueueWaitFilterHarness(queueWaitsMs);
		Map<String, String> m = new LinkedHashMap<>();
		m.put(ServerCounters.EXECUTOR_REJECTIONS, String.valueOf(rejections));
		m.put(ServerCounters.SHUTDOWN_REJECTIONS, "0");
		m.put(ServerCounters.QUEUED, "7");
		m.put(ServerCounters.QUEUE_WAIT, h.encoded());
		Map<String, Map<String, String>> providers = new LinkedHashMap<>();
		providers.put("127.0.0.1:1", m);
		return providers;
	}

	/** 只为造一份累计直方图的编码（与服务端 QueueWaitFilter 同一格式）。 */
	private static final class QueueWaitFilterHarness {
		private final Histogram h = new Histogram(QueueWaitFilter.LOWEST_NANOS, QueueWaitFilter.HIGHEST_NANOS, QueueWaitFilter.DIGITS);

		QueueWaitFilterHarness(long... millis) {
			for (long ms : millis) {
				h.recordValue(ms * MS);
			}
		}

		String encoded() {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(h.getNeededByteBufferCapacity());
			int length = h.encodeIntoCompressedByteBuffer(buffer);
			byte[] bytes = new byte[length];
			buffer.flip();
			buffer.get(bytes);
			return java.util.Base64.getEncoder().encodeToString(bytes);
		}
	}

	@Test
	void secondsSamplesAndFaultRecovery() {
		Profile p = profile("""
				  rate: 1000
				  faults: [{ at: 3s, kind: kill-9, target: 2 }]""");
		long t0 = 1_000 * S;
		FaultTimeline t = new FaultTimeline(t0, p, p.load().faults());
		for (int s = 0; s < 10; s++) {
			for (int i = 0; i < 100; i++) {
				t.success(t0 + s * S + i * MS, t0 + s * S + i * MS + (s == 3 ? 50 : 1) * MS);
			}
		}
		// 第 3、4 秒出错，第 5 秒起干净
		t.error("status:SERVER_OVERLOADED", t0 + 3 * S + 500 * MS);
		t.error("timeout", t0 + 4 * S + 100 * MS);
		t.error("timeout", t0 + 4 * S + 200 * MS);
		t.sample(0, 0, client(0, 0, 0), server(0));
		t.sample(1, 1000, client(50, 50, 0), server(0, 1, 1));
		t.sample(2, 2000, client(100, 100, 0), server(0, 1, 1));
		t.sample(3, 3000, client(150, 150, 0), server(0, 1, 1));
		t.sample(4, 4000, client(240, 160, 3), server(5, 1, 1, 30, 30));
		t.topology(Map.of("2", "127.0.0.1:2"));
		t.executed(List.of(Map.of("index", 0, "executed_at_ms", 3010.0, "status", "ok")));

		Map<String, Object> json = t.toJson();
		List<?> rows = (List<?>) ((Map<?, ?>) json.get("timeline")).get("rows");
		assertEquals(10, rows.size(), "one row per second of the round");
		Map<?, ?> r0 = (Map<?, ?>) rows.get(0);
		assertEquals(100L, r0.get("success"));
		assertEquals(0L, r0.get("errors"));
		assertEquals(Map.of("127.0.0.1:1", 0.5, "127.0.0.1:2", 0.5), r0.get("node_share"));
		Map<?, ?> r3 = (Map<?, ?>) rows.get(3);
		assertEquals(1L, r3.get("errors"));
		assertEquals(Map.of("status:SERVER_OVERLOADED", 1L), r3.get("errors_by_type"));
		assertTrue(((Number) r3.get("p99_ms")).doubleValue() >= 49.0, "second 3 is slow: " + r3);
		assertEquals(Map.of("127.0.0.1:1", 0.9, "127.0.0.1:2", 0.1), r3.get("node_share"), "sample 4 - sample 3: 90 vs 10 attempts");
		assertEquals(3L, r3.get("retries"));
		assertEquals(5L, r3.get("server_executor_rejections"));
		assertEquals(7L, r3.get("server_queued"));
		assertEquals(30.0, ((Number) r3.get("server_queue_wait_p99_ms")).doubleValue(), 0.5, "only the two 30ms waits are new in second 3");
		Map<?, ?> r5 = (Map<?, ?>) rows.get(5);
		assertNull(r5.get("node_share"), "no sample 6: the sampled columns are null");

		Map<?, ?> fault = (Map<?, ?>) ((List<?>) json.get("faults")).get(0);
		assertEquals("ok", fault.get("status"));
		assertEquals(3010.0, fault.get("executed_at_ms"));
		assertEquals("127.0.0.1:2", fault.get("target_address"));
		Map<?, ?> recovery = (Map<?, ?>) fault.get("recovery");
		assertEquals(Boolean.TRUE, recovery.get("recovered"));
		assertEquals(1.99, ((Number) recovery.get("errors_recovery_s")).doubleValue(), 1e-9, "second 5 starts 1.99s after the kill at 3.01s");
		assertEquals(3L, recovery.get("errors_until_recovery"));
		assertEquals(3, recovery.get("first_error_second"));
		assertEquals(4, recovery.get("last_error_second"));
		assertEquals(1.0, ((Number) recovery.get("baseline_p99_ms")).doubleValue(), 0.02);
		assertEquals(1.99, ((Number) recovery.get("p99_recovery_s")).doubleValue(), 1e-9);
	}

	@Test
	void notRecoveredAndNoErrorsCases() {
		Profile p = profile("""
				  rate: 1000
				  faults: [{ at: 2s, kind: sigterm, target: 1 }, { at: 5s, kind: kill-9, target: 1 }]""");
		long t0 = 5 * S;
		FaultTimeline t = new FaultTimeline(t0, p, p.load().faults());
		for (int s = 0; s < 10; s++) {
			t.success(t0 + s * S, t0 + s * S + MS);
			if (s >= 5) {
				t.error("timeout", t0 + s * S + 10 * MS);
			}
		}
		List<?> faults = (List<?>) t.toJson().get("faults");
		Map<?, ?> sigterm = (Map<?, ?>) ((Map<?, ?>) faults.get(0)).get("recovery");
		assertEquals(0.0, ((Number) sigterm.get("errors_recovery_s")).doubleValue(), "no errors between 2s and 5s: recovered at once");
		Map<?, ?> kill = (Map<?, ?>) ((Map<?, ?>) faults.get(1)).get("recovery");
		assertEquals(Boolean.FALSE, kill.get("recovered"));
		assertNull(kill.get("errors_recovery_s"));
		assertEquals(5L, kill.get("errors_until_recovery"));
		assertEquals("not_executed", ((Map<?, ?>) faults.get(0)).get("status"));
	}

	@Test
	void stepsAreSplitByCompletionTime() {
		Profile p = profile("  steps: [{ rate: 100, duration: 4s }, { rate: 300, duration: 3s }, { rate: 100, duration: 3s }]");
		long t0 = 0;
		FaultTimeline t = new FaultTimeline(t0, p, List.of());
		for (int s = 0; s < 10; s++) {
			int n = s >= 4 && s < 7 ? 300 : 100;
			for (int i = 0; i < n; i++) {
				long done = s * S + i * (S / n);
				t.success(done - MS, done);
			}
			if (s >= 4 && s < 7) {
				for (int i = 0; i < 10; i++) {
					t.error("status:SERVER_OVERLOADED", s * S + i * MS);
				}
			}
		}
		for (int k = 0; k <= 10; k++) {
			t.sample(k, k * 1000.0, client(k * 100L, 0, 0), server(k >= 5 ? 10L * (k - 4) : 0));
		}
		Map<String, Object> json = t.toJson();
		List<?> steps = (List<?>) json.get("steps");
		assertEquals(3, steps.size());
		Map<?, ?> burst = (Map<?, ?>) steps.get(1);
		assertEquals(300, burst.get("rate"));
		assertEquals(4000.0, burst.get("start_ms"));
		assertEquals(930L, burst.get("requests"));
		assertEquals(30L, burst.get("errors"));
		assertEquals(300.0, ((Number) burst.get("throughput_rps")).doubleValue(), 1e-6);
		assertEquals(Map.of("status:SERVER_OVERLOADED", 30L), burst.get("errors_by_type"));
		assertEquals(30L, ((Map<?, ?>) burst.get("server")).get("executor_rejections"), "samples 7 - 4: 30 - 0");
		Map<?, ?> back = (Map<?, ?>) steps.get(2);
		assertEquals(0L, back.get("errors"));
		Map<?, ?> recovery = (Map<?, ?>) back.get("recovery");
		assertEquals(0.0, ((Number) recovery.get("errors_recovery_s")).doubleValue(), "errors stop exactly when the burst ends");
		assertNull(((Map<?, ?>) steps.get(0)).get("recovery"), "the first step has no 'before'");
		assertFalse(json.containsKey("faults"));
	}
}
