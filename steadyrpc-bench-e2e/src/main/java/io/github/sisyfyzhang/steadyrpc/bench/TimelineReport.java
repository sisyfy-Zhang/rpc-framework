package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 故障注入 / 阶梯卡的 Markdown 段落（由 {@link MarkdownReport} 在轮次表之后调用；只读 JSON）：服务端执行器（窗口）、阶梯表、故障表与
 * 每秒时间线表。没有这些字段的结果（原口径的卡、旧结果文件）什么都不输出。
 */
final class TimelineReport {

	private TimelineReport() {
	}

	static void render(StringBuilder md, String sub, List<Object> rounds) {
		for (Object r : rounds) {
			if (!(r instanceof Map<?, ?> round)) {
				continue;
			}
			String n = String.valueOf(round.get("round"));
			if (round.get("steps") instanceof List<?> steps && !steps.isEmpty()) {
				md.append(sub).append(" 阶梯（第 ").append(n).append(" 轮）\n\n");
				steps(md, steps);
			}
			if (round.get("faults") instanceof List<?> faults && !faults.isEmpty()) {
				md.append(sub).append(" 故障（第 ").append(n).append(" 轮）\n\n");
				faults(md, faults);
			}
			if (round.get("timeline") instanceof Map<?, ?> timeline && timeline.get("rows") instanceof List<?> rows && !rows.isEmpty()) {
				md.append(sub).append(" 时间线（第 ").append(n).append(" 轮，每秒）\n\n");
				timeline(md, rows);
			}
		}
	}

	/** 轮次表之后的一行：统计窗口内服务端执行器的拒绝数与排队等待（有这些字段时）。 */
	static void windowExecutorLine(StringBuilder md, List<Object> rounds, Object officialRound) {
		for (Object r : rounds) {
			if (r instanceof Map<?, ?> round && officialRound != null && officialRound.equals(round.get("round"))
					&& round.get("server_executor") instanceof Map<?, ?> se) {
				Map<?, ?> wait = se.get("queue_wait_ms") instanceof Map<?, ?> w ? w : Map.of();
				md.append("服务端业务执行器（正式轮统计窗口内）：过载拒绝 ").append(str(se.get("executor_rejections"))).append("，停机拒绝 ")
						.append(str(se.get("shutdown_rejections"))).append("，排队等待 p50 / p99 / max ").append(str(wait.get("p50"))).append(" / ")
						.append(str(wait.get("p99"))).append(" / ").append(str(wait.get("max"))).append(" ms（").append(str(wait.get("count")))
						.append(" 个请求；到达 → 业务线程开始执行）。\n\n");
			}
		}
	}

	private static void steps(StringBuilder md, List<?> steps) {
		md.append("| 级 | 速率 (req/s) | 时段 (s) | 请求 | 成功率 | 吞吐 (req/s) | p50 (ms) | p99 (ms) | max (ms) | 失败分类 | 服务端过载拒绝 | 停机拒绝 "
				+ "| 排队等待 p99 (ms) | 错误恢复 (s) | p99 恢复 (s) |\n");
		md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (Object o : steps) {
			Map<?, ?> s = (Map<?, ?>) o;
			Map<?, ?> latency = map(s.get("latency_ms"));
			Map<?, ?> server = map(s.get("server"));
			Map<?, ?> wait = map(server.get("queue_wait_ms"));
			Map<?, ?> recovery = map(s.get("recovery"));
			md.append("| ").append(str(s.get("index"))).append(" | ").append(str(s.get("rate"))).append(" | ")
					.append(seconds(s.get("start_ms"))).append("–").append(seconds(s.get("end_ms"))).append(" | ").append(str(s.get("requests")))
					.append(" | ").append(pct(s.get("success_rate"))).append(" | ").append(num(s.get("throughput_rps"), 1)).append(" | ")
					.append(num(latency.get("p50"), 3)).append(" | ").append(num(latency.get("p99"), 3)).append(" | ").append(num(latency.get("max"), 3))
					.append(" | ").append(types(s.get("errors_by_type"))).append(" | ").append(str(server.get("executor_rejections"))).append(" | ")
					.append(str(server.get("shutdown_rejections"))).append(" | ").append(num(wait.get("p99"), 3)).append(" | ")
					.append(recovery.isEmpty() ? "—" : recoveryCell(recovery, "errors_recovery_s")).append(" | ")
					.append(recovery.isEmpty() ? "—" : recoveryCell(recovery, "p99_recovery_s")).append(" |\n");
		}
		md.append("\n按完成时刻归级；服务端两列是该级起止两次采样之差（全部提供者之和）；恢复 = 该级开始后到\"连续 ")
				.append(FaultTimeline.RECOVERY_CLEAN_SECONDS).append(" 秒错误为 0（p99 列另要求每秒 p99 ≤ ")
				.append((int) FaultTimeline.P99_RECOVERY_FACTOR).append(" × 基线）\"的第一秒，秒粒度。\n\n");
	}

	private static void faults(StringBuilder md, List<?> faults) {
		md.append("| # | 计划 (s) | 实际 (s) | 故障 | 目标 | 状态 | 错误恢复 (s) | p99 恢复 (s) | 份额恢复 (s) | 恢复前错误数 | 有错误的秒 | 摘除次数 |\n");
		md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (Object o : faults) {
			Map<?, ?> f = (Map<?, ?>) o;
			Map<?, ?> r = map(f.get("recovery"));
			String kind = str(f.get("kind")) + params(f);
			String target = "#" + str(f.get("target")) + (f.get("target_address") == null ? "" : "（" + f.get("target_address") + "）");
			Object firstError = r.get("first_error_second");
			Object lastError = r.get("last_error_second");
			md.append("| ").append(str(f.get("index"))).append(" | ").append(seconds(f.get("at_ms"))).append(" | ").append(seconds(f.get("executed_at_ms")))
					.append(" | ").append(kind).append(" | ").append(target).append(" | ").append(str(f.get("status"))).append(" | ")
					.append(recoveryCell(r, "errors_recovery_s")).append(" | ").append(recoveryCell(r, "p99_recovery_s")).append(" | ")
					.append(r.containsKey("share_recovery_s") ? recoveryCell(r, "share_recovery_s") : "—").append(" | ")
					.append(str(r.get("errors_until_recovery"))).append(" | ")
					.append(firstError == null ? "—" : firstError + "–" + lastError).append(" | ")
					.append(str(f.get("outlier_ejections"))).append(f.get("outlier_restores") instanceof Number restores
							? "（回归 " + restores + "）" : "").append(" |\n");
		}
		md.append("\n恢复：故障时刻（有执行记录用实际时刻）到\"连续 ").append(FaultTimeline.RECOVERY_CLEAN_SECONDS)
				.append(" 秒错误为 0\"的第一秒起点；p99 恢复另要求每秒 p99 ≤ ").append((int) FaultTimeline.P99_RECOVERY_FACTOR)
				.append(" × 基线（第一个事件之前各秒 p99 的中位数）；份额恢复只对 remove-toxic / sigcont：目标节点每秒份额 ≥ ")
				.append(FaultTimeline.SHARE_RECOVERY_FRACTION).append(" × 故障前的平均份额。秒粒度；\"未恢复\" = 到本轮结束都没满足。")
				.append("摘除次数 = 故障那一秒起到本轮结束压测端客户端的离群摘除次数（括号里是到期回归次数）。\n\n");
	}

	private static void timeline(StringBuilder md, List<?> rows) {
		md.append("| t (s) | 成功 | 失败 | 失败分类 | p50 (ms) | p99 (ms) | 节点份额（尝试数） | 重试 | 被摘节点（累计摘除） | 服务端拒绝 过载/停机 | 排队数 "
				+ "| 排队等待 p99 (ms) | 不可达提供者 |\n");
		md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (Object o : rows) {
			Map<?, ?> row = (Map<?, ?>) o;
			md.append("| ").append(str(row.get("t"))).append(" | ").append(str(row.get("success"))).append(" | ").append(str(row.get("errors")))
					.append(" | ").append(types(row.get("errors_by_type"))).append(" | ").append(num(row.get("p50_ms"), 3)).append(" | ")
					.append(num(row.get("p99_ms"), 3)).append(" | ").append(shares(row)).append(" | ").append(str(row.get("retries"))).append(" | ")
					.append(str(row.get("ejected_nodes"))).append(row.get("ejections") == null ? "" : "（" + row.get("ejections") + "）").append(" | ")
					.append(str(row.get("server_executor_rejections"))).append(" / ").append(str(row.get("server_shutdown_rejections")))
					.append(" | ").append(str(row.get("server_queued"))).append(" | ").append(num(row.get("server_queue_wait_p99_ms"), 3))
					.append(" | ").append(row.get("providers_unreachable") instanceof List<?> l && !l.isEmpty() ? String.join(" ", l.stream()
							.map(String::valueOf).toList()) : "—")
					.append(" |\n");
		}
		md.append("\n成功 / 失败 / 延迟按完成时刻归秒（延迟从计划发出时刻算，2 位有效数字）；节点份额 = 该秒压测端向各节点发出的尝试数占比（含重试）；"
				+ "被摘节点 = 该秒末处于离群摘除期的提供者数，括号里是累计摘除次数；服务端列是每秒对各提供者 counters() 采样之差（全部提供者之和），排队数是该秒末的瞬时值。\n\n");
	}

	private static String shares(Map<?, ?> row) {
		if (!(row.get("node_share") instanceof Map<?, ?> share) || share.isEmpty()) {
			return "—";
		}
		Map<?, ?> attempts = map(row.get("node_attempts"));
		List<String> parts = new ArrayList<>();
		for (Map.Entry<?, ?> e : share.entrySet()) {
			String address = String.valueOf(e.getKey());
			String port = address.substring(address.lastIndexOf(':') + 1);
			parts.add(":" + port + " " + (e.getValue() == null ? "—" : String.format(Locale.ROOT, "%.0f%%", ((Number) e.getValue()).doubleValue() * 100))
					+ "（" + str(attempts.get(e.getKey())) + "）");
		}
		return String.join(" · ", parts);
	}

	private static String params(Map<?, ?> f) {
		List<String> p = new ArrayList<>();
		for (String key : List.of("latency_ms", "jitter_ms", "timeout_ms")) {
			if (f.get(key) instanceof Number n) {
				p.add(key.replace("_ms", "") + " " + num(n, 0) + "ms");
			}
		}
		if (f.get("stream") != null) {
			p.add(String.valueOf(f.get("stream")));
		}
		return p.isEmpty() ? "" : "（" + String.join("，", p) + "）";
	}

	private static String recoveryCell(Map<?, ?> r, String key) {
		if (!r.containsKey(key)) {
			return "—";
		}
		Object v = r.get(key);
		return v == null ? "未恢复" : num(v, 1);
	}

	private static String types(Object byType) {
		if (!(byType instanceof Map<?, ?> m) || m.isEmpty()) {
			return "—";
		}
		List<String> parts = new ArrayList<>();
		m.forEach((k, v) -> parts.add(k + " " + v));
		return String.join("，", parts);
	}

	private static String seconds(Object ms) {
		return ms instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue() / 1000.0) : "—";
	}

	private static String pct(Object ratio) {
		return ratio instanceof Number n ? String.format(Locale.ROOT, "%.2f%%", n.doubleValue() * 100) : "—";
	}

	private static String num(Object value, int decimals) {
		return value instanceof Number n ? String.format(Locale.ROOT, "%." + decimals + "f", n.doubleValue()) : "—";
	}

	private static String str(Object value) {
		return value == null ? "—" : String.valueOf(value);
	}

	private static Map<?, ?> map(Object value) {
		return value instanceof Map<?, ?> m ? m : Map.of();
	}
}
