package com.feynman.rpc.bench;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对比表（{@code compare} 子命令，{@code report} 汇总时也附上）：把<b>同一负载模型、同一负载大小与时长口径</b>、只在
 * transport / 并发 / 速率 / 客户端 IO 线程数上不同的结果并排成表。每行取该结果的正式轮；哪些字段不同由程序识别，作为对比轴。
 *
 * <ul>
 *   <li><b>分组</b>：负载模型、服务、payload（kind + size）、时长 / 预热 / 统计窗口 / 轮数 / 超时、是否同机，全部相同的结果为一组。</li>
 *   <li><b>对比轴</b>：组内取值不止一种的 {@code transport}（连同序列化方式）、{@code concurrency}（闭环）、{@code rate}（开环）、
 *       {@code io_threads}（harness 的 {@code --io-threads}）、{@code server_executor}（服务端业务执行器）、{@code flush_consolidation}
 *       （本框架两端是否合并 flush）。只有一个轴在变 → 一张表；多个轴在变 → 每个轴一张表，
 *       表内其余轴取值相同（例如 rpc vs grpc 的 64 并发一张、rpc 的 64 / 128 / 256 并发一张）。没有轴在变（同一张卡跑了多次）→ 一张"重复运行"表。</li>
 *   <li>只读 JSON（与 {@link MarkdownReport} 同一原则）：表里每个数字都能在对应的结果文件里找到。</li>
 * </ul>
 */
final class CompareReport {

	static final String AXIS_TRANSPORT = "transport";
	static final String AXIS_CONCURRENCY = "concurrency";
	static final String AXIS_RATE = "rate";
	static final String AXIS_IO_THREADS = "io_threads";
	static final String AXIS_CONNECTIONS = "connections";
	/** 服务端业务执行器（服务端环境的 {@code server_executor_kind}：fixed / virtual / direct / grpc-default…）；旧结果没有时为 —。 */
	static final String AXIS_SERVER_EXECUTOR = "server_executor";
	/** 本框架两端的 flush 合并（{@code run.target.flush_consolidation} / {@code server_environment.server_flush_consolidation}）；其余为 —。 */
	static final String AXIS_FLUSH = "flush_consolidation";
	private static final List<String> AXES = List.of(AXIS_TRANSPORT, AXIS_CONNECTIONS, AXIS_CONCURRENCY, AXIS_RATE, AXIS_IO_THREADS,
			AXIS_SERVER_EXECUTOR, AXIS_FLUSH);
	/** transport 列的排序：本框架在前，对比对象按接入顺序，其余按字母。 */
	private static final List<String> TRANSPORT_ORDER = List.of("rpc", "grpc", "dubbo", "http");

	private CompareReport() {
	}

	/** 一份结果里对比要用的字段（全部取自 JSON）。 */
	record Run(String file, Map<?, ?> json, Map<String, String> axes, String groupKey, String groupTitle, boolean closed,
			boolean explicitConnections) {

		/** connections 轴的显示：实际连接数，卡片没写 target.connections（各框架缺省）时标"缺省"。 */
		String connectionsText() {
			String actual = axes.get(AXIS_CONNECTIONS);
			return explicitConnections || "—".equals(actual) ? actual : actual + "（缺省）";
		}

		String axisText(String axis) {
			return AXIS_CONNECTIONS.equals(axis) ? connectionsText() : axes.get(axis);
		}

		Map<?, ?> officialRound() {
			Object official = MarkdownReport.path(json, "summary", "official_round");
			for (Object r : list(json.get("rounds"))) {
				if (r instanceof Map<?, ?> round && Objects.equals(official, round.get("round"))) {
					return round;
				}
			}
			return Map.of();
		}

		String startedAt() {
			return String.valueOf(MarkdownReport.path(json, "environment", "started_at"));
		}
	}

	/** {@code compare} 子命令的整份文档。 */
	static String document(List<Map.Entry<String, Map<?, ?>>> results) {
		StringBuilder md = new StringBuilder();
		md.append("# 对比表\n\n");
		md.append("由 `rpc-bench-e2e compare` 从 JSON 结果文件生成；每行取该结果的正式轮，每个数字都可在对应 JSON 中找到。\n\n");
		String body = render(results, 2);
		md.append(body.isEmpty() ? "没有可以并排的结果：需要至少两份负载模型、负载大小与时长口径相同的结果。\n" : body);
		return md.toString();
	}

	/**
	 * @param level 每组标题的级别（{@code report} 汇总里是 3，{@code compare} 文档里是 2）
	 * @return 没有任何一组有两份以上结果时为空串
	 */
	static String render(List<Map.Entry<String, Map<?, ?>>> results, int level) {
		Map<String, List<Run>> groups = new LinkedHashMap<>();
		for (Map.Entry<String, Map<?, ?>> e : results) {
			Run run = run(e.getKey(), e.getValue());
			groups.computeIfAbsent(run.groupKey(), k -> new ArrayList<>()).add(run);
		}
		StringBuilder md = new StringBuilder();
		String h = "#".repeat(level);
		for (List<Run> group : groups.values()) {
			if (group.size() < 2) {
				continue;
			}
			group.sort(rowOrder());
			md.append(h).append(' ').append(group.get(0).groupTitle()).append("\n\n");
			List<String> varying = new ArrayList<>();
			for (String axis : AXES) {
				if (group.stream().map(r -> r.axes().get(axis)).distinct().count() > 1) {
					varying.add(axis);
				}
			}
			if (varying.size() <= 1) {
				String axis = varying.isEmpty() ? null : varying.get(0);
				md.append(axis == null ? "> 同一口径的重复运行（没有字段不同）。\n\n" : "> 对比轴：`" + axis + "`。\n\n");
				table(md, group, axis);
				continue;
			}
			// 多个轴在变：每个轴一张表，表内其余变化的轴取值相同
			for (String axis : varying) {
				if (AXIS_TRANSPORT.equals(axis) && varying.contains(AXIS_CONNECTIONS)) {
					transportTablesAcrossConnectionModes(md, group, varying);
					continue;
				}
				emitPartitions(md, partition(group, axis, varying, false), axis, "");
			}
		}
		return md.toString();
	}

	/**
	 * transport 与连接数同时在变（对比卡的"开箱即用"与"连接数对齐"两种配置）：transport 的并排分两类——
	 * <ul>
	 *   <li><b>开箱即用</b>：卡片都没写 {@code target.connections}，各框架用自己的缺省连接数（本框架 2、gRPC 1 个 channel、Dubbo 1 条）；</li>
	 *   <li><b>连接数对齐</b>：实际连接数相同（写了 {@code target.connections} 的卡，加上缺省恰好相等的卡）。</li>
	 * </ul>
	 */
	private static void transportTablesAcrossConnectionModes(StringBuilder md, List<Run> group, List<String> varying) {
		List<Run> defaults = group.stream().filter(r -> !r.explicitConnections()).toList();
		emitPartitions(md, partition(defaults, AXIS_TRANSPORT, varying, true), AXIS_TRANSPORT, "开箱即用：各框架缺省连接数");
		Map<String, List<Run>> aligned = partition(group, AXIS_TRANSPORT, varying, false);
		aligned.values().removeIf(rows -> rows.stream().noneMatch(Run::explicitConnections));
		emitPartitions(md, aligned, AXIS_TRANSPORT, "连接数对齐");
	}

	/** 按"其余变化的轴"的取值分区；{@code ignoreConnections} 时分区键里不含连接数。 */
	private static Map<String, List<Run>> partition(List<Run> rows, String axis, List<String> varying, boolean ignoreConnections) {
		Map<String, List<Run>> partitions = new LinkedHashMap<>();
		for (Run run : rows) {
			List<String> others = new ArrayList<>();
			for (String other : varying) {
				if (!other.equals(axis) && !(ignoreConnections && AXIS_CONNECTIONS.equals(other))) {
					others.add(other + " = " + run.axes().get(other));
				}
			}
			partitions.computeIfAbsent(String.join("，", others), k -> new ArrayList<>()).add(run);
		}
		return partitions;
	}

	private static void emitPartitions(StringBuilder md, Map<String, List<Run>> partitions, String axis, String label) {
		partitions.forEach((fixed, rows) -> {
			if (rows.stream().map(r -> r.axes().get(axis)).distinct().count() > 1) {
				String detail = label.isEmpty() ? fixed : fixed.isEmpty() ? label : label + "；" + fixed;
				md.append("> 对比轴：`").append(axis).append("`").append(detail.isEmpty() ? "" : "（" + detail + "）").append("。\n\n");
				table(md, rows, axis);
			}
		});
	}

	private static void table(StringBuilder md, List<Run> rows, String axis) {
		boolean closed = rows.get(0).closed();
		List<String> header = new ArrayList<>();
		if (axis != null) {
			header.add(axisTitle(axis));
		}
		if (!AXIS_TRANSPORT.equals(axis)) {
			header.add("transport");
		}
		header.addAll(List.of("口径卡", "吞吐 (req/s)", "吞吐 / 首行", "p50 (ms)", "p99 (ms)", "p99.9 (ms)", "max (ms)"));
		if (!closed) {
			header.add("服务时间 p99 (ms)");
		}
		header.addAll(List.of("客户端核 CPU", "服务端核 CPU", "client_bound", "GC 停顿 客户端/服务端 (ms)", "write 系统调用/请求 客户端/服务端",
				"上下文切换/请求 客户端/服务端", "窗口错误", "判定", "连接",
				"客户端 IO 线程", "服务端配置", "结果文件"));
		md.append("| ").append(String.join(" | ", header)).append(" |\n|").append("---|".repeat(header.size())).append('\n');
		Double first = null;
		for (Run run : rows) {
			Map<?, ?> json = run.json();
			Map<?, ?> official = map(MarkdownReport.path(json, "summary", "official"));
			Map<?, ?> round = run.officialRound();
			Double throughput = official.get("throughput_rps") instanceof Number n ? n.doubleValue() : null;
			if (first == null) {
				first = throughput;
			}
			List<String> cells = new ArrayList<>();
			if (axis != null) {
				cells.add("**" + run.axisText(axis) + "**");
			}
			if (!AXIS_TRANSPORT.equals(axis)) {
				cells.add(run.axes().get(AXIS_TRANSPORT));
			}
			cells.add("`" + str(MarkdownReport.path(json, "profile", "id")) + "`");
			cells.add(num(throughput, 1));
			cells.add(first == null || throughput == null || first == 0 ? "—" : String.format(Locale.ROOT, "%.2f×", throughput / first));
			for (String q : List.of("p50_ms", "p99_ms", "p999_ms", "max_ms")) {
				cells.add(num(official.get(q), 3));
			}
			if (!closed) {
				cells.add(num(MarkdownReport.path(round, "service_time_ms", "p99"), 3));
			}
			Object cpu = round.get("cpu");
			cells.add(cpu instanceof Map<?, ?> c ? CpuSampler.pct(c.get("client_busy_pct")) : str(cpu));
			cells.add(cpu instanceof Map<?, ?> c ? CpuSampler.pct(c.get("server_busy_pct")) : str(cpu));
			Object bound = MarkdownReport.path(json, "validity", "client_bound");
			cells.add(Boolean.TRUE.equals(bound) ? "**是**" : Boolean.FALSE.equals(bound) ? "否" : str(bound));
			cells.add(gc(round, "client") + " / " + gc(round, "server"));
			cells.add(round.get("io") == null ? "—" : IoSampler.cell(round, "client", "syscw") + " / " + IoSampler.cell(round, "server", "syscw"));
			cells.add(round.get("io") == null ? "—" : contextSwitches(round, "client") + " / " + contextSwitches(round, "server"));
			cells.add(str(official.get("window_errors")));
			cells.add(Boolean.TRUE.equals(MarkdownReport.path(json, "validity", "passed")) ? "通过" : "**未通过**");
			cells.add(connection(map(MarkdownReport.path(json, "run", "target"))));
			cells.add(str(MarkdownReport.path(json, "run", "harness_options", "io_threads")));
			cells.add(serverConfig(json.get("server_environment")));
			cells.add("`" + run.file() + "`");
			md.append("| ");
			for (int i = 0; i < cells.size(); i++) {
				md.append(cells.get(i).replace("|", "\\|").replace('\n', ' ')).append(i < cells.size() - 1 ? " | " : " |\n");
			}
		}
		md.append('\n');
		md.append("每行取该结果的正式轮（")
				.append(closed ? "闭环：窗口吞吐中位数那一轮" : "开环：窗口 p99 中位数那一轮")
				.append("）。CPU 是统计窗口内两组核的平均忙碌率；client_bound = 是 表示该结果的吞吐受发压端 CPU 限制，是 harness 的上限而不是服务端上限。")
				.append(closed ? "闭环的 p99 / p99.9 低估尾延迟，尾延迟以开环为准。" : "开环延迟从计划发出时刻算起（修正 coordinated omission）。")
				.append("\n\n");
	}

	/** 由一份结果 JSON 得到分组键、组标题与各轴的取值。 */
	static Run run(String file, Map<?, ?> json) {
		Map<?, ?> parsed = map(MarkdownReport.path(json, "profile", "parsed"));
		Map<?, ?> target = map(parsed.get("target"));
		Map<?, ?> load = map(parsed.get("load"));
		Map<?, ?> payload = map(target.get("payload"));
		Map<?, ?> isolation = map(parsed.get("isolation"));
		boolean closed = Profile.MODEL_CLOSED.equals(load.get("model"));
		Map<String, String> axes = new LinkedHashMap<>();
		String transport = str(target.get("transport"));
		Object serializer = target.get("serializer");
		axes.put(AXIS_TRANSPORT, serializer == null ? transport : transport + "（" + serializer + "）");
		// 连接数轴：实际生效的连接数（run.target.connections_per_node：rpc 每节点连接、gRPC channel 数、Dubbo connections）；
		// 卡片是否显式写了 target.connections 决定它属于"开箱即用"还是"连接数对齐"
		axes.put(AXIS_CONNECTIONS, str(MarkdownReport.path(json, "run", "target", "connections_per_node")));
		boolean explicitConnections = target.get("connections") != null;
		axes.put(AXIS_CONCURRENCY, closed ? str(load.get("concurrency")) : "—");
		axes.put(AXIS_RATE, closed ? "—" : str(load.get("rate")));
		// 只有命令行显式给了 --io-threads 才算对比轴的取值；各 transport 的缺省（写法各不相同）一律记 default，不把缺省差异当成对比轴
		Object ioSource = MarkdownReport.path(json, "run", "harness_options", "io_source");
		axes.put(AXIS_IO_THREADS, "command line".equals(ioSource) ? str(MarkdownReport.path(json, "run", "harness_options", "io_threads")) : "default");
		Object serverEnvironment = json.get("server_environment");
		Map<?, ?> serverEnv = serverEnvironment instanceof Map<?, ?> m ? m : Map.of();
		axes.put(AXIS_SERVER_EXECUTOR, str(serverEnv.get("server_executor_kind")));
		Object clientFlush = MarkdownReport.path(json, "run", "target", "flush_consolidation");
		Object serverFlush = serverEnv.get("server_flush_consolidation");
		axes.put(AXIS_FLUSH, clientFlush == null && serverFlush == null ? "—" : "client " + str(clientFlush) + " / server " + str(serverFlush));
		String payloadText = "bytes".equals(payload.get("kind")) ? str(payload.get("size")) + " B" : str(payload.get("kind"));
		List<Object> key = List.of(str(load.get("model")), str(target.get("service")), str(payload.get("kind")), str(payload.get("size")),
				str(load.get("duration_ms")), str(load.get("warmup_ms")), str(load.get("steady_window_ms")), str(load.get("rounds")),
				str(load.get("timeout_ms")), str(isolation.get("same_host")));
		String title = (closed ? "闭环" : "开环") + " · " + payloadText + " · `" + str(target.get("service")) + "` · 每轮 "
				+ str(load.get("duration")) + "（预热 " + str(load.get("warmup")) + "，窗口 [" + String.join(", ", strings(load.get("steady_window")))
				+ "]）× " + str(load.get("rounds")) + " 轮 · 超时 " + str(load.get("timeout"));
		return new Run(file, json, axes, String.valueOf(key), title, closed, explicitConnections);
	}

	private static Comparator<Run> rowOrder() {
		return Comparator.comparing((Run r) -> transportRank(r.axes().get(AXIS_TRANSPORT)))
				.thenComparing(r -> r.axes().get(AXIS_TRANSPORT))
				.thenComparingDouble(r -> numeric(r.axes().get(AXIS_CONNECTIONS)))
				.thenComparingDouble(r -> numeric(r.axes().get(AXIS_CONCURRENCY)))
				.thenComparingDouble(r -> numeric(r.axes().get(AXIS_RATE)))
				.thenComparingDouble(r -> numeric(r.axes().get(AXIS_IO_THREADS)))
				.thenComparing(r -> r.axes().get(AXIS_IO_THREADS))
				.thenComparing(r -> r.axes().get(AXIS_SERVER_EXECUTOR))
				.thenComparing(r -> r.axes().get(AXIS_FLUSH), Comparator.reverseOrder())
				.thenComparing(Run::startedAt);
	}

	private static int transportRank(String transport) {
		String name = transport.contains("（") ? transport.substring(0, transport.indexOf('（')) : transport;
		int i = TRANSPORT_ORDER.indexOf(name);
		return i < 0 ? TRANSPORT_ORDER.size() : i;
	}

	private static double numeric(String value) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			return Double.MAX_VALUE; // "default (...)" 之类排在数字之后
		}
	}

	private static String axisTitle(String axis) {
		return switch (axis) {
			case AXIS_TRANSPORT -> "transport（序列化）";
			case AXIS_CONCURRENCY -> "并发";
			case AXIS_RATE -> "速率 (req/s)";
			case AXIS_IO_THREADS -> "客户端 IO 线程（对比轴）";
			case AXIS_CONNECTIONS -> "连接数";
			case AXIS_SERVER_EXECUTOR -> "服务端执行器";
			case AXIS_FLUSH -> "flush 合并";
			default -> axis;
		};
	}

	/** 连接方式：对比对象与 rpc 卡写 {@code connection_model}；旧结果只有每节点连接数；http 卡是连接池大小。 */
	private static String connection(Map<?, ?> target) {
		if (target.get("connection_model") != null) {
			return str(target.get("connection_model"));
		}
		if (target.get("connections_per_node") != null) {
			return "每节点 " + str(target.get("connections_per_node")) + " 条连接";
		}
		if (target.get("connections") != null) {
			return str(target.get("connections")) + " 条 keep-alive 连接";
		}
		return "—";
	}

	/** 服务端的 IO 线程与业务执行方式（服务端环境里的 {@code server_io_threads} / {@code server_executor}）；旧结果没有时为 —。 */
	private static String serverConfig(Object serverEnvironment) {
		if (!(serverEnvironment instanceof Map<?, ?> env)) {
			return str(serverEnvironment);
		}
		Set<String> parts = new LinkedHashSet<>();
		if (env.get("server_io_threads") != null) {
			parts.add("IO 线程 " + env.get("server_io_threads"));
		}
		if (env.get("server_executor") != null) {
			parts.add("执行器 " + env.get("server_executor"));
		}
		return parts.isEmpty() ? "—" : String.join("；", parts);
	}

	/** 一端每请求的上下文切换（自愿 + 非自愿）；取不到时同 {@link IoSampler#cell}。 */
	private static String contextSwitches(Map<?, ?> round, String side) {
		Double vol = IoSampler.perRequestValue(round, side, "vol_cs");
		Double nonvol = IoSampler.perRequestValue(round, side, "nonvol_cs");
		return vol == null || nonvol == null ? IoSampler.cell(round, side, "vol_cs") : String.format(Locale.ROOT, "%.2f", vol + nonvol);
	}

	private static String gc(Map<?, ?> round, String side) {
		Double pause = GcSampler.pauseMs(round, side);
		if (pause != null) {
			return num(pause, 0);
		}
		Object gc = round.get("gc");
		Object value = gc instanceof Map<?, ?> m ? m.get(side) : gc;
		return value == null ? "—" : String.valueOf(value);
	}

	private static Map<?, ?> map(Object value) {
		return value instanceof Map<?, ?> m ? m : Map.of();
	}

	private static List<Object> list(Object value) {
		return value instanceof List<?> l ? new ArrayList<>(l) : List.of();
	}

	private static List<String> strings(Object value) {
		List<String> out = new ArrayList<>();
		for (Object o : list(value)) {
			out.add(String.valueOf(o));
		}
		return out;
	}

	private static String str(Object value) {
		return value == null ? "—" : String.valueOf(value);
	}

	private static String num(Object value, int decimals) {
		return value instanceof Number n ? String.format(Locale.ROOT, "%." + decimals + "f", n.doubleValue()) : "—";
	}
}
