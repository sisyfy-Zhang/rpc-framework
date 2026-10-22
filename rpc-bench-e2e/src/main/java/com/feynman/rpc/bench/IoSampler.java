package com.feynman.rpc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每轮统计窗口内本进程的读写系统调用次数与上下文切换次数（Linux；其它平台记 {@value EnvironmentProbe#NA}）。
 * 窗口起止各取一次快照，差值进 {@code rounds[].io.client}；服务端由 {@code BenchService.stats()} / {@code GET /stats}
 * 返回同一份快照（值压平成字符串，{@link ServerProbes#stats()}），差值进 {@code rounds[].io.server}。再按窗口内完成的请求数
 * 派生"每请求"值——{@code syscw_per_request} 回答"每个请求花了几次 write 系统调用"，是 flush 合并类改动的直接证据。
 *
 * <ul>
 *   <li>{@code syscr} / {@code syscw}：{@code /proc/self/io}，内核按线程组累计（含已退出的线程）的 read / write 类系统调用次数
 *       （read、readv、pread… / write、writev、pwrite…；套接字的收发与 eventfd 唤醒——Netty 的 {@code selector.wakeup()}——都算在内，
 *       {@code epoll_wait}、{@code futex} 不算）。需要内核开启 task IO accounting；读不到时整项记 n/a。</li>
 *   <li>{@code vol_cs} / {@code nonvol_cs}：{@code /proc/self/task/<tid>/status} 的 {@code voluntary_ctxt_switches} /
 *       {@code nonvoluntary_ctxt_switches} 对<b>当前存活的线程</b>求和。不用 {@code /proc/self/status}：它的这两项只是主线程的值
 *       （Java 的主线程在压测中几乎一直睡着）。代价：窗口内退出的线程，其计数在结束快照里消失（窗口内新建的线程从 0 计起，不影响）；
 *       压测中的线程（事件循环、载体线程、工作者）都常驻，这个误差可以忽略，{@code threads} 记录两次快照的线程数以便核对。</li>
 *   <li>{@code by_group}：同一次遍历里按线程名（{@code /proc/self/task/<tid>/comm}，内核截断到 15 字节）的前缀把线程归组（{@link #group}），
 *       每组记存活线程数与 vol / nonvol 切换的差值，每请求值同上。各组之和就是上面的 {@code vol_cs} / {@code nonvol_cs}（同一次遍历、
 *       同一批线程）。组：{@value #GROUP_NETTY_IO}（Netty 事件循环）、{@value #GROUP_BIZ}（服务端业务池）、{@value #GROUP_FJP}
 *       （ForkJoinPool：虚拟线程载体与 commonPool）、{@value #GROUP_TIMERS}（JDK 的 CompletableFuture 延迟调度线程、虚拟线程 unparker）、
 *       {@value #GROUP_HARNESS}（压测端的调度 / 开环工作者线程）、{@value #GROUP_JVM}（GC / JIT / VM 内部线程）、{@value #GROUP_OTHER}
 *       （其余，包括名为 {@code java} 的主线程）。</li>
 *   <li>{@code by_group.<组>.cpu_ms}：同一次遍历里再读 {@code /proc/self/task/<tid>/stat} 的 {@code utime + stime}（第 14、15 项，
 *       单位是时钟滴答 {@code USER_HZ}；Linux 对用户态的 ABI 固定为 100，即 10ms 一个滴答，{@link #MS_PER_TICK}），组内求和后取窗口差值，
 *       每请求值 {@code cpu_us_per_request} = 差值 / 窗口内完成的请求数（微秒）。该端各组之和另记为该端的 {@code cpu_ms} /
 *       {@code cpu_us_per_request}。与切换计数同一个代价：窗口内退出的线程其 CPU 时间在结束快照里消失。任一线程的 {@code stat} 解析不了时
 *       整个快照不带 CPU（旧服务端同样没有），该端的 {@code by_group} 不出 CPU 字段。</li>
 * </ul>
 * 读文件的开销：每次快照读 1 + 2 × 线程数个小文件（百个量级），只在窗口起止各一次，在采样虚拟线程上，不在压测路径上。
 */
final class IoSampler {

	static final Path PROC_SELF_IO = Path.of("/proc/self/io");
	static final Path PROC_SELF_TASK = Path.of("/proc/self/task");
	static final String SOURCE = "/proc/self/io (syscr, syscw) + sum of /proc/self/task/*/status (voluntary / nonvoluntary_ctxt_switches), "
			+ "grouped by /proc/self/task/*/comm prefix (by_group; cpu_ms = utime + stime of /proc/self/task/*/stat, USER_HZ = 100), steady window";

	/** {@code /proc/<pid>/task/<tid>/stat} 的 utime / stime 单位是 {@code USER_HZ}，Linux 对用户态固定为 100（{@code getconf CLK_TCK}）。 */
	static final long MS_PER_TICK = 10;

	static final String GROUP_NETTY_IO = "netty_io";
	static final String GROUP_BIZ = "biz_pool";
	static final String GROUP_FJP = "fjp";
	static final String GROUP_TIMERS = "timers";
	static final String GROUP_HARNESS = "harness";
	static final String GROUP_JVM = "jvm";
	static final String GROUP_OTHER = "other";
	/** 报告里各组的顺序。 */
	static final List<String> GROUPS = List.of(GROUP_NETTY_IO, GROUP_BIZ, GROUP_FJP, GROUP_TIMERS, GROUP_HARNESS, GROUP_JVM, GROUP_OTHER);

	/**
	 * 线程名前缀 → 组，按顺序匹配第一个（{@code comm} 是线程名的前 15 字节）。名字取自实际运行的 jstack / comm：
	 * 本框架 {@code rpc-client-io-N-M}、{@code rpc-server-worker-N-M}（截成 {@code rpc-server-work}）、{@code rpc-server-boss-…}、
	 * {@code rpc-server-biz-N-M}；对比对象 gRPC（{@code grpc-client-io}、{@code grpc-server-worker}、{@code grpc-server-boss}、
	 * {@code grpc-server-biz-}、{@code grpc-default-executor}）、Dubbo（{@code NettyClientWorker}、{@code NettyServerWorker}、
	 * {@code NettyServerBoss}、{@code DubboServerHandler}）、HTTP 卡（{@code bench-http-*}）与 Netty 缺省名 {@code nioEventLoopGroup-…}。
	 */
	private static final List<Map.Entry<String, String>> PREFIXES = List.of(
			Map.entry("rpc-client-io", GROUP_NETTY_IO),
			Map.entry("rpc-server-work", GROUP_NETTY_IO),
			Map.entry("rpc-server-boss", GROUP_NETTY_IO),
			Map.entry("rpc-server-biz", GROUP_BIZ),
			Map.entry("grpc-client-io", GROUP_NETTY_IO),
			Map.entry("grpc-server-wor", GROUP_NETTY_IO),
			Map.entry("grpc-server-bos", GROUP_NETTY_IO),
			Map.entry("grpc-nio-", GROUP_NETTY_IO),
			Map.entry("grpc-server-biz", GROUP_BIZ),
			Map.entry("grpc-default-ex", GROUP_BIZ),
			Map.entry("NettyClientWork", GROUP_NETTY_IO),
			Map.entry("NettyServerWork", GROUP_NETTY_IO),
			Map.entry("NettyServerBoss", GROUP_NETTY_IO),
			Map.entry("DubboServerHand", GROUP_BIZ),
			Map.entry("bench-http-", GROUP_NETTY_IO),
			Map.entry("nioEventLoopGro", GROUP_NETTY_IO),
			Map.entry("ForkJoinPool", GROUP_FJP),
			Map.entry("CompletableFutu", GROUP_TIMERS),
			Map.entry("VirtualThread-u", GROUP_TIMERS),
			Map.entry("bench-", GROUP_HARNESS),
			Map.entry("GC Thread", GROUP_JVM),
			Map.entry("G1 ", GROUP_JVM),
			Map.entry("C1 CompilerThre", GROUP_JVM),
			Map.entry("C2 CompilerThre", GROUP_JVM),
			Map.entry("VM Thread", GROUP_JVM),
			Map.entry("VM Periodic Tas", GROUP_JVM),
			Map.entry("Monitor Deflati", GROUP_JVM),
			Map.entry("Service Thread", GROUP_JVM),
			Map.entry("Signal Dispatch", GROUP_JVM),
			Map.entry("Reference Handl", GROUP_JVM),
			Map.entry("Finalizer", GROUP_JVM),
			Map.entry("Common-Cleaner", GROUP_JVM),
			Map.entry("Notification Th", GROUP_JVM),
			Map.entry("Attach Listener", GROUP_JVM),
			Map.entry("Sweeper thread", GROUP_JVM),
			Map.entry("JFR ", GROUP_JVM),
			Map.entry("JVMCI", GROUP_JVM));

	/** 线程名（{@code comm}）所属的组。 */
	static String group(String comm) {
		if (comm != null) {
			for (Map.Entry<String, String> prefix : PREFIXES) {
				if (comm.startsWith(prefix.getKey())) {
					return prefix.getValue();
				}
			}
		}
		return GROUP_OTHER;
	}

	/** 一组线程的累计值；{@code cpuTicks} 是组内各线程 {@code utime + stime} 之和（时钟滴答），-1 = 没有（旧服务端、读不到）。 */
	record GroupCounts(long volCs, long nonvolCs, int threads, long cpuTicks) {

		/** 不带 CPU 时间（旧服务端、测试）。 */
		GroupCounts(long volCs, long nonvolCs, int threads) {
			this(volCs, nonvolCs, threads, -1);
		}

		boolean hasCpu() {
			return cpuTicks >= 0;
		}
	}

	/**
	 * 某一时刻的累计值；{@code threads} 是求和时存活的线程数，{@code groups} 是同一次遍历按组的累计值（键为 {@link #GROUPS} 里的组名，
	 * 只含有线程的组；旧服务端没有分组时为空）。
	 */
	record Snapshot(long syscr, long syscw, long volCs, long nonvolCs, int threads, Map<String, GroupCounts> groups) {

		Snapshot {
			groups = groups == null ? Map.of() : Map.copyOf(groups);
		}

		/** 不分组的快照（旧服务端、测试）。 */
		Snapshot(long syscr, long syscw, long volCs, long nonvolCs, int threads) {
			this(syscr, syscw, volCs, nonvolCs, threads, Map.of());
		}

		/** 压平成字符串（RPC 的 {@code Map<String, String>} 与 HTTP 的 JSON 对象共用），{@link #fromStrings} 还原。 */
		Map<String, String> toStrings() {
			Map<String, String> out = new LinkedHashMap<>();
			out.put("io.syscr", String.valueOf(syscr));
			out.put("io.syscw", String.valueOf(syscw));
			out.put("io.vol_cs", String.valueOf(volCs));
			out.put("io.nonvol_cs", String.valueOf(nonvolCs));
			out.put("io.threads", String.valueOf(threads));
			for (String g : GROUPS) {
				GroupCounts c = groups.get(g);
				if (c != null) {
					out.put("io.group." + g + ".vol_cs", String.valueOf(c.volCs()));
					out.put("io.group." + g + ".nonvol_cs", String.valueOf(c.nonvolCs()));
					out.put("io.group." + g + ".threads", String.valueOf(c.threads()));
					if (c.hasCpu()) {
						out.put("io.group." + g + ".cpu_ticks", String.valueOf(c.cpuTicks()));
					}
				}
			}
			return out;
		}

		/** 没有 io 字段（非 Linux 的服务端、旧服务端）或不是数字时 null；没有分组字段（旧服务端）时 groups 为空。 */
		static Snapshot fromStrings(Map<String, String> in) {
			if (in == null || in.get("io.syscw") == null) {
				return null;
			}
			try {
				Map<String, GroupCounts> groups = new LinkedHashMap<>();
				for (String g : GROUPS) {
					String vol = in.get("io.group." + g + ".vol_cs");
					if (vol != null) {
						String cpu = in.get("io.group." + g + ".cpu_ticks");
						groups.put(g, new GroupCounts(Long.parseLong(vol.trim()), Long.parseLong(in.get("io.group." + g + ".nonvol_cs").trim()),
								Integer.parseInt(in.get("io.group." + g + ".threads").trim()), cpu == null ? -1 : Long.parseLong(cpu.trim())));
					}
				}
				return new Snapshot(Long.parseLong(in.get("io.syscr").trim()), Long.parseLong(in.get("io.syscw").trim()),
						Long.parseLong(in.get("io.vol_cs").trim()), Long.parseLong(in.get("io.nonvol_cs").trim()),
						Integer.parseInt(in.get("io.threads").trim()), groups);
			} catch (RuntimeException e) {
				return null;
			}
		}
	}

	private IoSampler() {
	}

	/** 本进程此刻的快照；不是 Linux 或读不到 {@code /proc/self/io} 时 null。 */
	static Snapshot snapshot() {
		if (!Files.isReadable(PROC_SELF_IO)) {
			return null;
		}
		try {
			Map<String, Long> io = fields(Files.readAllLines(PROC_SELF_IO, StandardCharsets.US_ASCII), ':');
			long vol = 0;
			long nonvol = 0;
			int threads = 0;
			Map<String, long[]> byGroup = new LinkedHashMap<>();
			boolean cpuKnown = true;
			try (DirectoryStream<Path> tasks = Files.newDirectoryStream(PROC_SELF_TASK)) {
				for (Path task : tasks) {
					List<String> status;
					String comm;
					String stat;
					try {
						status = Files.readAllLines(task.resolve("status"), StandardCharsets.UTF_8);
						comm = Files.readString(task.resolve("comm"), StandardCharsets.UTF_8).strip();
						stat = Files.readString(task.resolve("stat"), StandardCharsets.UTF_8);
					} catch (IOException e) {
						continue; // 线程在列目录与读文件之间退出了
					}
					long ticks = cpuTicks(stat);
					if (ticks < 0) {
						cpuKnown = false;
					}
					Map<String, Long> f = fields(status, ':');
					long v = f.getOrDefault("voluntary_ctxt_switches", 0L);
					long n = f.getOrDefault("nonvoluntary_ctxt_switches", 0L);
					vol += v;
					nonvol += n;
					threads++;
					long[] g = byGroup.computeIfAbsent(group(comm), k -> new long[4]);
					g[0] += v;
					g[1] += n;
					g[2]++;
					g[3] += Math.max(ticks, 0);
				}
			}
			Long syscr = io.get("syscr");
			Long syscw = io.get("syscw");
			if (syscr == null || syscw == null) {
				return null;
			}
			Map<String, GroupCounts> groups = new LinkedHashMap<>();
			boolean withCpu = cpuKnown;
			byGroup.forEach((g, c) -> groups.put(g, new GroupCounts(c[0], c[1], (int) c[2], withCpu ? c[3] : -1)));
			return new Snapshot(syscr, syscw, vol, nonvol, threads, groups);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * {@code /proc/<pid>/task/<tid>/stat} 一行里的 {@code utime + stime}（第 14、15 项，时钟滴答）；解析不了时 -1。
	 * 第 2 项是括号里的线程名，名字本身可能含空格与括号，所以从最后一个 {@code ')'} 之后数：其后第 1 个字段是第 3 项（state）。
	 */
	static long cpuTicks(String stat) {
		if (stat == null) {
			return -1;
		}
		int close = stat.lastIndexOf(')');
		if (close < 0) {
			return -1;
		}
		String[] rest = stat.substring(close + 1).trim().split("\\s+");
		// rest[0] = 第 3 项 state，utime = 第 14 项 = rest[11]，stime = 第 15 项 = rest[12]
		if (rest.length < 13) {
			return -1;
		}
		try {
			return Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** {@code name: value} 行里值是整数的那些（其余跳过）。 */
	static Map<String, Long> fields(List<String> lines, char separator) {
		Map<String, Long> out = new LinkedHashMap<>();
		for (String line : lines) {
			int i = line.indexOf(separator);
			if (i <= 0) {
				continue;
			}
			String value = line.substring(i + 1).trim();
			try {
				out.put(line.substring(0, i).trim(), Long.parseLong(value));
			} catch (NumberFormatException e) {
				// 非数字字段（Name、Cpus_allowed_list 等）
			}
		}
		return out;
	}

	/** 窗口内的差值（每请求的派生值在轮末按窗口内完成数补上，见 {@link #perRequest}）。 */
	static Map<String, Object> delta(Snapshot before, Snapshot after) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("syscr", after.syscr() - before.syscr());
		out.put("syscw", after.syscw() - before.syscw());
		out.put("vol_cs", after.volCs() - before.volCs());
		out.put("nonvol_cs", after.nonvolCs() - before.nonvolCs());
		out.put("threads", before.threads() + " -> " + after.threads());
		if (!before.groups().isEmpty() && !after.groups().isEmpty()) {
			Map<String, Object> byGroup = new LinkedHashMap<>();
			// 两次快照的每一组都带 CPU 时间才出 cpu_ms（旧服务端、读不到 stat 时整端不出，不出半套）
			boolean cpu = before.groups().values().stream().allMatch(GroupCounts::hasCpu)
					&& after.groups().values().stream().allMatch(GroupCounts::hasCpu);
			long cpuTotalMs = 0;
			for (String g : GROUPS) {
				GroupCounts b = before.groups().get(g);
				GroupCounts a = after.groups().get(g);
				if (a == null && b == null) {
					continue;
				}
				GroupCounts b0 = b == null ? new GroupCounts(0, 0, 0, 0) : b;
				GroupCounts a0 = a == null ? new GroupCounts(0, 0, 0, 0) : a;
				Map<String, Object> d = new LinkedHashMap<>();
				d.put("threads", b0.threads() + " -> " + a0.threads());
				d.put("vol_cs", a0.volCs() - b0.volCs());
				d.put("nonvol_cs", a0.nonvolCs() - b0.nonvolCs());
				if (cpu) {
					long ms = (a0.cpuTicks() - b0.cpuTicks()) * MS_PER_TICK;
					d.put("cpu_ms", ms);
					cpuTotalMs += ms;
				}
				byGroup.put(g, d);
			}
			if (cpu) {
				out.put("cpu_ms", cpuTotalMs);
			}
			out.put("by_group", byGroup);
		}
		return out;
	}

	/**
	 * 在一端的差值里加上每请求值：{@code requests} = 窗口内完成的请求数（成功 + 失败）；为 0 时记 null。
	 * {@code by_group} 的每组同样加上 {@code vol_cs_per_request} / {@code nonvol_cs_per_request} / {@code cs_per_request}（二者之和），
	 * 有 {@code cpu_ms} 时（该端与每组）再加 {@code cpu_us_per_request}（微秒）。
	 */
	static void perRequest(Map<String, Object> side, long requests) {
		for (String key : List.of("syscr", "syscw", "vol_cs", "nonvol_cs")) {
			Object v = side.get(key);
			side.put(key + "_per_request", perRequest(v, requests));
		}
		if (side.get("cpu_ms") instanceof Number ms) {
			side.put("cpu_us_per_request", perRequest(ms.doubleValue() * 1000, requests));
		}
		if (side.get("by_group") instanceof Map<?, ?> byGroup) {
			Map<String, Object> out = new LinkedHashMap<>();
			byGroup.forEach((g, d) -> {
				if (d instanceof Map<?, ?> counts) {
					Map<String, Object> copy = new LinkedHashMap<>();
					counts.forEach((k, v) -> copy.put(String.valueOf(k), v));
					Object vol = copy.get("vol_cs");
					Object nonvol = copy.get("nonvol_cs");
					copy.put("vol_cs_per_request", perRequest(vol, requests));
					copy.put("nonvol_cs_per_request", perRequest(nonvol, requests));
					copy.put("cs_per_request", vol instanceof Number a && nonvol instanceof Number b
							? perRequest(a.longValue() + b.longValue(), requests) : null);
					if (copy.get("cpu_ms") instanceof Number ms) {
						copy.put("cpu_us_per_request", perRequest(ms.doubleValue() * 1000, requests));
					}
					out.put(String.valueOf(g), copy);
				} else {
					out.put(String.valueOf(g), d);
				}
			});
			side.put("by_group", out);
		}
	}

	private static Double perRequest(Object value, long requests) {
		return requests > 0 && value instanceof Number n ? RoundRecorder.round3(n.doubleValue() / requests) : null;
	}

	/**
	 * 一端某组的每请求切换（自愿 + 非自愿）；取不到（旧结果文件、该端没有分组、该组不存在）时 null。
	 */
	static Double groupPerRequest(Map<?, ?> round, String side, String group) {
		if (round.get("io") instanceof Map<?, ?> io && io.get(side) instanceof Map<?, ?> s && s.get("by_group") instanceof Map<?, ?> byGroup
				&& byGroup.get(group) instanceof Map<?, ?> g && g.get("cs_per_request") instanceof Number n) {
			return n.doubleValue();
		}
		return null;
	}

	/** 一端某组的一个字段（线程数 "a -> b" 等）；取不到时 null。 */
	static Object groupField(Map<?, ?> round, String side, String group, String key) {
		if (round.get("io") instanceof Map<?, ?> io && io.get(side) instanceof Map<?, ?> s && s.get("by_group") instanceof Map<?, ?> byGroup
				&& byGroup.get(group) instanceof Map<?, ?> g) {
			return g.get(key);
		}
		return null;
	}

	/** 轮次是否至少有一端带分组（决定 Markdown 是否出分组表）。 */
	static boolean hasGroups(Map<?, ?> round) {
		if (round.get("io") instanceof Map<?, ?> io) {
			for (String side : List.of("client", "server")) {
				if (io.get(side) instanceof Map<?, ?> s && s.get("by_group") instanceof Map<?, ?> g && !g.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Markdown 小表：一个轮次两端按线程组的每请求上下文切换（自愿 + 非自愿，括号内为自愿 / 非自愿）、每请求 CPU 时间（微秒，
	 * {@code cpu_us_per_request}）与窗口起止的存活线程数。没有分组的一端整列记 —，没有 CPU 时间的一端（旧服务端）CPU 列记 —。
	 */
	static void groupTable(StringBuilder md, Map<?, ?> round) {
		md.append("| 线程组 | 客户端 线程数 | 客户端 切换/请求（自愿 / 非自愿） | 客户端 CPU µs/请求 | 服务端 线程数 | 服务端 切换/请求（自愿 / 非自愿） | 服务端 CPU µs/请求 |\n")
				.append("|---|---|---|---|---|---|---|\n");
		double[] totals = new double[2];
		boolean[] any = new boolean[2];
		double[] cpuTotals = new double[2];
		boolean[] anyCpu = new boolean[2];
		for (String g : GROUPS) {
			if (groupField(round, "client", g, "vol_cs") == null && groupField(round, "server", g, "vol_cs") == null) {
				continue;
			}
			md.append("| ").append(g);
			int i = 0;
			for (String side : List.of("client", "server")) {
				Object threads = groupField(round, side, g, "threads");
				Double cs = groupPerRequest(round, side, g);
				Object vol = groupField(round, side, g, "vol_cs_per_request");
				Object nonvol = groupField(round, side, g, "nonvol_cs_per_request");
				md.append(" | ").append(threads == null ? "—" : String.valueOf(threads));
				if (cs == null) {
					md.append(" | —");
				} else {
					totals[i] += cs;
					any[i] = true;
					md.append(" | ").append(fmt(cs)).append("（").append(fmt(vol)).append(" / ").append(fmt(nonvol)).append("）");
				}
				if (groupField(round, side, g, "cpu_us_per_request") instanceof Number cpu) {
					cpuTotals[i] += cpu.doubleValue();
					anyCpu[i] = true;
					md.append(" | ").append(fmt2(cpu));
				} else {
					md.append(" | —");
				}
				i++;
			}
			md.append(" |\n");
		}
		md.append("| 合计 | | ").append(any[0] ? fmt(totals[0]) : "—").append(" | ").append(anyCpu[0] ? fmt2(cpuTotals[0]) : "—")
				.append(" | | ").append(any[1] ? fmt(totals[1]) : "—").append(" | ").append(anyCpu[1] ? fmt2(cpuTotals[1]) : "—").append(" |\n");
	}

	private static String fmt2(Object value) {
		return value instanceof Number n ? String.format(Locale.ROOT, "%.2f", n.doubleValue()) : "—";
	}

	private static String fmt(Object value) {
		return value instanceof Number n ? String.format(Locale.ROOT, "%.3f", n.doubleValue()) : "—";
	}

	/** 轮次一端的每请求值；没有该字段（旧结果文件）或取不到时 null。 */
	static Double perRequestValue(Map<?, ?> round, String side, String key) {
		if (round.get("io") instanceof Map<?, ?> io && io.get(side) instanceof Map<?, ?> s && s.get(key + "_per_request") instanceof Number n) {
			return n.doubleValue();
		}
		return null;
	}

	/** Markdown 的一格：一端的每请求值（3 位小数），取不到时原样输出 n/a / unknown，旧结果文件为 —。 */
	static String cell(Map<?, ?> round, String side, String key) {
		Double value = perRequestValue(round, side, key);
		if (value != null) {
			return String.format(java.util.Locale.ROOT, "%.2f", value);
		}
		Object io = round.get("io");
		if (io == null) {
			return "—";
		}
		Object s = io instanceof Map<?, ?> m ? m.get(side) : io;
		return s == null ? "—" : s instanceof Map<?, ?> ? "—" : String.valueOf(s);
	}
}
