package io.github.sisyfyzhang.steadyrpc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 每轮统计窗口内的 CPU 忙碌率（Linux 读 {@code /proc/stat}，其它平台不采样、记 {@value EnvironmentProbe#NA}）。
 *
 * <p>两组核：客户端核 = 压测端 {@code environment.cpus_allowed}，服务端核 = {@code server_environment.cpus_allowed}
 * （拿不到、或卡片声明不同机时服务端记 n/a）。窗口开始与结束各取一次快照，忙碌率 = 1 − Δidle / Δtotal（idle 含 iowait），
 * 每组取各核平均。它回答"这一轮的瓶颈在哪一侧"：闭环卡的客户端核接近 100% 而服务端有余量时，测到的是 harness 的发压上限。
 * 采样线程是一个只睡眠、读两次文件的虚拟线程，不占压测路径。
 */
final class CpuSampler {

	static final Path PROC_STAT = Path.of("/proc/stat");
	/** 有效轮里客户端核平均忙碌率超过它即 {@code validity.client_bound}（不算失败，进声明段）。 */
	static final double CLIENT_BOUND_PCT = 90.0;

	/** 任一有效轮的客户端核平均忙碌率 &gt; {@value #CLIENT_BOUND_PCT}%。 */
	static boolean clientBound(List<Map<String, Object>> rounds) {
		for (Map<String, Object> round : rounds) {
			if (Boolean.TRUE.equals(round.get("valid")) && round.get("cpu") instanceof Map<?, ?> cpu
					&& cpu.get("client_busy_pct") instanceof Number busy && busy.doubleValue() > CLIENT_BOUND_PCT) {
				return true;
			}
		}
		return false;
	}

	/** 控制台一行里的简写：{@code client 98.1% / server 77.0%}；没采样时原样输出（n/a / unknown）。 */
	static String brief(Object cpu) {
		if (!(cpu instanceof Map<?, ?> m)) {
			return String.valueOf(cpu);
		}
		return "client " + pct(m.get("client_busy_pct")) + " / server " + pct(m.get("server_busy_pct"));
	}

	static String pct(Object value) {
		return value instanceof Number n ? String.format(java.util.Locale.ROOT, "%.1f%%", n.doubleValue()) : EnvironmentProbe.NA;
	}

	private final String clientCpus;
	private final String serverCpus;
	private final TreeSet<Integer> client;
	private final TreeSet<Integer> server;

	private CpuSampler(String clientCpus, String serverCpus) {
		this.clientCpus = clientCpus;
		this.serverCpus = serverCpus;
		this.client = parseCpuList(clientCpus);
		this.server = serverCpus == null ? null : parseCpuList(serverCpus);
	}

	/**
	 * @param clientCpus 压测端 {@code cpus_allowed}（如 {@code 5-9}）；不是核列表时（n/a、unknown）返回 null = 不采样
	 * @param serverCpus 服务端 {@code cpus_allowed}；不是核列表或不同机时服务端那一组记 n/a
	 */
	static CpuSampler create(Object clientCpus, Object serverCpus, boolean sameHost) {
		if (!Files.isReadable(PROC_STAT) || !isCpuList(clientCpus)) {
			return null;
		}
		return new CpuSampler(String.valueOf(clientCpus), sameHost && isCpuList(serverCpus) ? String.valueOf(serverCpus) : null);
	}

	static boolean isCpuList(Object value) {
		return value instanceof String s && s.matches("[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*");
	}

	/** {@code "0-4,7"} → {0,1,2,3,4,7}。 */
	static TreeSet<Integer> parseCpuList(String list) {
		TreeSet<Integer> cpus = new TreeSet<>();
		for (String part : list.split(",")) {
			int dash = part.indexOf('-');
			if (dash < 0) {
				cpus.add(Integer.parseInt(part.trim()));
			} else {
				int from = Integer.parseInt(part.substring(0, dash).trim());
				int to = Integer.parseInt(part.substring(dash + 1).trim());
				for (int i = from; i <= to; i++) {
					cpus.add(i);
				}
			}
		}
		return cpus;
	}

	/** 各核 {idle（含 iowait）, total}（user..steal 八项之和，guest 已含在 user 里）。 */
	static Map<Integer, long[]> snapshot(List<String> procStatLines) {
		Map<Integer, long[]> cpus = new TreeMap<>();
		for (String line : procStatLines) {
			if (!line.startsWith("cpu") || line.length() < 4 || !Character.isDigit(line.charAt(3))) {
				continue;
			}
			String[] f = line.trim().split("\\s+");
			int cpu = Integer.parseInt(f[0].substring(3));
			long total = 0;
			for (int i = 1; i <= 8 && i < f.length; i++) {
				total += Long.parseLong(f[i]);
			}
			long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
			cpus.put(cpu, new long[] { idle, total });
		}
		return cpus;
	}

	static Map<Integer, long[]> snapshot() throws IOException {
		return snapshot(Files.readAllLines(PROC_STAT, StandardCharsets.US_ASCII));
	}

	/** 两次快照之间两组核的平均忙碌率，进 {@code rounds[].cpu}。 */
	Map<String, Object> measure(Map<Integer, long[]> before, Map<Integer, long[]> after, long windowMillis) {
		Map<String, Object> cpu = new LinkedHashMap<>();
		cpu.put("source", "/proc/stat, steady window");
		cpu.put("window_ms", windowMillis);
		cpu.put("client_cpus", clientCpus);
		cpu.put("client_busy_pct", average(client, before, after));
		cpu.put("server_cpus", serverCpus == null ? EnvironmentProbe.NA : serverCpus);
		cpu.put("server_busy_pct", server == null ? null : average(server, before, after));
		return cpu;
	}

	private static Double average(TreeSet<Integer> cpus, Map<Integer, long[]> before, Map<Integer, long[]> after) {
		double sum = 0;
		int n = 0;
		for (int cpu : cpus) {
			long[] a = before.get(cpu);
			long[] b = after.get(cpu);
			if (a == null || b == null) {
				continue;
			}
			long total = b[1] - a[1];
			long idle = b[0] - a[0];
			sum += total > 0 ? 100.0 * (total - idle) / total : 0;
			n++;
		}
		return n == 0 ? null : Math.round(sum / n * 10) / 10.0;
	}
}
