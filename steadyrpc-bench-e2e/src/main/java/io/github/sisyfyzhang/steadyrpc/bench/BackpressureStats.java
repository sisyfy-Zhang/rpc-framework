package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * rpc 服务端的出站背压计数（{@code RpcServer.autoReadPauses()} / {@code outboundOverloadRejections()}）：bench server 的统计端点
 * （{@code BenchService.stats()}）以累计值回传，压测端在统计窗口起止各取一次（与 GC / io 同一次调用）做差，进
 * {@code rounds[].backpressure}：
 * <pre>
 * {"source": "...", "window": {"auto_read_pauses": n, "outbound_overload_rejections": m,
 *                               "client_send_buffer_rejections": r, "client_unwritable_switches": s},
 *  "cumulative_at_window_end": {...同四项}}
 * </pre>
 * 后两项来自压测端的 {@code RpcClient.sendBufferRejections()} / {@code unwritableConnectionSwitches()}（{@link CallTarget#clientStats()}），
 * 取不到时这两项不出现。服务端统计取不到时整项记 {@code unknown}；取到了但没有服务端的键（对比对象、HTTP 端点、旧服务端）记 {@code n/a}。
 */
final class BackpressureStats {

	static final String AUTO_READ_PAUSES_KEY = "backpressure.auto_read_pauses";
	static final String OUTBOUND_OVERLOAD_REJECTIONS_KEY = "backpressure.outbound_overload_rejections";
	static final String CLIENT_SEND_BUFFER_REJECTIONS_KEY = "backpressure.client_send_buffer_rejections";
	static final String CLIENT_UNWRITABLE_SWITCHES_KEY = "backpressure.client_unwritable_switches";
	static final String SOURCE = "server stats (RpcServer.autoReadPauses / outboundOverloadRejections)"
			+ " + harness client (RpcClient.sendBufferRejections / unwritableConnectionSwitches)";

	private BackpressureStats() {
	}

	/** 只有服务端统计（旧调用方）。 */
	static Object delta(Map<String, String> before, Map<String, String> after) {
		return delta(before, after, null, null);
	}

	/** 窗口内差值与窗口结束时的累计值；见类注释。{@code clientBefore / clientAfter} 为 null 或缺键时不出客户端两项。 */
	static Object delta(Map<String, String> before, Map<String, String> after, Map<String, String> clientBefore,
			Map<String, String> clientAfter) {
		if (before == null || after == null) {
			return EnvironmentProbe.UNKNOWN;
		}
		Long pausesBefore = number(before, AUTO_READ_PAUSES_KEY);
		Long pausesAfter = number(after, AUTO_READ_PAUSES_KEY);
		Long rejectionsBefore = number(before, OUTBOUND_OVERLOAD_REJECTIONS_KEY);
		Long rejectionsAfter = number(after, OUTBOUND_OVERLOAD_REJECTIONS_KEY);
		if (pausesBefore == null || pausesAfter == null || rejectionsBefore == null || rejectionsAfter == null) {
			return EnvironmentProbe.NA;
		}
		Map<String, Object> window = new LinkedHashMap<>();
		window.put("auto_read_pauses", pausesAfter - pausesBefore);
		window.put("outbound_overload_rejections", rejectionsAfter - rejectionsBefore);
		Map<String, Object> cumulative = new LinkedHashMap<>();
		cumulative.put("auto_read_pauses", pausesAfter);
		cumulative.put("outbound_overload_rejections", rejectionsAfter);
		if (clientBefore != null && clientAfter != null) {
			for (String[] k : new String[][] { { CLIENT_SEND_BUFFER_REJECTIONS_KEY, "client_send_buffer_rejections" },
					{ CLIENT_UNWRITABLE_SWITCHES_KEY, "client_unwritable_switches" } }) {
				Long b = number(clientBefore, k[0]);
				Long a = number(clientAfter, k[0]);
				if (a != null && b != null) {
					window.put(k[1], a - b);
					cumulative.put(k[1], a);
				}
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", SOURCE);
		out.put("window", window);
		out.put("cumulative_at_window_end", cumulative);
		return out;
	}

	private static Long number(Map<String, String> stats, String key) {
		String value = stats.get(key);
		if (value == null) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 窗口内的暂停读次数；没有该字段（旧结果文件）为 —，取不到原样输出 n/a / unknown。 */
	static Long windowPauses(Map<?, ?> round) {
		return windowValue(round, "auto_read_pauses");
	}

	private static Long windowValue(Map<?, ?> round, String key) {
		if (round.get("backpressure") instanceof Map<?, ?> bp && bp.get("window") instanceof Map<?, ?> w
				&& w.get(key) instanceof Number n) {
			return n.longValue();
		}
		return null;
	}

	/**
	 * Markdown 的一格"暂停读次数"：窗口内的暂停次数；窗口内有硬上限拒绝时追加"（硬上限拒绝 m）"。
	 * 没有该字段（旧结果文件）为 —，取不到原样输出 n/a / unknown。
	 */
	static String cell(Map<?, ?> round) {
		Long pauses = windowPauses(round);
		if (pauses != null) {
			Long rejections = windowValue(round, "outbound_overload_rejections");
			return rejections != null && rejections > 0 ? pauses + "（硬上限拒绝 " + rejections + "）" : String.valueOf(pauses);
		}
		Object bp = round.get("backpressure");
		return bp == null || bp instanceof Map<?, ?> ? "—" : String.valueOf(bp);
	}
}
