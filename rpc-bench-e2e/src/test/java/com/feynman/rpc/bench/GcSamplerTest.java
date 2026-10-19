package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 每轮统计窗口的 GC 观测：快照的字符串往返、停顿与并发周期分开计、窗口差值、发压端 GC 声明。 */
class GcSamplerTest {

	private static GcSampler.Snapshot snapshot(long youngCount, long youngMs, long zCycles, long zCycleMs, long zPauses, long zPauseMs) {
		return new GcSampler.Snapshot(List.of(new GcSampler.Collector("G1 Young Generation", youngCount, youngMs),
				new GcSampler.Collector("ZGC Minor Cycles", zCycles, zCycleMs), new GcSampler.Collector("ZGC Minor Pauses", zPauses, zPauseMs)),
				64L << 20, 1L << 30);
	}

	@Test
	void snapshotRoundTripsThroughTheFlatStringForm() {
		GcSampler.Snapshot live = GcSampler.snapshot();
		assertFalse(live.collectors().isEmpty(), "every JVM has at least one collector MXBean");
		assertTrue(live.heapUsedBytes() > 0);
		Map<String, String> flat = live.toStrings();
		assertEquals(live, GcSampler.Snapshot.fromStrings(flat));
		assertEquals(String.valueOf(live.pauseMs()), flat.get("gc_pause_ms"));
		assertThrows(IllegalArgumentException.class, () -> GcSampler.Snapshot.fromStrings(Map.of("pid", "1")),
				"a server without stats (an old bench server) is reported, not misread as zero GC");
	}

	/** ZGC / Shenandoah 的 "... Cycles" 是并发周期时长，不是停顿：列出来，但不计入 pause_ms / collections。 */
	@Test
	void concurrentCyclesAreListedButNotCountedAsPauses() {
		GcSampler.Snapshot before = snapshot(10, 100, 3, 900, 9, 1);
		GcSampler.Snapshot after = snapshot(14, 160, 5, 1500, 15, 2);
		Map<String, Object> delta = GcSampler.delta(before, after, 50_000);
		assertEquals(4L + 6L, delta.get("collections"));
		assertEquals(60L + 1L, delta.get("pause_ms"));
		assertEquals(0.122, delta.get("pause_pct"));
		List<?> collectors = (List<?>) delta.get("collectors");
		assertEquals(3, collectors.size());
		Map<?, ?> cycles = (Map<?, ?>) collectors.get(1);
		assertEquals("ZGC Minor Cycles", cycles.get("name"));
		assertEquals(GcSampler.KIND_CONCURRENT_CYCLE, cycles.get("kind"));
		assertEquals(600L, cycles.get("time_ms"));
		assertEquals(GcSampler.KIND_PAUSE, ((Map<?, ?>) collectors.get(2)).get("kind"));
		assertEquals(64.0, delta.get("heap_used_mb_end"));
	}

	private static Map<String, Object> round(int number, boolean valid, Object clientPausePct) {
		Map<String, Object> round = new LinkedHashMap<>();
		round.put("round", number);
		round.put("valid", valid);
		round.put("warmup_completed", true);
		round.put("backlog_dropped", 0);
		round.put("window", Map.of("errors", 0));
		round.put("throughput_rps", 1000.0);
		round.put("latency_ms", Map.of("p99", 1.0));
		round.put("cpu", EnvironmentProbe.NA);
		if (clientPausePct != null) {
			round.put("gc", Map.of("client", Map.of("pause_ms", 1, "pause_pct", clientPausePct), "server", EnvironmentProbe.UNKNOWN));
		}
		return round;
	}

	/** 任一有效轮发压端 GC 停顿 &gt; 窗口 1%：声明段写明比例与轮次；只是声明，不算失败；无效轮不算。 */
	@Test
	void clientGcPauseAboveOnePercentOfTheWindowIsDeclared() {
		Profile profile = ProfileLoader.parse(ProfileLoaderTest.BRIEF_EXAMPLE.replace("  p99_max: 20ms\n", ""), null, "brief");
		Map<String, Object> summary = Map.of("official", Map.of("throughput_rps", 1000.0));

		Map<String, Object> validity = BenchRunner.validity(profile,
				List.of(round(1, true, 0.4), round(2, true, 1.75), round(3, false, 9.0)), summary, Map.of());
		assertEquals(1.75, validity.get("client_gc_pause_pct_max"));
		assertTrue(((List<?>) validity.get("statements")).contains("发压端 GC 停顿占窗口 1.8%（第 2 轮，有效轮中最高），可能抬高尾延迟。"),
				String.valueOf(validity.get("statements")));
		assertEquals(Boolean.FALSE, validity.get("passed"), "round 3 is invalid");

		Map<String, Object> quiet = BenchRunner.validity(profile, List.of(round(1, true, 1.0), round(2, true, 0.2)), summary, Map.of());
		assertEquals(Boolean.TRUE, quiet.get("passed"), "a GC statement never fails the run");
		assertFalse(String.valueOf(quiet.get("statements")).contains("发压端 GC"), "exactly 1% is not above the threshold");

		Map<String, Object> legacy = BenchRunner.validity(profile, List.of(round(1, true, null)), summary, Map.of());
		assertNull(legacy.get("client_gc_pause_pct_max"), "rounds without gc (old result files) carry no GC figure");
	}
}
