package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code rounds[].backpressure}：窗口起止两份服务端统计的差值，以及 Markdown 轮次表"暂停读次数"一格。 */
class BackpressureStatsTest {

	private static Map<String, String> stats(long pauses, long rejections) {
		return Map.of(BackpressureStats.AUTO_READ_PAUSES_KEY, String.valueOf(pauses),
				BackpressureStats.OUTBOUND_OVERLOAD_REJECTIONS_KEY, String.valueOf(rejections), "gc.x", "1");
	}

	@Test
	void windowDeltaAndCumulativeValues() {
		Map<?, ?> bp = (Map<?, ?>) BackpressureStats.delta(stats(5, 0), stats(12, 3));
		assertEquals(7L, ((Map<?, ?>) bp.get("window")).get("auto_read_pauses"));
		assertEquals(3L, ((Map<?, ?>) bp.get("window")).get("outbound_overload_rejections"));
		assertEquals(12L, ((Map<?, ?>) bp.get("cumulative_at_window_end")).get("auto_read_pauses"));
		assertEquals("7（硬上限拒绝 3）", BackpressureStats.cell(Map.of("backpressure", bp)));
		assertEquals("0", BackpressureStats.cell(Map.of("backpressure", BackpressureStats.delta(stats(2, 1), stats(2, 1)))));
	}

	@Test
	void harnessClientCountersSitNextToTheServerOnes() {
		Map<String, String> before = Map.of(BackpressureStats.CLIENT_SEND_BUFFER_REJECTIONS_KEY, "1",
				BackpressureStats.CLIENT_UNWRITABLE_SWITCHES_KEY, "10");
		Map<String, String> after = Map.of(BackpressureStats.CLIENT_SEND_BUFFER_REJECTIONS_KEY, "4",
				BackpressureStats.CLIENT_UNWRITABLE_SWITCHES_KEY, "10");
		Map<?, ?> bp = (Map<?, ?>) BackpressureStats.delta(stats(0, 0), stats(2, 0), before, after);
		Map<?, ?> window = (Map<?, ?>) bp.get("window");
		assertEquals(2L, window.get("auto_read_pauses"));
		assertEquals(3L, window.get("client_send_buffer_rejections"));
		assertEquals(0L, window.get("client_unwritable_switches"));
		assertEquals(10L, ((Map<?, ?>) bp.get("cumulative_at_window_end")).get("client_unwritable_switches"));
		Map<?, ?> noClient = (Map<?, ?>) ((Map<?, ?>) BackpressureStats.delta(stats(0, 0), stats(2, 0), Map.of(), Map.of())).get("window");
		assertEquals(false, noClient.containsKey("client_send_buffer_rejections"), "targets without client counters leave them out");
	}

	@Test
	void missingStatsAreUnknownAndServersWithoutTheCountersAreNa() {
		assertEquals(EnvironmentProbe.UNKNOWN, BackpressureStats.delta(null, stats(1, 0)));
		assertEquals(EnvironmentProbe.NA, BackpressureStats.delta(Map.of("gc.x", "1"), Map.of("gc.x", "2")),
				"comparison servers (gRPC / Dubbo) and HTTP endpoints do not report them");
		assertEquals(EnvironmentProbe.NA, BackpressureStats.cell(Map.of("backpressure", EnvironmentProbe.NA)));
		assertEquals("—", BackpressureStats.cell(Map.of()), "result files written before the field existed");
	}
}
