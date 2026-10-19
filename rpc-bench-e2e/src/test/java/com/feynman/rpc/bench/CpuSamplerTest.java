package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** 每轮统计窗口的 CPU 采样：/proc/stat 解析、两组核的平均忙碌率、client_bound 判定与声明。 */
class CpuSamplerTest {

	/** 一份 /proc/stat（10 核，累计 tick）：0-4 号核 user / idle 取前两个数，5-9 号核取后两个数。 */
	static List<String> stat(long serverUser, long serverIdle, long clientUser, long clientIdle) {
		List<String> lines = new java.util.ArrayList<>(List.of("cpu  1 2 3 4 5 6 7 8 9 10"));
		for (int cpu = 0; cpu < 10; cpu++) {
			long user = cpu < 5 ? serverUser : clientUser;
			long idle = cpu < 5 ? serverIdle : clientIdle;
			// user nice system idle iowait irq softirq steal guest guest_nice
			lines.add("cpu" + cpu + ' ' + user + " 0 0 " + idle + " 0 0 0 0 0 0");
		}
		lines.add("intr 12345");
		lines.add("ctxt 999");
		return lines;
	}

	@Test
	void parsesCpuListsAndOnlyAcceptsRealLists() {
		assertEquals(new TreeSet<>(List.of(0, 1, 2, 3, 4, 7)), CpuSampler.parseCpuList("0-4,7"));
		assertTrue(CpuSampler.isCpuList("5-9"));
		assertTrue(CpuSampler.isCpuList("0,2,4-6"));
		assertFalse(CpuSampler.isCpuList(EnvironmentProbe.NA));
		assertFalse(CpuSampler.isCpuList(EnvironmentProbe.UNKNOWN));
		assertFalse(CpuSampler.isCpuList(null));
	}

	@Test
	void measuresTheAverageBusyPercentageOfBothCoreGroups() {
		Map<Integer, long[]> before = CpuSampler.snapshot(stat(100, 900, 100, 900));
		Map<Integer, long[]> after = CpuSampler.snapshot(stat(870, 1130, 1080, 920)); // 每核 Δ1000 tick：服务端忙 770，客户端忙 980
		assertEquals(10, before.size(), "per-cpu lines only, the aggregate 'cpu' line is skipped");
		boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
		CpuSampler sampler = CpuSampler.create("5-9", "0-4", true);
		if (!linux) {
			assertNull(sampler, "no /proc/stat: no sampling (rounds[].cpu = n/a)");
			return;
		}
		Map<String, Object> cpu = sampler.measure(before, after, 40_000);
		assertEquals("5-9", cpu.get("client_cpus"));
		assertEquals(98.0, cpu.get("client_busy_pct"));
		assertEquals("0-4", cpu.get("server_cpus"));
		assertEquals(77.0, cpu.get("server_busy_pct"));
		assertEquals(40_000L, cpu.get("window_ms"));

		Map<String, Object> remote = CpuSampler.create("5-9", "0-4", false).measure(before, after, 40_000);
		assertEquals(EnvironmentProbe.NA, remote.get("server_cpus"), "server on another host: its cores are not ours to sample");
		assertNull(remote.get("server_busy_pct"));
		assertNull(CpuSampler.create(EnvironmentProbe.NA, "0-4", true), "no client core list: no sampling");
	}

	private static Map<String, Object> round(boolean valid, Object cpu) {
		Map<String, Object> round = new LinkedHashMap<>();
		round.put("round", 1);
		round.put("valid", valid);
		round.put("warmup_completed", true);
		round.put("backlog_dropped", 0);
		round.put("window", Map.of("errors", 0));
		round.put("throughput_rps", 1000.0);
		round.put("latency_ms", Map.of("p99", 1.0));
		round.put("cpu", cpu);
		return round;
	}

	/** client_bound：任一有效轮客户端核 &gt; 90% 即为 true，声明段写明；不算失败。 */
	@Test
	void clientBoundIsDeclaredButDoesNotFailTheRun() {
		Profile profile = ProfileLoader.parse(ProfileLoaderTest.BRIEF_EXAMPLE.replace("  p99_max: 20ms\n", ""), null, "brief");
		Map<String, Object> saturated = Map.of("client_cpus", "5-9", "client_busy_pct", 97.5, "server_cpus", "0-4", "server_busy_pct", 70.0);
		Map<String, Object> relaxed = Map.of("client_cpus", "5-9", "client_busy_pct", 60.0, "server_cpus", "0-4", "server_busy_pct", 99.0);
		Map<String, Object> summary = Map.of("official", Map.of("throughput_rps", 1000.0));

		List<Map<String, Object>> bound = List.of(round(true, saturated), round(true, relaxed));
		Map<String, Object> validity = BenchRunner.validity(profile, bound, summary, Map.of());
		assertEquals(Boolean.TRUE, validity.get("client_bound"));
		assertTrue(((List<?>) validity.get("statements")).contains(BenchRunner.CLIENT_BOUND_STATEMENT));
		assertEquals(Boolean.TRUE, validity.get("passed"), "client-bound is a statement, not a failure");

		assertFalse(CpuSampler.clientBound(List.of(round(true, relaxed), round(true, EnvironmentProbe.NA))));
		assertFalse(CpuSampler.clientBound(List.of(round(false, saturated))), "only valid rounds count");
		Map<String, Object> notBound = BenchRunner.validity(profile, List.of(round(true, relaxed)), summary, Map.of());
		assertEquals(Boolean.FALSE, notBound.get("client_bound"));
		assertFalse(((List<?>) notBound.get("statements")).contains(BenchRunner.CLIENT_BOUND_STATEMENT));
		assertEquals("client 97.5% / server 70.0%", CpuSampler.brief(saturated));
		assertEquals("n/a", CpuSampler.brief(EnvironmentProbe.NA));
	}
}
