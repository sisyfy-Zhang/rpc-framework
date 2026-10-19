package com.feynman.rpc.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每轮统计窗口的 GC 观测：本进程累计 GC 次数 / 时间与当前堆用量的快照（{@link GarbageCollectorMXBean}、
 * {@link java.lang.management.MemoryMXBean}），窗口起止各取一次做差值，进 {@code rounds[].gc.client}；
 * 服务端由 {@code BenchService.stats()} / {@code GET /stats} 返回同一份快照（值压平成字符串），进 {@code rounds[].gc.server}。
 *
 * <p><b>停顿与并发周期分开算</b>：收集器 MXBean 的 {@code getCollectionTime()} 对 STW 收集器（G1 的 Young / Old /
 * Concurrent GC——后者记 Remark 与 Cleanup 两个停顿、Parallel、Serial）就是停顿时间；但 ZGC / Shenandoah 的
 * {@code ... Cycles} MXBean 记的是<b>并发周期</b>的时长（应用线程照常运行），停顿在对应的 {@code ... Pauses} MXBean 里。
 * 所以 {@code pause_ms} / {@code collections} 只累加名字不以 {@code Cycles} 结尾的收集器；并发周期照样逐个列在
 * {@code collectors} 里（{@code kind = concurrent_cycle}），不计入停顿。MXBean 的时间是毫秒粒度。
 */
final class GcSampler {

	static final String KIND_PAUSE = "pause";
	static final String KIND_CONCURRENT_CYCLE = "concurrent_cycle";

	/** 一个收集器 MXBean 的累计值。 */
	record Collector(String name, long count, long timeMs) {

		/** ZGC / Shenandoah 的 "... Cycles"：并发周期的时长，不是停顿。 */
		boolean concurrentCycle() {
			return name.endsWith("Cycles");
		}
	}

	/** 某一时刻的累计 GC 值与堆用量。 */
	record Snapshot(List<Collector> collectors, long heapUsedBytes, long heapCommittedBytes) {

		long pauseCount() {
			return collectors.stream().filter(c -> !c.concurrentCycle()).mapToLong(Collector::count).sum();
		}

		long pauseMs() {
			return collectors.stream().filter(c -> !c.concurrentCycle()).mapToLong(Collector::timeMs).sum();
		}

		/** 压平成字符串（RPC 的 {@code Map<String, String>} 与 HTTP 的 JSON 对象共用），{@link #fromStrings} 还原。 */
		Map<String, String> toStrings() {
			Map<String, String> out = new LinkedHashMap<>();
			out.put("gc_collections", String.valueOf(pauseCount()));
			out.put("gc_pause_ms", String.valueOf(pauseMs()));
			out.put("heap_used_bytes", String.valueOf(heapUsedBytes));
			out.put("heap_committed_bytes", String.valueOf(heapCommittedBytes));
			out.put("collectors", String.join(",", collectors.stream().map(Collector::name).toList()));
			for (Collector c : collectors) {
				out.put("collector." + c.name() + ".count", String.valueOf(c.count()));
				out.put("collector." + c.name() + ".time_ms", String.valueOf(c.timeMs()));
			}
			return out;
		}

		/** @throws IllegalArgumentException 缺字段或不是数字（例如对端不是本 harness 的 bench server） */
		static Snapshot fromStrings(Map<String, String> in) {
			String names = in.get("collectors");
			if (names == null) {
				throw new IllegalArgumentException("stats have no 'collectors' field: " + in.keySet());
			}
			List<Collector> collectors = new ArrayList<>();
			for (String name : names.isEmpty() ? new String[0] : names.split(",")) {
				collectors.add(new Collector(name, number(in, "collector." + name + ".count"),
						number(in, "collector." + name + ".time_ms")));
			}
			return new Snapshot(List.copyOf(collectors), number(in, "heap_used_bytes"), number(in, "heap_committed_bytes"));
		}

		private static long number(Map<String, String> in, String key) {
			String value = in.get(key);
			if (value == null) {
				throw new IllegalArgumentException("stats have no '" + key + "' field");
			}
			return Long.parseLong(value.trim());
		}
	}

	private GcSampler() {
	}

	/** 本进程此刻的快照。收集器按 MXBean 的顺序；不支持的值（-1）记 0。 */
	static Snapshot snapshot() {
		List<Collector> collectors = new ArrayList<>();
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			collectors.add(new Collector(bean.getName(), Math.max(0, bean.getCollectionCount()), Math.max(0, bean.getCollectionTime())));
		}
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		return new Snapshot(List.copyOf(collectors), heap.getUsed(), heap.getCommitted());
	}

	/**
	 * 窗口内的 GC：{@code collections}（停顿次数）、{@code pause_ms}、{@code pause_pct}（占窗口时长的百分比）、
	 * 逐个收集器的次数与时间（标明 pause / concurrent_cycle）、窗口起止的堆用量。
	 */
	static Map<String, Object> delta(Snapshot before, Snapshot after, long windowMs) {
		Map<String, Object> out = new LinkedHashMap<>();
		long pauseCount = after.pauseCount() - before.pauseCount();
		long pauseMs = after.pauseMs() - before.pauseMs();
		out.put("collections", pauseCount);
		out.put("pause_ms", pauseMs);
		out.put("pause_pct", windowMs <= 0 ? null : RoundRecorder.round3(pauseMs * 100.0 / windowMs));
		List<Object> collectors = new ArrayList<>();
		for (Collector a : after.collectors()) {
			Collector b = before.collectors().stream().filter(c -> c.name().equals(a.name())).findFirst()
					.orElse(new Collector(a.name(), 0, 0));
			Map<String, Object> c = new LinkedHashMap<>();
			c.put("name", a.name());
			c.put("kind", a.concurrentCycle() ? KIND_CONCURRENT_CYCLE : KIND_PAUSE);
			c.put("collections", a.count() - b.count());
			c.put("time_ms", a.timeMs() - b.timeMs());
			collectors.add(c);
		}
		out.put("collectors", collectors);
		out.put("heap_used_mb_start", mb(before.heapUsedBytes()));
		out.put("heap_used_mb_end", mb(after.heapUsedBytes()));
		out.put("heap_committed_mb", mb(after.heapCommittedBytes()));
		return out;
	}

	/** 轮次的客户端 / 服务端 GC 停顿（ms）；没有该字段（旧结果文件）或取不到时为 null。 */
	static Double pauseMs(Map<?, ?> round, String side) {
		Object gc = round.get("gc");
		if (gc instanceof Map<?, ?> m && m.get(side) instanceof Map<?, ?> s && s.get("pause_ms") instanceof Number n) {
			return n.doubleValue();
		}
		return null;
	}

	/** 同上，占窗口时长的百分比。 */
	static Double pausePct(Map<?, ?> round, String side) {
		Object gc = round.get("gc");
		if (gc instanceof Map<?, ?> m && m.get(side) instanceof Map<?, ?> s && s.get("pause_pct") instanceof Number n) {
			return n.doubleValue();
		}
		return null;
	}

	private static double mb(long bytes) {
		return RoundRecorder.round3(bytes / (1024.0 * 1024.0));
	}
}
