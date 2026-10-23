package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * 原样返回入参，不做任何业务计算；{@link #environment()} 返回构造时采集好的服务端环境，{@link #stats()} 返回此刻的 GC 统计
 * （与系统调用 / 上下文切换，以及 rpc 服务端的出站背压计数）。
 */
public final class BenchServiceImpl implements BenchService {

	private final Map<String, String> environment;
	/** 附加进 stats() 的服务端计数（rpc 服务端：出站背压，见 {@link BackpressureStats}）；没有时返回空表。 */
	private final Supplier<Map<String, String>> extraStats;
	private final Supplier<Map<String, String>> counters;
	private final long workNanos;
	private final WorkMode workMode;
	/**
	 * monitor / sync-park 每次新建的锁对象写到这里，让它<b>全局逃逸</b>：否则 C2 的逃逸分析会认定一个局部 new 出来、从不外泄的锁对象
	 * 没有别人能同步，直接消除 synchronized（lock elision）——JIT 编译之后就不再钉住，测到的是"没有 synchronized"。
	 * （冒烟实测：不加这一步时 sync-park 在预热后吞吐从 ≈ 500 涨到数千 req/s、排队等待 p50 0.09ms。）
	 */
	@SuppressWarnings("unused")
	private static volatile Object escapedLock;

	/**
	 * 业务方法"等待"的方式（{@code server --work-mode}，卡片 {@code target.server_work_mode}）。三种都不占 CPU、占着执行业务方法的线程，
	 * 区别只在虚拟线程上（JDK 21）：
	 * <ul>
	 *   <li>{@link #PARK}（缺省）：{@code LockSupport.parkNanos}，虚拟线程卸载，载体线程去跑别的虚拟线程；</li>
	 *   <li>{@link #MONITOR}：在一个 {@code synchronized} 块内 {@code Object.wait(timeout)}。JDK 21 上虚拟线程在 synchronized 内
	 *       不能卸载（钉住载体），但 {@code Object.wait} 属于"会补偿"的阻塞操作：调度器临时给载体池扩容（上限
	 *       {@code jdk.virtualThreadScheduler.maxPoolSize}，缺省 256）——吞吐不一定掉，载体线程数会涨；</li>
	 *   <li>{@link #SYNC_PARK}：在一个 {@code synchronized} 块内 {@code LockSupport.parkNanos}。钉住且<b>不补偿</b>：
	 *       同时在等的请求数被载体数（= 可用核数）封顶，这是 JEP 491（JDK 24）修掉的那种退化。</li>
	 * </ul>
	 * 锁对象每次调用新建（不跨请求争用）：测的是钉住，不是锁竞争。平台线程（固定池）上三种方式等价。
	 */
	enum WorkMode {
		PARK("park"), MONITOR("monitor"), SYNC_PARK("sync-park");

		private final String cardValue;

		WorkMode(String cardValue) {
			this.cardValue = cardValue;
		}

		/** 卡片 / 命令行 / 服务端环境里的写法。 */
		String cardValue() {
			return cardValue;
		}

		/** 卡片 / 命令行写法 → 枚举；不认识时 null。 */
		static WorkMode of(String text) {
			for (WorkMode m : values()) {
				if (text != null && m.cardValue.equals(text.trim())) {
					return m;
				}
			}
			return null;
		}

		/** 全部写法（卡片校验的枚举）。 */
		static java.util.Set<String> cardValues() {
			java.util.Set<String> out = new java.util.LinkedHashSet<>();
			for (WorkMode m : values()) {
				out.add(m.cardValue);
			}
			return out;
		}
	}

	public BenchServiceImpl(Map<String, String> environment) {
		this(environment, Map::of);
	}

	public BenchServiceImpl(Map<String, String> environment, Supplier<Map<String, String>> extraStats) {
		this(environment, extraStats, Map::of, 0);
	}

	/**
	 * @param counters  {@link #counters()} 的内容（rpc bench server：{@link ServerCounters}）
	 * @param workNanos 每次 echo / echoOrder 返回前用 {@link LockSupport#parkNanos} 等待的时长（{@code server --work}，模拟 IO 等待，
	 *                  不占 CPU、占着业务线程）；0 = 原样立即返回
	 */
	BenchServiceImpl(Map<String, String> environment, Supplier<Map<String, String>> extraStats, Supplier<Map<String, String>> counters,
			long workNanos) {
		this(environment, extraStats, counters, workNanos, WorkMode.PARK);
	}

	/** @param workMode 等待方式（{@code server --work-mode}），见 {@link WorkMode}；null = {@link WorkMode#PARK}；{@code workNanos = 0} 时不起作用 */
	BenchServiceImpl(Map<String, String> environment, Supplier<Map<String, String>> extraStats, Supplier<Map<String, String>> counters,
			long workNanos, WorkMode workMode) {
		this.environment = new LinkedHashMap<>(environment);
		this.extraStats = extraStats;
		this.counters = counters;
		this.workNanos = workNanos;
		this.workMode = workMode == null ? WorkMode.PARK : workMode;
	}

	/** 本次调用要"干活"的时长：parkNanos / wait 可能提前返回（虚假唤醒），循环到截止时刻为止。 */
	void work() {
		if (workNanos <= 0) {
			return;
		}
		long deadline = System.nanoTime() + workNanos;
		switch (workMode) {
			case PARK -> parkUntil(deadline);
			case SYNC_PARK -> {
				Object lock = new Object();
				escapedLock = lock;
				synchronized (lock) {
					parkUntil(deadline);
				}
			}
			case MONITOR -> {
				Object lock = new Object();
				escapedLock = lock;
				waitUntil(lock, deadline);
			}
		}
	}

	private static void parkUntil(long deadline) {
		long remaining;
		while ((remaining = deadline - System.nanoTime()) > 0) {
			LockSupport.parkNanos(remaining);
		}
	}

	/** 在 {@code lock} 的监视器里 {@code wait} 到截止时刻（没有 notify 方：只要"在 synchronized 内等一段时间"）。 */
	private static void waitUntil(Object lock, long deadline) {
		synchronized (lock) {
			long remaining;
			while ((remaining = deadline - System.nanoTime()) > 0) {
				try {
					lock.wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	@Override
	public byte[] echo(byte[] payload) {
		work();
		return payload;
	}

	@Override
	public Order echoOrder(Order order) {
		work();
		return order;
	}

	@Override
	public Map<String, String> counters() {
		return counters.get();
	}

	@Override
	public Map<String, String> environment() {
		return new LinkedHashMap<>(environment);
	}

	@Override
	public Map<String, String> stats() {
		Map<String, String> stats = ServerProbes.stats();
		stats.putAll(extraStats.get());
		return stats;
	}
}
