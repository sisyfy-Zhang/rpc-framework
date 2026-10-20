package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
			assertTrue(ServerProbes.stats().containsKey("io.syscw"));
		} else {
			assertNull(now);
			assertTrue(!ServerProbes.stats().containsKey("io.syscw") && ServerProbes.stats().containsKey("gc_pause_ms"));
		}
	}
}
