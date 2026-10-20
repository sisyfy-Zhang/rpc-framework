package com.feynman.rpc.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON（已读回成 Map）→ Markdown。<b>只读 JSON</b>：报告里的每个数字都能在同名 JSON 里找到出处，
 * 不存在"Markdown 里有、JSON 里没有"的数。表格用 GitHub 风格，可以直接嵌进 README。
 */
final class MarkdownReport {

	private MarkdownReport() {
	}

	/** 单次运行的完整报告（{@code run} 子命令写出的 .md）。 */
	static String render(Map<?, ?> json, String jsonFileName) {
		StringBuilder md = new StringBuilder();
		section(md, json, jsonFileName, 1, null);
		return md.toString();
	}

	/**
	 * 汇总报告（{@code report} 子命令）：每张卡一节；同一张卡有多次运行时，节下每次运行一小节。
	 *
	 * @param runsByProfile 卡片 id → 该卡的运行（JSON 与文件名），已按开始时间排序
	 */
	static String renderSummary(Map<String, List<Map.Entry<String, Map<?, ?>>>> runsByProfile) {
		return renderSummary(runsByProfile, List.of());
	}

	/**
	 * @param crossval 交叉验证结果（{@code crossval} 子命令写出的 JSON，按创建时间排序）：在汇总末尾加"交叉验证判定"一节——
	 *        每份结果的 PASS/FAIL 与逐项判定，作为同一 sha 下 harness 可信度的证据
	 */
	static String renderSummary(Map<String, List<Map.Entry<String, Map<?, ?>>>> runsByProfile,
			List<Map.Entry<String, Map<?, ?>>> crossval) {
		return renderSummary(runsByProfile, crossval, "");
	}

	/**
	 * @param compare {@link CompareReport#render} 的结果（同一口径、不同 transport / 并发的结果并排）；非空时放在汇总表之后的"对比表"一节
	 */
	static String renderSummary(Map<String, List<Map.Entry<String, Map<?, ?>>>> runsByProfile,
			List<Map.Entry<String, Map<?, ?>>> crossval, String compare) {
		StringBuilder md = new StringBuilder();
		md.append("# 压测报告汇总\n\n");
		md.append("由 `rpc-bench-e2e report` 从 JSON 结果文件生成；每个数字都可在对应 JSON 中找到。\n\n");
		md.append("| 口径卡 | 运行次数 | 最近一次 | 判定 |\n|---|---|---|---|\n");
		runsByProfile.forEach((profile, runs) -> {
			Map<?, ?> last = runs.get(runs.size() - 1).getValue();
			md.append("| [").append(profile).append("](#").append(anchor(profile)).append(") | ").append(runs.size())
					.append(" | ").append(str(path(last, "environment", "started_at"))).append(" | ")
					.append(verdict(last)).append(" |\n");
		});
		md.append('\n');
		if (compare != null && !compare.isEmpty()) {
			md.append("## 对比表\n\n");
			md.append("同一负载模型、负载大小与时长口径，只在 transport / 并发 / 速率 / 客户端 IO 线程数上不同的结果并排（`compare` 子命令同一份表）。\n\n");
			md.append(compare);
		}
		runsByProfile.forEach((profile, runs) -> {
			if (runs.size() == 1) {
				section(md, runs.get(0).getValue(), runs.get(0).getKey(), 2, null);
			} else {
				md.append("## ").append(profile).append("\n\n");
				for (Map.Entry<String, Map<?, ?>> run : runs) {
					section(md, run.getValue(), run.getKey(), 3, run.getKey());
				}
			}
		});
		if (!crossval.isEmpty()) {
			crossvalSection(md, crossval);
		}
		return md.toString();
	}

	private static void crossvalSection(StringBuilder md, List<Map.Entry<String, Map<?, ?>>> results) {
		md.append("## 交叉验证判定\n\n");
		md.append("harness 与 wrk / wrk2 打同一个 HTTP 目标的比较结果（`crossval` 子命令），口径见 docs/benchmarking.md「交叉验证」。\n\n");
		md.append("| 结果文件 | 模式 | 工具 | harness 结果 | 判定 |\n|---|---|---|---|---|\n");
		for (Map.Entry<String, Map<?, ?>> e : results) {
			Map<?, ?> json = e.getValue();
			md.append("| [").append(e.getKey()).append("](#").append(anchor(e.getKey())).append(") | ")
					.append(str(json.get("mode"))).append(" (").append(str(json.get("kind"))).append(") | ")
					.append(str(path(json, "tool", "name"))).append(" | ").append(str(path(json, "harness", "file"))).append(" | **")
					.append(str(json.get("verdict"))).append("** |\n");
		}
		md.append('\n');
		for (Map.Entry<String, Map<?, ?>> e : results) {
			Map<?, ?> json = e.getValue();
			md.append("### ").append(e.getKey()).append("\n\n");
			md.append("> 判定：**").append(str(json.get("verdict"))).append("** · 创建于 ").append(str(json.get("created_at")))
					.append(" · harness：`").append(str(path(json, "harness", "file"))).append("`（git ")
					.append(shortSha(path(json, "harness", "git_sha"))).append(dirty(path(json, "harness", "git_dirty")))
					.append("）· 工具命令：").append(code(blankAsDash(path(json, "tool", "command")))).append("\n\n");
			if (json.get("question") != null) {
				md.append("**问题**：").append(str(json.get("question"))).append("\n\n");
			}
			md.append("| 组 | 判据 | 实测 | 相对偏差 | 结果 |\n|---|---|---|---|---|\n");
			for (Object item : list(json.get("checks"))) {
				Map<?, ?> check = map(item);
				md.append("| ").append(cell(check.get("group"))).append(" | ").append(cell(check.get("criterion"))).append(" | ")
						.append(cell(check.get("measured"))).append(" | ")
						.append(check.get("rel_dev_pct") instanceof Number ? num(check.get("rel_dev_pct"), 1) + "%" : "—").append(" | ")
						.append(Boolean.TRUE.equals(check.get("passed")) ? "通过" : Boolean.FALSE.equals(check.get("passed")) ? "**未通过**" : "—")
						.append(" |\n");
			}
			md.append('\n');
		}
	}

	/** 表格单元格：竖线转义、换行压成空格。 */
	private static String cell(Object value) {
		return str(value).replace("|", "\\|").replace('\n', ' ');
	}

	private static void section(StringBuilder md, Map<?, ?> json, String fileName, int level, String titleOverride) {
		String h = "#".repeat(level);
		String sub = "#".repeat(level + 1);
		Map<?, ?> profile = map(path(json, "profile", "parsed"));
		Map<?, ?> env = map(json.get("environment"));
		Map<?, ?> load = map(profile.get("load"));
		Map<?, ?> target = map(profile.get("target"));
		Map<?, ?> isolation = map(profile.get("isolation"));
		Map<?, ?> measure = map(profile.get("measure"));
		Map<?, ?> run = map(json.get("run"));
		Map<?, ?> validity = map(json.get("validity"));
		Map<?, ?> summary = map(json.get("summary"));
		boolean closed = "closed".equals(load.get("model"));

		md.append(h).append(' ').append(titleOverride != null ? titleOverride : str(profile.get("profile"))).append("\n\n");
		List<String> meta = new ArrayList<>();
		if (json.get("label") != null) {
			meta.add("标签：`" + json.get("label") + "`");
		}
		meta.add("结果文件：`" + fileName + "`");
		meta.add("判定：" + verdict(json));
		md.append("> ").append(String.join(" · ", meta)).append("\n\n");
		md.append("**问题**：").append(str(profile.get("question"))).append("\n\n");

		md.append(sub).append(" 环境\n\n| 项 | 值 |\n|---|---|\n");
		row(md, "操作系统", str(env.get("os_name")) + " " + str(env.get("os_version")) + " (" + str(env.get("os_arch")) + ")");
		row(md, "WSL", yesNo(env.get("wsl")));
		row(md, "CPU", str(env.get("cpu_model")));
		Object serverEnvRaw = json.get("server_environment");
		Map<?, ?> serverEnv = map(serverEnvRaw);
		String serverMissing = str(serverEnvRaw);
		row(md, "可用核数 / 主机核数", cores(env) + (serverEnv.isEmpty() ? "" : "（服务端 " + cores(serverEnv) + "）"));
		row(md, "内存", memory(env.get("total_memory_bytes")));
		row(md, "JDK", str(env.get("jdk_version")) + " · " + str(env.get("jdk_vendor")) + " · " + str(env.get("jvm_name")));
		row(md, "JVM 参数（压测端）", code(joined(env.get("jvm_args"))));
		row(md, "服务端 JVM 参数", serverEnv.isEmpty() ? serverMissing : code(blankAsDash(serverEnv.get("jvm_args"))));
		row(md, "CPU 亲和（压测端）", str(env.get("cpus_allowed")));
		row(md, "服务端 CPU 亲和", serverEnv.isEmpty() ? serverMissing : str(serverEnv.get("cpus_allowed")));
		row(md, "指标", "压测端 metrics_enabled=" + str(env.get("metrics_enabled")) + " · 服务端 metrics_enabled="
				+ (serverEnv.isEmpty() ? serverMissing : str(serverEnv.get("metrics_enabled"))));
		row(md, "GC", joined(env.get("gc")));
		row(md, "Netty", str(env.get("netty_version")));
		row(md, "git", shortSha(env.get("git_sha")) + dirty(env.get("git_dirty")));
		row(md, "主机名", str(env.get("hostname")));
		row(md, "电源 / 调频", str(env.get("power_supply")) + " / " + str(env.get("cpu_governor")));
		row(md, "时间", str(env.get("started_at")) + " → " + str(env.get("ended_at")));
		row(md, "harness", "rpc-bench-e2e " + str(env.get("harness_version")));
		md.append('\n');

		md.append(sub).append(" 口径\n\n| 项 | 值 |\n|---|---|\n");
		Map<?, ?> payload = map(target.get("payload"));
		String payloadText = "bytes".equals(payload.get("kind")) ? "bytes，" + str(payload.get("size")) + " B（固定种子随机内容）"
				: "order（8 字段 POJO，含 List 与 java.time）";
		row(md, "目标", str(target.get("transport")) + " · `" + str(target.get("service")) + "` · " + payloadText);
		Map<?, ?> runTarget = map(run.get("target"));
		if ("http".equals(target.get("transport"))) {
			row(md, "序列化 / 提供者", "—（HTTP 请求体就是原始字节）/ 1 个 URL");
			row(md, "连接", str(runTarget.get("address")) + "，" + str(runTarget.get("connections")) + " 条 keep-alive 连接、每条同一时刻 1 个在途请求（"
					+ str(runTarget.get("connection_model")) + "），重试 0");
		} else if (!"rpc".equals(target.get("transport"))) {
			// 对比对象（gRPC / Dubbo 等）：连接方式各不相同，按它自己描述的 connection_model 写
			row(md, "序列化 / 提供者", str(target.get("serializer")) + " / " + str(target.get("providers")) + " 个");
			row(md, "连接", str(runTarget.get("address")) + "，提供者 " + joined(runTarget.get("providers")) + "，"
					+ str(runTarget.get("connection_model")) + "，重试 " + str(runTarget.get("retries")));
		} else {
			row(md, "序列化 / 提供者", str(target.get("serializer")) + " / " + str(target.get("providers")) + " 个");
			row(md, "连接", str(runTarget.get("address")) + "，提供者 " + joined(runTarget.get("providers")) + "，每节点连接 "
					+ str(runTarget.get("connections_per_node")) + "，负载均衡 " + str(runTarget.get("load_balance")) + "，重试 0");
		}
		if (runTarget.get("connections_meaning") != null) {
			// target.connections 的含义随 transport 不同；写明卡片的值（缺省 = 各框架缺省）与实际生效的连接数
			row(md, "连接数（target.connections）", (target.get("connections") == null ? "缺省" : str(target.get("connections")))
					+ " → 实际 " + str(runTarget.get("connections_per_node")) + "（" + str(runTarget.get("connections_meaning")) + "）");
		}
		row(md, "负载模型", closed ? "闭环（closed），并发 " + str(load.get("concurrency")) + " 个虚拟用户"
				: "开环（open），" + str(load.get("rate")) + " req/s 等间隔发出，在途上限 " + str(load.get("inflight_cap")));
		row(md, "时长", str(load.get("duration")) + " / 轮，预热 " + str(load.get("warmup")) + "，统计窗口 "
				+ joinedBrackets(load.get("steady_window")) + "，共 " + str(load.get("rounds")) + " 轮");
		row(md, "超时", str(load.get("timeout")));
		row(md, "隔离", "client " + str(isolation.get("client_process")) + " · server " + str(isolation.get("server_process"))
				+ " · 同机 " + yesNo(isolation.get("same_host")) + " · 注册中心 " + str(isolation.get("registry")));
		Map<?, ?> histogram = map(measure.get("histogram"));
		row(md, "直方图", "HdrHistogram，" + str(histogram.get("significant_digits")) + " 位有效数字，上限 " + str(histogram.get("max"))
				+ "；只统计完成时刻落在窗口内的成功调用");
		row(md, "延迟起点", closed ? "调用发出前（闭环）" : "计划发出时刻（开环，修正 coordinated omission）");
		Map<?, ?> harness = map(run.get("harness_options"));
		if (!harness.isEmpty()) {
			row(md, "harness 选项", harnessOptions(harness, closed, list(json.get("rounds")), "http".equals(target.get("transport"))));
			row(md, "预热段", preWarmup(run.get("pre_warmup"), harness));
		}
		md.append('\n');

		List<String> report = strings(measure.get("report"));
		md.append(sub).append(" 轮次\n\n");
		List<String> header = new ArrayList<>(List.of("轮", "窗口内请求"));
		for (String item : report) {
			header.add(columnTitle(item));
		}
		if (!closed) {
			header.add("服务时间 p99 (ms)");
			header.add("发出滞后 p99 (ms)");
		}
		header.add("客户端核 CPU");
		header.add("服务端核 CPU");
		header.add("GC 停顿 客户端/服务端 (ms)");
		header.add("write 系统调用/请求 客户端/服务端");
		header.add("有效");
		md.append("| ").append(String.join(" | ", header)).append(" |\n|");
		md.append("---|".repeat(header.size())).append('\n');
		Object official = summary.get("official_round");
		for (Object r : list(json.get("rounds"))) {
			Map<?, ?> round = map(r);
			boolean bold = official != null && official.equals(round.get("round"));
			List<String> cells = new ArrayList<>();
			cells.add(str(round.get("round")) + (bold ? "（正式）" : ""));
			cells.add(str(path(round, "window", "requests")));
			Map<?, ?> latency = map(round.get("latency_ms"));
			for (String item : report) {
				cells.add(switch (item) {
					case "throughput" -> num(round.get("throughput_rps"), 1);
					case "errors_by_status" -> errors(round);
					default -> num(latency.get(item), 3);
				});
			}
			if (!closed) {
				cells.add(num(path(round, "service_time_ms", "p99"), 3));
				cells.add(num(path(round, "dispatch_lag_ms", "p99"), 3));
			}
			Map<?, ?> cpu = map(round.get("cpu"));
			cells.add(cpu.isEmpty() ? cpuMissing(round.get("cpu")) : CpuSampler.pct(cpu.get("client_busy_pct")));
			cells.add(cpu.isEmpty() ? cpuMissing(round.get("cpu")) : CpuSampler.pct(cpu.get("server_busy_pct")));
			cells.add(round.get("gc") == null ? "—" : gcPause(round, "client") + " / " + gcPause(round, "server"));
			cells.add(round.get("io") == null ? "—" : IoSampler.cell(round, "client", "syscw") + " / " + IoSampler.cell(round, "server", "syscw"));
			cells.add(Boolean.TRUE.equals(round.get("valid")) ? "是" : "否：" + String.join("；", strings(round.get("invalid_reasons"))));
			md.append("| ");
			for (int i = 0; i < cells.size(); i++) {
				String cell = cells.get(i);
				md.append(bold && !cell.isEmpty() ? "**" + cell + "**" : cell).append(i < cells.size() - 1 ? " | " : " |\n");
			}
		}
		md.append('\n');
		md.append("正式轮：第 ").append(str(official)).append(" 轮（").append(str(summary.get("selection_rule"))).append("）。");
		Map<?, ?> spread = map(summary.get("spread"));
		List<String> spreads = new ArrayList<>();
		for (String metric : List.of("throughput_rps", "p50_ms", "p99_ms", "p999_ms")) {
			Map<?, ?> s = map(spread.get(metric));
			if (!s.isEmpty() && s.get("range") != null) {
				spreads.add(metric + " " + num(s.get("range"), 3) + "（" + num(s.get("range_pct"), 1) + "%）");
			}
		}
		if (!spreads.isEmpty()) {
			md.append("各轮极差：").append(String.join("，", spreads)).append("。");
		}
		md.append("\n\n");
		for (Object r : list(json.get("rounds"))) {
			Map<?, ?> round = map(r);
			if (official != null && official.equals(round.get("round")) && IoSampler.hasGroups(round)) {
				md.append("上下文切换按线程组（正式轮，统计窗口内；按线程名前缀归组，口径见 docs/benchmarking.md）：\n\n");
				IoSampler.groupTable(md, round);
				md.append('\n');
			}
		}

		md.append(sub).append(" 断言\n\n");
		List<Object> asserts = list(validity.get("asserts"));
		if (asserts.isEmpty()) {
			md.append("本卡未声明断言。\n\n");
		} else {
			md.append("| 断言 | 阈值 | 实测 | 口径 | 结果 |\n|---|---|---|---|---|\n");
			for (Object a : asserts) {
				Map<?, ?> assertion = map(a);
				md.append("| `").append(str(assertion.get("key"))).append("` | ").append(str(assertion.get("threshold")))
						.append(" | ").append(str(assertion.get("actual"))).append(" | ").append(str(assertion.get("scope")))
						.append(" | ").append(Boolean.TRUE.equals(assertion.get("passed")) ? "通过" : "**未通过**").append(" |\n");
			}
			md.append('\n');
		}

		md.append(sub).append(" 声明\n\n");
		md.append("- 数字来自固定的一台本机（").append(str(env.get("hostname"))).append("，").append(str(env.get("cpu_model")))
				.append("），只说明这台机器上这个口径下的量级，不可外推到其它机器、其它网络或生产环境。\n");
		md.append("- 拓扑：").append(str(validity.get("topology"))).append('\n');
		for (String statement : strings(validity.get("statements"))) {
			md.append("- ").append(statement).append('\n');
		}
		md.append('\n');
	}

	/**
	 * "可用 / 主机"核数。可用核数 = availableProcessors（受 taskset 影响），主机核数 = 整机逻辑核；
	 * 改名前的结果文件只有 {@code logical_cpus}（即可用核数），主机核数记为 —。
	 */
	private static String cores(Map<?, ?> env) {
		Object available = env.containsKey("available_cpus") ? env.get("available_cpus") : env.get("logical_cpus");
		return str(available) + " / " + str(env.get("host_cpus"));
	}

	/**
	 * 统计窗口内一端的 GC 停顿（ms，整数——MXBean 是毫秒粒度）；取不到记 unknown。没有 {@code gc} 字段的旧结果文件
	 * 整格记 —（由调用方处理）。
	 */
	private static String gcPause(Map<?, ?> round, String side) {
		Object gc = round.get("gc");
		Double pause = GcSampler.pauseMs(round, side);
		if (pause != null) {
			return num(pause, 0);
		}
		Object value = gc instanceof Map<?, ?> m ? m.get(side) : gc;
		return value == null ? "—" : String.valueOf(value);
	}

	/** 口径表"harness 选项"一行：开环执行者、HTTP IO 线程数、调度线程绑核（含第 1 轮读回的实际生效情况）。 */
	private static String harnessOptions(Map<?, ?> harness, boolean closed, List<Object> rounds, boolean http) {
		List<String> parts = new ArrayList<>();
		if (!closed) {
			parts.add("开环执行者 " + str(harness.get("open_workers"))
					+ (harness.get("open_pool_size") == null ? "" : "（" + str(harness.get("open_pool_size")) + (HarnessOptions.WORKERS_POOL_PLATFORM.equals(harness.get("open_workers")) ? " 个平台线程" : " 个虚拟线程") + "工作者，有界 FIFO 队列）"));
		}
		Object client = harness.get("http_client");
		if (client != null && !String.valueOf(client).startsWith("n/a")) {
			parts.add("HTTP 客户端 " + str(client));
		}
		Object io = harness.get("io_threads");
		if (io != null && !String.valueOf(io).startsWith("n/a")) {
			Object poll = harness.get("io_busy_poll");
			parts.add((http ? "HTTP IO 线程 " : "客户端 IO 线程 ") + str(io) + (Boolean.TRUE.equals(poll) ? "（忙轮询）" : ""));
		} else if (io != null && client == null) {
			parts.add("HTTP IO 线程 " + str(io)); // 早于 http_client 字段的结果文件
		}
		String pin = "调度线程绑核 " + str(harness.get("pin_dispatcher"));
		if (!closed && !rounds.isEmpty() && path(map(rounds.get(0)), "scheduler", "pin") instanceof Map<?, ?> p) {
			pin += "（第 1 轮读回：" + str(p.get("status")) + (p.get("others_cpus") == null ? "" : "，其余线程 " + str(p.get("others_cpus")))
					+ (p.get("reason") == null ? "" : "；" + str(p.get("reason"))) + "）";
		}
		parts.add(pin);
		return String.join(" · ", parts);
	}

	/** 口径表"预热段"一行：第 1 轮之前、以同样负载跑的一段（不计入任何轮）的时长与摘要。 */
	private static String preWarmup(Object preWarmup, Map<?, ?> harness) {
		if (!(preWarmup instanceof Map<?, ?> p)) {
			return "关闭（" + str(harness.get("pre_warmup_source")) + " = " + str(harness.get("pre_warmup")) + "）";
		}
		Object maxInflight = path(p, "scheduler", "max_inflight");
		return str(harness.get("pre_warmup")) + "（" + str(harness.get("pre_warmup_source")) + "），第 1 轮之前以同样负载跑、不计入任何轮："
				+ "发出 " + str(p.get("issued")) + "、错误 " + str(p.get("errors")) + "、延迟 p99 " + num(path(p, "latency_ms", "p99"), 3) + "ms"
				+ (maxInflight == null ? "" : "、最大在途 " + str(maxInflight));
	}

	/** 没有 {@code cpu} 字段（旧结果文件）记 —，否则原样（n/a / unknown）。 */
	private static String cpuMissing(Object cpu) {
		return cpu == null ? "—" : String.valueOf(cpu);
	}

	private static String verdict(Map<?, ?> json) {
		return Boolean.TRUE.equals(path(json, "validity", "passed")) ? "**通过**" : "**未通过**";
	}

	private static String columnTitle(String item) {
		return switch (item) {
			case "throughput" -> "吞吐 (req/s)";
			case "errors_by_status" -> "错误（窗口内）";
			case "p999" -> "p99.9 (ms)";
			default -> item + " (ms)";
		};
	}

	private static String errors(Map<?, ?> round) {
		Map<?, ?> byType = map(round.get("errors_by_type"));
		Object total = path(round, "window", "errors");
		if (byType.isEmpty()) {
			return str(total);
		}
		List<String> parts = new ArrayList<>();
		byType.forEach((k, v) -> parts.add(k + "×" + v));
		return str(total) + "（" + String.join("，", parts) + "）";
	}

	private static void row(StringBuilder md, String key, String value) {
		md.append("| ").append(key).append(" | ").append(value.replace("|", "\\|")).append(" |\n");
	}

	static Object path(Map<?, ?> map, String... keys) {
		Object current = map;
		for (String key : keys) {
			if (!(current instanceof Map<?, ?> m)) {
				return null;
			}
			current = m.get(key);
		}
		return current;
	}

	private static Map<?, ?> map(Object value) {
		return value instanceof Map<?, ?> m ? m : Map.of();
	}

	private static List<Object> list(Object value) {
		return value instanceof List<?> l ? new ArrayList<>(l) : List.of();
	}

	private static List<String> strings(Object value) {
		List<String> result = new ArrayList<>();
		for (Object item : list(value)) {
			result.add(String.valueOf(item));
		}
		return result;
	}

	private static String str(Object value) {
		if (value == null) {
			return "—";
		}
		if (value instanceof Map<?, ?> m) {
			List<String> parts = new ArrayList<>();
			m.forEach((k, v) -> parts.add(k + "=" + v));
			return String.join(", ", parts);
		}
		return String.valueOf(value);
	}

	private static String joined(Object value) {
		List<String> items = strings(value);
		return items.isEmpty() ? "—" : String.join(" ", items);
	}

	private static String blankAsDash(Object value) {
		return value == null || String.valueOf(value).isBlank() ? "—" : String.valueOf(value);
	}

	private static String joinedBrackets(Object value) {
		return "[" + String.join(", ", strings(value)) + "]";
	}

	private static String code(String text) {
		return "—".equals(text) ? text : "`" + text.replace("`", "'") + "`";
	}

	private static String num(Object value, int decimals) {
		if (!(value instanceof Number n)) {
			return "—";
		}
		return String.format(Locale.ROOT, "%." + decimals + "f", n.doubleValue());
	}

	private static String yesNo(Object value) {
		if (Boolean.TRUE.equals(value)) {
			return "是";
		}
		if (Boolean.FALSE.equals(value)) {
			return "否";
		}
		return str(value);
	}

	private static String memory(Object bytes) {
		if (!(bytes instanceof Number n)) {
			return str(bytes);
		}
		return String.format(Locale.ROOT, "%.1f GiB", n.doubleValue() / (1L << 30));
	}

	private static String shortSha(Object sha) {
		String s = str(sha);
		return s.length() >= 7 && !"unknown".equals(s) ? s.substring(0, 7) : s;
	}

	private static String dirty(Object dirty) {
		if (Boolean.TRUE.equals(dirty)) {
			return "（工作区有未提交改动）";
		}
		if (Boolean.FALSE.equals(dirty)) {
			return "（干净）";
		}
		return "（dirty: " + str(dirty) + "）";
	}

	/** GitHub 风格的标题锚点（小写、空格变连字符、去掉标点；字母数字与 - _ 保留）。 */
	static String anchor(String heading) {
		StringBuilder sb = new StringBuilder();
		for (char c : heading.toLowerCase(Locale.ROOT).toCharArray()) {
			if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
				sb.append(c);
			} else if (c == ' ') {
				sb.append('-');
			}
		}
		return sb.toString();
	}
}
