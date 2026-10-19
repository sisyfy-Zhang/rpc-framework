package com.feynman.rpc.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code --pin-dispatcher <cpu>}（只在 Linux 上生效）：把开环调度线程单独钉到一个核，进程里其余线程钉到进程亲和里
 * 剩下的核。调度线程忙等对齐节拍、独占一个核；不钉时调度器可能把 IO 线程或虚拟线程的载体放到同一个核上，
 * 被唤醒的一方要等忙等线程让出时间片，调度线程本身也会被挤开（发出滞后的长尾）。
 *
 * <p>Java 没有设置线程亲和的 API，这里按 native tid 调 {@code taskset -cp}：JVM 把 Java 线程名写进
 * {@code /proc/self/task/<tid>/comm}（截断到 15 字节），调度线程名 {@value #DISPATCHER_THREAD_NAME} 不超过 15 字节，
 * 按名字就能找到它。其余线程一次 {@code sh -c} 批量设置（不在每个 tid 上各 fork 一次）。
 *
 * <p>新建的平台线程继承<b>创建它的线程</b>的亲和：轮次中途由调度线程触发新建的 ForkJoinPool 载体会继承调度线程的核。
 * 所以每轮开始前都重新扫一遍，轮次结束时再核对一次（{@link #strays}），结果进 {@code rounds[].scheduler.pin}。
 * 实际生效情况一律以读回的 {@code Cpus_allowed_list} 为准，不以 taskset 的退出码为准。
 */
final class ThreadAffinity {

	/** 开环调度线程的名字（≤ 15 字节：comm 里能完整看到）。 */
	static final String DISPATCHER_THREAD_NAME = "bench-dispatch";

	static final Path TASKS = Path.of("/proc/self/task");

	/** 见 {@link #processCpus()}：进程启动时的亲和，只写一次。 */
	private static final java.util.concurrent.atomic.AtomicReference<TreeSet<Integer>> PROCESS_CPUS =
			new java.util.concurrent.atomic.AtomicReference<>();

	private ThreadAffinity() {
	}

	static boolean supported() {
		return Files.isDirectory(TASKS) && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
	}

	/**
	 * 把名为 {@code threadName} 的线程钉到 {@code cpu}，其余线程钉到进程亲和减去 {@code cpu}。任何一步失败都不抛：
	 * 结果原样记录（{@code status} = pinned / partial / failed / unsupported / skipped）。
	 */
	static Map<String, Object> pin(String threadName, int cpu) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("cpu", cpu);
		if (!supported()) {
			result.put("status", "unsupported");
			result.put("reason", "not Linux (no /proc/self/task); the dispatcher runs unpinned");
			return result;
		}
		try {
			TreeSet<Integer> allowed = processCpus();
			result.put("process_cpus", format(allowed));
			if (!allowed.contains(cpu)) {
				result.put("status", "skipped");
				result.put("reason", "cpu " + cpu + " is not in the process affinity " + format(allowed));
				return result;
			}
			TreeSet<Integer> others = new TreeSet<>(allowed);
			others.remove(cpu);
			String othersList = format(others);
			result.put("others_cpus", others.isEmpty() ? "n/a (only one cpu allowed)" : othersList);
			Map<Long, String> threads = threads();
			List<Long> dispatcher = named(threads, threadName);
			// JVM 在新线程自己的启动路径上写 comm：刚 start() 的线程可能还看不到名字，稍等再找（至多约 1s）
			for (int i = 0; i < 100 && dispatcher.isEmpty(); i++) {
				Thread.sleep(10);
				threads = threads();
				dispatcher = named(threads, threadName);
			}
			if (dispatcher.size() != 1) {
				result.put("status", "failed");
				result.put("reason", dispatcher.size() + " threads named " + threadName + " in /proc/self/task");
				return result;
			}
			long dispatcherTid = dispatcher.get(0);
			result.put("dispatcher_tid", dispatcherTid);
			List<Long> rest = threads.keySet().stream().filter(t -> t != dispatcherTid).toList();
			StringBuilder script = new StringBuilder("taskset -cp ").append(cpu).append(' ').append(dispatcherTid).append(" >/dev/null || echo fail ")
					.append(dispatcherTid).append('\n');
			if (!others.isEmpty()) {
				for (long tid : rest) {
					// 线程可能已经退出：失败只记一笔，核对时以读回的亲和为准
					script.append("taskset -cp ").append(othersList).append(' ').append(tid).append(" >/dev/null 2>&1 || echo gone ").append(tid)
							.append('\n');
				}
			}
			String output = shell(script.toString());
			String dispatcherNow = cpusAllowed(dispatcherTid);
			result.put("dispatcher_cpus_allowed", dispatcherNow);
			int othersPinned = 0;
			int othersGone = 0;
			for (long tid : rest) {
				String now = cpusAllowed(tid);
				if (now == null) {
					othersGone++;
				} else if (others.isEmpty() || now.equals(othersList)) {
					othersPinned++;
				}
			}
			result.put("threads_total", threads.size());
			result.put("others_pinned", othersPinned);
			result.put("others_exited", othersGone);
			boolean dispatcherOk = String.valueOf(cpu).equals(dispatcherNow);
			boolean othersOk = othersPinned + othersGone == rest.size();
			result.put("status", dispatcherOk && othersOk ? "pinned" : dispatcherOk ? "partial" : "failed");
			if (!output.isBlank() && output.contains("fail")) {
				result.put("taskset_output", output.strip());
			}
		} catch (IOException | RuntimeException e) {
			result.put("status", "failed");
			result.put("reason", e.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			result.put("status", "failed");
			result.put("reason", "interrupted");
		}
		return result;
	}

	/**
	 * 把进程里全部线程的亲和恢复为进程原本的核（{@link #processCpus()}）。harness 自己不需要它（进程跑完就退出），
	 * 给在同一个 JVM 里调用过 {@link #pin} 的测试收尾用。返回恢复后仍未回到原亲和的线程数；不支持时返回 0。
	 */
	static int unpinAll() {
		if (!supported()) {
			return 0;
		}
		try {
			String all = format(processCpus());
			StringBuilder script = new StringBuilder();
			Map<Long, String> threads = threads();
			for (long tid : threads.keySet()) {
				script.append("taskset -cp ").append(all).append(' ').append(tid).append(" >/dev/null 2>&1").append('\n');
			}
			shell(script.toString());
			int notRestored = 0;
			for (long tid : threads.keySet()) {
				String now = cpusAllowed(tid);
				if (now != null && !now.equals(all)) {
					notRestored++;
				}
			}
			return notRestored;
		} catch (IOException e) {
			return -1;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	/**
	 * 轮次结束时的核对：除 {@code exceptTid} 外，亲和里含 {@code cpu} 的线程（名字列表，至多 20 个）。只读文件，不 fork。
	 * 不支持时返回 null。
	 */
	static List<String> strays(int cpu, long exceptTid) {
		if (!supported()) {
			return null;
		}
		List<String> strays = new ArrayList<>();
		try {
			for (Map.Entry<Long, String> e : threads().entrySet()) {
				if (e.getKey() == exceptTid) {
					continue;
				}
				String allowed = cpusAllowed(e.getKey());
				if (allowed != null && CpuSampler.isCpuList(allowed) && CpuSampler.parseCpuList(allowed).contains(cpu)) {
					if (strays.size() < 20) {
						strays.add(e.getValue() + "(" + e.getKey() + ")=" + allowed);
					}
				}
			}
		} catch (IOException e) {
			return List.of("unknown: " + e);
		}
		return strays;
	}

	/**
	 * 进程原本可用的核：第一次调用时读 {@code /proc/self/status}（主线程的亲和）并记住。不能每次现读：第一次钉核之后
	 * 主线程已被缩到"其余核"，也不能取各线程的并集：上一段的调度线程退出后，它那一个核就从并集里消失了。
	 * 第一次调用一定发生在任何钉核之前（只有 {@link #pin} 会改亲和，它先调这里）。
	 */
	static TreeSet<Integer> processCpus() throws IOException {
		TreeSet<Integer> cpus = PROCESS_CPUS.get();
		if (cpus == null) {
			String list = statusField(Path.of("/proc/self/status"), "Cpus_allowed_list");
			if (list == null || !CpuSampler.isCpuList(list)) {
				throw new IOException("cannot read Cpus_allowed_list from /proc/self/status");
			}
			PROCESS_CPUS.compareAndSet(null, CpuSampler.parseCpuList(list));
			cpus = PROCESS_CPUS.get();
		}
		return new TreeSet<>(cpus);
	}

	private static List<Long> named(Map<Long, String> threads, String name) {
		return threads.entrySet().stream().filter(e -> name.equals(e.getValue())).map(Map.Entry::getKey).toList();
	}

	/** tid → comm。 */
	static Map<Long, String> threads() throws IOException {
		Map<Long, String> threads = new LinkedHashMap<>();
		try (Stream<Path> tasks = Files.list(TASKS)) {
			for (Path task : tasks.sorted().toList()) {
				long tid;
				try {
					tid = Long.parseLong(task.getFileName().toString());
				} catch (NumberFormatException e) {
					continue;
				}
				try {
					threads.put(tid, Files.readString(task.resolve("comm"), StandardCharsets.UTF_8).strip());
				} catch (IOException e) {
					// 线程刚退出
				}
			}
		}
		return threads;
	}

	/** 某个线程当前的 {@code Cpus_allowed_list}；线程已退出时 null。 */
	static String cpusAllowed(long tid) {
		try {
			return statusField(TASKS.resolve(Long.toString(tid)).resolve("status"), "Cpus_allowed_list");
		} catch (IOException e) {
			return null;
		}
	}

	private static String statusField(Path status, String field) throws IOException {
		for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
			if (line.startsWith(field + ":")) {
				return line.substring(field.length() + 1).strip();
			}
		}
		return null;
	}

	/** {0,1,2,5} → {@code 0-2,5}（与 taskset / Cpus_allowed_list 同一种写法，便于逐字比较）。 */
	static String format(TreeSet<Integer> cpus) {
		List<String> parts = new ArrayList<>();
		Integer from = null;
		Integer prev = null;
		for (int cpu : cpus) {
			if (from == null) {
				from = cpu;
			} else if (cpu != prev + 1) {
				parts.add(from.equals(prev) ? String.valueOf(from) : from + "-" + prev);
				from = cpu;
			}
			prev = cpu;
		}
		if (from != null) {
			parts.add(from.equals(prev) ? String.valueOf(from) : from + "-" + prev);
		}
		return parts.stream().collect(Collectors.joining(","));
	}

	private static String shell(String script) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
		process.getOutputStream().close();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("taskset batch did not finish within 30s");
		}
		return output;
	}
}
