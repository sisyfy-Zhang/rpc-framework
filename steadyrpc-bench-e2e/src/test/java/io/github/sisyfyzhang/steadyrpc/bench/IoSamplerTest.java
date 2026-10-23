package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link IoSampler}：/proc 文本解析、快照压平与还原、窗口差值与每请求值、Markdown 单元格。 */
class IoSamplerTest {

	@Test
	void parsesProcIoAndStatusLines() {
		Map<String, Long> io = IoSampler.fields(List.of("rchar: 100", "wchar: 200", "syscr: 7", "syscw: 9", "read_bytes: 0"), ':');
		assertEquals(7L, io.get("syscr"));
		assertEquals(9L, io.get("syscw"));
		Map<String, Long> status = IoSampler.fields(List.of("Name:\tjava", "State:\tS (sleeping)", "voluntary_ctxt_switches:\t1234",
				"nonvoluntary_ctxt_switches:\t5", "Cpus_allowed_list:\t5-9"), ':');
		assertEquals(1234L, status.get("voluntary_ctxt_switches"));
		assertEquals(5L, status.get("nonvoluntary_ctxt_switches"));
		assertNull(status.get("Name"), "non-numeric fields are skipped");
		assertNull(status.get("Cpus_allowed_list"));
	}

	@Test
	void snapshotsRoundTripThroughStringsAndDeltasDerivePerRequestValues() {
		IoSampler.Snapshot before = new IoSampler.Snapshot(100, 200, 1000, 10, 40);
		IoSampler.Snapshot after = new IoSampler.Snapshot(1100, 700, 3000, 30, 41);
		Map<String, String> flat = new LinkedHashMap<>(GcSampler.snapshot().toStrings());
		flat.putAll(after.toStrings());
		assertEquals(after, IoSampler.Snapshot.fromStrings(flat));
		assertNotNull(GcSampler.Snapshot.fromStrings(flat), "io keys do not disturb the GC fields of the same stats map");
		assertNull(IoSampler.Snapshot.fromStrings(GcSampler.snapshot().toStrings()), "a server without io fields (non-Linux, old) gives null");

		Map<String, Object> delta = IoSampler.delta(before, after);
		assertEquals(1000L, delta.get("syscr"));
		assertEquals(500L, delta.get("syscw"));
		assertEquals(2000L, delta.get("vol_cs"));
		assertEquals(20L, delta.get("nonvol_cs"));
		assertEquals("40 -> 41", delta.get("threads"));
		IoSampler.perRequest(delta, 1000);
		assertEquals(0.5, delta.get("syscw_per_request"));
		assertEquals(2.0, delta.get("vol_cs_per_request"));
		Map<String, Object> none = IoSampler.delta(before, after);
		IoSampler.perRequest(none, 0);
		assertNull(none.get("syscw_per_request"), "no requests in the window: no per-request value");
	}

	@Test
	void threadNamesAreGroupedByPrefix() {
		// comm 是线程名的前 15 字节：rpc-server-worker-3-1 → rpc-server-work
		assertEquals(IoSampler.GROUP_NETTY_IO, IoSampler.group("rpc-client-io-1"));
		assertEquals(IoSampler.GROUP_NETTY_IO, IoSampler.group("rpc-server-work"));
		assertEquals(IoSampler.GROUP_NETTY_IO, IoSampler.group("rpc-server-boss"));
		assertEquals(IoSampler.GROUP_BIZ, IoSampler.group("rpc-server-biz-"));
		assertEquals(IoSampler.GROUP_NETTY_IO, IoSampler.group("grpc-server-wor"));
		assertEquals(IoSampler.GROUP_BIZ, IoSampler.group("grpc-default-ex"));
		assertEquals(IoSampler.GROUP_NETTY_IO, IoSampler.group("NettyClientWork"));
		assertEquals(IoSampler.GROUP_BIZ, IoSampler.group("DubboServerHand"));
		assertEquals(IoSampler.GROUP_FJP, IoSampler.group("ForkJoinPool-1-"));
		assertEquals(IoSampler.GROUP_FJP, IoSampler.group("ForkJoinPool.co"));
		assertEquals(IoSampler.GROUP_TIMERS, IoSampler.group("CompletableFutu"));
		assertEquals(IoSampler.GROUP_TIMERS, IoSampler.group("VirtualThread-u"));
		assertEquals(IoSampler.GROUP_HARNESS, IoSampler.group("bench-dispatch"));
		assertEquals(IoSampler.GROUP_HARNESS, IoSampler.group("bench-open-work"));
		assertEquals(IoSampler.GROUP_NETTY_IO, IoSampler.group("bench-http-work"), "the HTTP card's event loops are IO, not harness");
		assertEquals(IoSampler.GROUP_JVM, IoSampler.group("GC Thread#3"));
		assertEquals(IoSampler.GROUP_JVM, IoSampler.group("G1 Refine#0"));
		assertEquals(IoSampler.GROUP_JVM, IoSampler.group("C2 CompilerThre"));
		assertEquals(IoSampler.GROUP_JVM, IoSampler.group("VM Periodic Tas"));
		assertEquals(IoSampler.GROUP_OTHER, IoSampler.group("java"));
		assertEquals(IoSampler.GROUP_OTHER, IoSampler.group("something-else"));
		assertEquals(IoSampler.GROUP_OTHER, IoSampler.group(null));
	}

	@Test
	void groupsRoundTripThroughStringsAndDeltaPerGroup() {
		IoSampler.Snapshot before = new IoSampler.Snapshot(100, 200, 1000, 10, 4, Map.of(
				IoSampler.GROUP_NETTY_IO, new IoSampler.GroupCounts(600, 6, 2),
				IoSampler.GROUP_BIZ, new IoSampler.GroupCounts(400, 4, 2)));
		IoSampler.Snapshot after = new IoSampler.Snapshot(1100, 700, 3000, 30, 5, Map.of(
				IoSampler.GROUP_NETTY_IO, new IoSampler.GroupCounts(1600, 16, 2),
				IoSampler.GROUP_BIZ, new IoSampler.GroupCounts(1400, 4, 2),
				IoSampler.GROUP_FJP, new IoSampler.GroupCounts(0, 10, 1)));
		Map<String, String> flat = after.toStrings();
		assertEquals("1600", flat.get("io.group.netty_io.vol_cs"));
		assertEquals(after, IoSampler.Snapshot.fromStrings(flat));
		Map<String, String> old = new LinkedHashMap<>(flat);
		old.keySet().removeIf(k -> k.startsWith("io.group."));
		assertTrue(IoSampler.Snapshot.fromStrings(old).groups().isEmpty(), "an old server without groups still parses");

		Map<String, Object> delta = IoSampler.delta(before, after);
		IoSampler.perRequest(delta, 1000);
		Map<?, ?> byGroup = (Map<?, ?>) delta.get("by_group");
		assertEquals(List.of(IoSampler.GROUP_NETTY_IO, IoSampler.GROUP_BIZ, IoSampler.GROUP_FJP), List.copyOf(byGroup.keySet()),
				"report order, only groups that exist");
		Map<?, ?> io = (Map<?, ?>) byGroup.get(IoSampler.GROUP_NETTY_IO);
		assertEquals(1000L, io.get("vol_cs"));
		assertEquals(10L, io.get("nonvol_cs"));
		assertEquals("2 -> 2", io.get("threads"));
		assertEquals(1.0, io.get("vol_cs_per_request"));
		assertEquals(1.01, io.get("cs_per_request"));
		Map<?, ?> fjp = (Map<?, ?>) byGroup.get(IoSampler.GROUP_FJP);
		assertEquals("0 -> 1", fjp.get("threads"), "a group that appeared inside the window counts from 0");
		assertEquals(0.01, fjp.get("cs_per_request"));
		assertNull(IoSampler.delta(new IoSampler.Snapshot(1, 1, 1, 1, 1), after).get("by_group"), "no groups on one side: no by_group");

		Map<String, Object> round = Map.of("io", Map.of("client", delta, "server", "unknown"));
		assertTrue(IoSampler.hasGroups(round));
		assertFalse(IoSampler.hasGroups(Map.of("io", "n/a")));
		assertEquals(1.01, IoSampler.groupPerRequest(round, "client", IoSampler.GROUP_NETTY_IO));
		assertNull(IoSampler.groupPerRequest(round, "server", IoSampler.GROUP_NETTY_IO));
		StringBuilder md = new StringBuilder();
		IoSampler.groupTable(md, round);
		String table = md.toString();
		assertTrue(table.contains("| netty_io | 2 -> 2 | 1.010（1.000 / 0.010） | — | — | — | — |"), table);
		assertTrue(table.contains("| 合计 | | 2.020 | — | | — | — |"), table);
		assertNull(delta.get("cpu_ms"), "snapshots without cpu ticks: no cpu_ms on the side");
		assertNull(io.get("cpu_ms"), "nor per group");
	}

	@Test
	void parsesUtimePlusStimeFromTaskStat() {
		// 线程名里带空格与括号：从最后一个 ')' 之后数，第 14、15 项是 utime / stime
		String stat = "4242 (rpc-client) io-1) S 4200 4200 4200 0 -1 4194368 120 0 0 0 1234 56 0 0 20 0 1 0 100 0 0 18446744073709551615";
		assertEquals(1290L, IoSampler.cpuTicks(stat));
		assertEquals(-1L, IoSampler.cpuTicks("garbage"));
		assertEquals(-1L, IoSampler.cpuTicks("1 (x) S 1 2"), "too few fields");
		assertEquals(-1L, IoSampler.cpuTicks(null));
	}

	@Test
	void cpuTicksBecomeMillisecondsAndMicrosecondsPerRequestPerGroup() {
		IoSampler.Snapshot before = new IoSampler.Snapshot(100, 200, 1000, 10, 4, Map.of(
				IoSampler.GROUP_NETTY_IO, new IoSampler.GroupCounts(600, 6, 2, 1000),
				IoSampler.GROUP_FJP, new IoSampler.GroupCounts(400, 4, 2, 500)));
		IoSampler.Snapshot after = new IoSampler.Snapshot(1100, 700, 3000, 30, 5, Map.of(
				IoSampler.GROUP_NETTY_IO, new IoSampler.GroupCounts(1600, 16, 2, 1350),
				IoSampler.GROUP_FJP, new IoSampler.GroupCounts(1400, 4, 2, 600),
				IoSampler.GROUP_JVM, new IoSampler.GroupCounts(0, 10, 1, 5)));
		Map<String, String> flat = after.toStrings();
		assertEquals("1350", flat.get("io.group.netty_io.cpu_ticks"));
		assertEquals(after, IoSampler.Snapshot.fromStrings(flat), "cpu ticks round-trip through the stats map");

		Map<String, Object> delta = IoSampler.delta(before, after);
		IoSampler.perRequest(delta, 100_000);
		Map<?, ?> byGroup = (Map<?, ?>) delta.get("by_group");
		Map<?, ?> io = (Map<?, ?>) byGroup.get(IoSampler.GROUP_NETTY_IO);
		assertEquals(3500L, io.get("cpu_ms"), "350 ticks x 10ms");
		assertEquals(35.0, io.get("cpu_us_per_request"), "3500ms / 100000 requests = 35us");
		assertEquals(1000L, ((Map<?, ?>) byGroup.get(IoSampler.GROUP_FJP)).get("cpu_ms"));
		assertEquals(50L, ((Map<?, ?>) byGroup.get(IoSampler.GROUP_JVM)).get("cpu_ms"), "a group that appeared inside the window counts from 0");
		assertEquals(4550L, delta.get("cpu_ms"), "the side total is the sum of the groups");
		assertEquals(45.5, delta.get("cpu_us_per_request"));

		// 一端的某次快照不带 CPU（旧服务端）：整端不出 cpu 字段，不出半套
		IoSampler.Snapshot old = new IoSampler.Snapshot(100, 200, 1000, 10, 4, Map.of(
				IoSampler.GROUP_NETTY_IO, new IoSampler.GroupCounts(600, 6, 2),
				IoSampler.GROUP_FJP, new IoSampler.GroupCounts(400, 4, 2)));
		Map<String, Object> mixed = IoSampler.delta(old, after);
		assertNull(mixed.get("cpu_ms"));
		assertNull(((Map<?, ?>) ((Map<?, ?>) mixed.get("by_group")).get(IoSampler.GROUP_NETTY_IO)).get("cpu_ms"));

		Map<String, Object> round = Map.of("io", Map.of("client", delta, "server", mixed));
		StringBuilder md = new StringBuilder();
		IoSampler.groupTable(md, round);
		String table = md.toString();
		assertTrue(table.contains("| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 客户端 CPU µs/请求 | 服务端 线程数 |"), table);
		assertTrue(table.contains("| netty_io | 2 -> 2 | 0.010（0.010 / 0.000） | 35.00 | 2 -> 2 |"), table);
		assertTrue(table.contains("| 合计 | | 0.020 | 45.50 | |"), table);
	}

	@Test
	void markdownCellsShowPerRequestValuesOrWhatWasRecorded() {
		Map<String, Object> client = new LinkedHashMap<>(Map.of("syscw", 1200L));
		IoSampler.perRequest(client, 1000);
		Map<String, Object> round = Map.of("io", Map.of("client", client, "server", "unknown"));
		assertEquals("1.20", IoSampler.cell(round, "client", "syscw"));
		assertEquals("unknown", IoSampler.cell(round, "server", "syscw"));
		assertEquals("—", IoSampler.cell(Map.of(), "client", "syscw"), "old result files have no io");
		assertEquals("n/a", IoSampler.cell(Map.of("io", "n/a"), "client", "syscw"));
	}

	@Test
	void liveSnapshotOnLinuxOnly() {
		IoSampler.Snapshot now = IoSampler.snapshot();
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
			assertNotNull(now, "/proc/self/io must be readable on Linux");
			assertTrue(now.threads() > 1 && now.volCs() > 0, now.toString());
			assertEquals(now.volCs(), now.groups().values().stream().mapToLong(IoSampler.GroupCounts::volCs).sum(), "groups sum to the total");
			assertEquals(now.threads(), now.groups().values().stream().mapToInt(IoSampler.GroupCounts::threads).sum());
			assertTrue(now.groups().values().stream().allMatch(IoSampler.GroupCounts::hasCpu), "utime + stime per group: " + now.groups());
			assertTrue(now.groups().values().stream().mapToLong(IoSampler.GroupCounts::cpuTicks).sum() > 0, "a JVM has used some CPU");
			assertTrue(ServerProbes.stats().containsKey("io.group.jvm.cpu_ticks"));
			assertTrue(now.groups().containsKey(IoSampler.GROUP_JVM), now.groups().toString());
			assertTrue(ServerProbes.stats().containsKey("io.group.jvm.vol_cs"));
			assertTrue(ServerProbes.stats().containsKey("io.syscw"));
		} else {
			assertNull(now);
			assertTrue(!ServerProbes.stats().containsKey("io.syscw") && ServerProbes.stats().containsKey("gc_pause_ms"));
		}
	}
}
