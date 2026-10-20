package com.feynman.rpc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * </ul>
 * 读文件的开销：每次快照读 1 + 线程数个小文件（百个量级），只在窗口起止各一次，在采样虚拟线程上，不在压测路径上。
 */
final class IoSampler {

	static final Path PROC_SELF_IO = Path.of("/proc/self/io");
	static final Path PROC_SELF_TASK = Path.of("/proc/self/task");
	static final String SOURCE = "/proc/self/io (syscr, syscw) + sum of /proc/self/task/*/status (voluntary / nonvoluntary_ctxt_switches), steady window";

	/** 某一时刻的累计值；{@code threads} 是求和时存活的线程数。 */
	record Snapshot(long syscr, long syscw, long volCs, long nonvolCs, int threads) {

		/** 压平成字符串（RPC 的 {@code Map<String, String>} 与 HTTP 的 JSON 对象共用），{@link #fromStrings} 还原。 */
		Map<String, String> toStrings() {
			Map<String, String> out = new LinkedHashMap<>();
			out.put("io.syscr", String.valueOf(syscr));
			out.put("io.syscw", String.valueOf(syscw));
			out.put("io.vol_cs", String.valueOf(volCs));
			out.put("io.nonvol_cs", String.valueOf(nonvolCs));
			out.put("io.threads", String.valueOf(threads));
			return out;
		}

		/** 没有 io 字段（非 Linux 的服务端、旧服务端）或不是数字时 null。 */
		static Snapshot fromStrings(Map<String, String> in) {
			if (in == null || in.get("io.syscw") == null) {
				return null;
			}
			try {
				return new Snapshot(Long.parseLong(in.get("io.syscr").trim()), Long.parseLong(in.get("io.syscw").trim()),
						Long.parseLong(in.get("io.vol_cs").trim()), Long.parseLong(in.get("io.nonvol_cs").trim()),
						Integer.parseInt(in.get("io.threads").trim()));
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
			try (DirectoryStream<Path> tasks = Files.newDirectoryStream(PROC_SELF_TASK)) {
				for (Path task : tasks) {
					List<String> status;
					try {
						status = Files.readAllLines(task.resolve("status"), StandardCharsets.UTF_8);
					} catch (IOException e) {
						continue; // 线程在列目录与读文件之间退出了
					}
					Map<String, Long> f = fields(status, ':');
					vol += f.getOrDefault("voluntary_ctxt_switches", 0L);
					nonvol += f.getOrDefault("nonvoluntary_ctxt_switches", 0L);
					threads++;
				}
			}
			Long syscr = io.get("syscr");
			Long syscw = io.get("syscw");
			if (syscr == null || syscw == null) {
				return null;
			}
			return new Snapshot(syscr, syscw, vol, nonvol, threads);
		} catch (IOException | RuntimeException e) {
			return null;
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
		return out;
	}

	/** 在一端的差值里加上每请求值：{@code requests} = 窗口内完成的请求数（成功 + 失败）；为 0 时记 null。 */
	static void perRequest(Map<String, Object> side, long requests) {
		for (String key : List.of("syscr", "syscw", "vol_cs", "nonvol_cs")) {
			Object v = side.get(key);
			side.put(key + "_per_request", requests > 0 && v instanceof Number n ? RoundRecorder.round3(n.doubleValue() / requests) : null);
		}
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
