package com.feynman.rpc.core.cluster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.config.OutlierDetectionConfig;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 一个服务（{@code ServiceKey}）在本客户端视角下的离群摘除：按提供者地址统计最近 {@value OutlierDetectionConfig#WINDOW_SECONDS} 秒的
 * 尝试与"坏结果"，满足条件时把该地址从候选集里摘掉一段时间，到期自动放回。规则与缺省值见 {@link OutlierDetectionConfig}。
 *
 * <p><b>为什么需要它</b>：节点"活着但不回包"（连接静默挂起、进程存活）时注册中心不会摘它，框架又不重试超时（deadline 是共享预算），
 * 没有离群摘除时这个节点会一直分到 1/n 的首次尝试、每个都等到超时（docs/probes/fault-injection.md 的 toxic-timeout）。
 * 进程被 kill -9 时注册中心要等会话超时（ZooKeeper 缺省 30s）才摘，failfast 下这段时间 1/n 的请求失败。
 *
 * <p><b>倍数衰减</b>：摘除时长 = baseEjection × 累计被摘次数；某地址放回之后没有再被摘、满一个 maxEjection，累计次数减 1（最低 1），
 * 每再满一个 maxEjection 再减 1——偶发一次的节点不会永远背着上限时长。衰减在下一次摘除（与观测）时按经过的时间一次算清，不需要定时器。
 *
 * <p><b>刻意不做的</b>：半开探测（摘除到期直接放回，Envoy 式时间驱动；放回后若仍坏，几个坏结果之后再摘，时长倍增）、
 * 主动健康检查、按延迟摘除（慢节点交给 P2C 按在途数降份额，见 fault-injection.md 的慢节点组）。
 *
 * <p><b>开销</b>：调用热路径上 {@link #filter} 只读一次 volatile（没有节点处于摘除期时原样返回列表，不分配）；
 * {@link #onSuccess} 是一次 Map 查找、一次桶计数（CAS）与一次连续计数的读；{@link #onFailure} 另外汇总窗口、在满足条件时
 * 进入摘除判断（加锁，罕见路径）。桶是"秒序号 &lt;&lt; 32 | 计数"打包的 long，换秒与计数在同一次 CAS 里完成，没有锁、不丢计数。
 *
 * <p>线程安全：计数无锁；摘除 / 放回 / 清理在 {@code lock} 内进行（只在状态变化时进入）。注册中心推送移除某地址时
 * {@link #retain} 清掉它的全部状态（RpcClient 挂在 ServiceDirectory 的变更回调上）。
 */
public final class OutlierDetector {

	private static final Logger log = LoggerFactory.getLogger(OutlierDetector.class);
	private static final int BUCKETS = OutlierDetectionConfig.WINDOW_SECONDS;
	private static final long SECOND_NANOS = 1_000_000_000L;
	private static final long COUNT_MASK = 0xFFFF_FFFFL;

	/** 坏结果的种类（只用于日志里的"最后一次坏结果"）。 */
	public static final String TIMEOUT = "timeout";
	public static final String DEADLINE_EXCEEDED = "deadline_exceeded";
	public static final String TRANSPORT = "transport";
	public static final String OVERLOADED = "overloaded";

	private final OutlierDetectionConfig config;
	private final String scope;
	private final Supplier<List<ServiceInstance>> population;
	private final Stats stats;
	private final LongSupplier clock;
	private final long origin;
	private final long baseNanos;
	private final long maxNanos;
	private final Map<String, Node> nodes = new ConcurrentHashMap<>();
	/** 处于摘除期的节点数（含已到期、尚未放回的）。0 时 {@link #filter} 原样返回——热路径上唯一的 volatile 读。只在 lock 内写。 */
	private volatile int ejected;
	private final Object lock = new Object();

	/** 一个客户端内全部服务共用的累计计数（{@code RpcClient.outlierEjections()} 等）。 */
	public static final class Stats {
		private final LongAdder ejections = new LongAdder();
		private final LongAdder restores = new LongAdder();
		private final LongAdder allEjectedFallbacks = new LongAdder();

		/** 累计摘除次数。 */
		public long ejections() {
			return ejections.sum();
		}

		/** 累计放回次数（到期后第一次选点或观测时放回；推送移除不算）。 */
		public long restores() {
			return restores.sum();
		}

		/** 候选全部处于摘除期、退回全部候选的选点次数。 */
		public long allEjectedFallbacks() {
			return allEjectedFallbacks.sum();
		}
	}

	/**
	 * @param scope      日志里的服务名
	 * @param population 该服务当前的提供者列表（路由视图），用于最大摘除比例的 n 与"地址是否仍在列表里"
	 * @param stats      累计计数的去处（可多个检测器共用）
	 */
	public OutlierDetector(OutlierDetectionConfig config, String scope, Supplier<List<ServiceInstance>> population, Stats stats) {
		this(config, scope, population, stats, System::nanoTime);
	}

	/** @param clock 单调时钟（纳秒），测试用来驱动时间 */
	public OutlierDetector(OutlierDetectionConfig config, String scope, Supplier<List<ServiceInstance>> population, Stats stats,
			LongSupplier clock) {
		this.config = config;
		this.scope = scope;
		this.population = population;
		this.stats = stats;
		this.clock = clock;
		this.origin = clock.getAsLong();
		this.baseNanos = config.baseEjection().toNanos();
		this.maxNanos = config.maxEjection().toNanos();
	}

	public OutlierDetectionConfig config() {
		return config;
	}

	// ---------------------------------------------------------------- 选点路径

	/**
	 * 从候选里去掉处于摘除期的地址（到期的顺带放回）。没有节点处于摘除期时原样返回 {@code candidates}。
	 * 去掉之后一个都不剩（而 candidates 非空）时返回 {@code candidates} 本身，并计一次 {@link Stats#allEjectedFallbacks()}。
	 */
	public List<ServiceInstance> filter(List<ServiceInstance> candidates) {
		if (ejected == 0) {
			return candidates;
		}
		return filterSlow(candidates);
	}

	private List<ServiceInstance> filterSlow(List<ServiceInstance> candidates) {
		long now = clock.getAsLong();
		List<ServiceInstance> healthy = null;
		int size = candidates.size();
		for (int i = 0; i < size; i++) {
			ServiceInstance instance = candidates.get(i);
			Node node = nodes.get(instance.address());
			boolean skip = node != null && node.ejected && !restoreIfExpired(node, now);
			if (skip) {
				if (healthy == null) {
					healthy = new ArrayList<>(size);
					for (int j = 0; j < i; j++) {
						healthy.add(candidates.get(j));
					}
				}
			} else if (healthy != null) {
				healthy.add(instance);
			}
		}
		if (healthy == null) {
			return candidates;
		}
		if (healthy.isEmpty() && size > 0) {
			stats.allEjectedFallbacks.increment();
			return candidates;
		}
		return healthy;
	}

	// ---------------------------------------------------------------- 结果

	/** 一次没有坏结果的尝试（成功、业务异常、其它服务端错误状态）：计尝试，连续坏结果清零。 */
	public void onSuccess(String address) {
		Node node = node(address);
		if (node == null) {
			return;
		}
		increment(node.attempts, second());
		if (node.consecutive.get() != 0) {
			node.consecutive.set(0);
		}
	}

	/**
	 * 一次坏结果（超时、DEADLINE_EXCEEDED、传输失败、SERVER_OVERLOADED）：计尝试与坏结果，满足条件时尝试摘除。
	 *
	 * @param kind {@link #TIMEOUT} / {@link #DEADLINE_EXCEEDED} / {@link #TRANSPORT} / {@link #OVERLOADED}（只用于日志）
	 */
	public void onFailure(String address, String kind) {
		Node node = node(address);
		if (node == null) {
			return;
		}
		long s = second();
		increment(node.attempts, s);
		increment(node.failures, s);
		int consecutive = node.consecutive.incrementAndGet();
		if (node.ejected) {
			return;
		}
		String reason = null;
		if (config.consecutiveFailures() > 0 && consecutive >= config.consecutiveFailures()) {
			reason = consecutive + " consecutive failures (last: " + kind + ")";
		} else {
			long attempts = sum(node.attempts, s);
			if (attempts >= config.minRequests()) {
				long failures = sum(node.failures, s);
				if (failures >= config.failureRatio() * attempts) {
					reason = failures + "/" + attempts + " attempts failed in the last " + BUCKETS + "s (ratio >= " + config.failureRatio()
							+ ", last: " + kind + ")";
				}
			}
		}
		if (reason != null) {
			tryEject(node, reason);
		}
	}

	private Node node(String address) {
		Node node = nodes.get(address);
		if (node != null) {
			return node;
		}
		// 只为当前列表里的地址建状态：已被推送移除的地址上迟到的完成不再建出新状态（retain 清过的不会复活）
		if (!contains(population.get(), address)) {
			return null;
		}
		return nodes.computeIfAbsent(address, Node::new);
	}

	private static boolean contains(List<ServiceInstance> instances, String address) {
		for (ServiceInstance instance : instances) {
			if (instance.address().equals(address)) {
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- 摘除 / 放回

	private void tryEject(Node node, String reason) {
		synchronized (lock) {
			if (node.ejected || nodes.get(node.address) != node) {
				return;
			}
			long now = clock.getAsLong();
			int n = 0;
			int ejectedNow = 0;
			boolean listed = false;
			Set<String> seen = new HashSet<>();
			for (ServiceInstance instance : population.get()) {
				if (!seen.add(instance.address())) {
					continue;
				}
				n++;
				Node other = nodes.get(instance.address());
				if (other == node) {
					listed = true;
				} else if (other != null && other.ejected && !restoreIfExpiredLocked(other, now)) {
					ejectedNow++;
				}
			}
			if (!listed) {
				return;
			}
			int minHealthy = Math.max(1, Math.ceilDiv(n * (100 - config.maxEjectionPercent()), 100));
			if (n - ejectedNow - 1 < minHealthy) {
				if (log.isDebugEnabled()) {
					log.debug("Outlier {} of {} not ejected: {} of {} provider(s) already ejected, maxEjectionPercent {} keeps at least {} ({})",
							node.address, scope, ejectedNow, n, config.maxEjectionPercent(), minHealthy, reason);
				}
				return;
			}
			decayLocked(node, now);
			node.ejections++;
			long duration = baseNanos > maxNanos / node.ejections ? maxNanos : Math.min(maxNanos, baseNanos * node.ejections);
			node.ejectedUntil = now + duration;
			node.ejected = true;
			ejected = ejected + 1;
			stats.ejections.increment();
			log.warn("Outlier ejected {} of {} for {}ms (ejection #{}): {}", node.address, scope, duration / 1_000_000, node.ejections, reason);
		}
	}

	/** 到期则放回并返回 true；未到期返回 false。 */
	private boolean restoreIfExpired(Node node, long now) {
		if (now - node.ejectedUntil < 0) {
			return false;
		}
		synchronized (lock) {
			return restoreIfExpiredLocked(node, now);
		}
	}

	/**
	 * 倍数衰减（锁内）：放回之后每连续健康（没有再被摘）满一个 maxEjection，累计被摘次数减 1，最低 1；起点随之前移，
	 * 不足一个 maxEjection 的零头留到下次。摘除期内不衰减。
	 */
	private void decayLocked(Node node, long now) {
		if (node.ejected || node.ejections <= 1) {
			return;
		}
		long healthy = now - node.healthySince;
		if (healthy < maxNanos) {
			return;
		}
		long steps = healthy / maxNanos;
		node.ejections = (int) Math.max(1, node.ejections - steps);
		node.healthySince += steps * maxNanos;
	}

	private boolean restoreIfExpiredLocked(Node node, long now) {
		if (!node.ejected) {
			return true;
		}
		if (now - node.ejectedUntil < 0) {
			return false;
		}
		node.ejected = false;
		node.consecutive.set(0);
		node.healthySince = now;
		ejected = ejected - 1;
		stats.restores.increment();
		log.info("Outlier {} of {} restored after ejection #{}", node.address, scope, node.ejections);
		return true;
	}

	/** 放回全部已到期的节点（观测路径用，读数不受"最近没有调用、没人触发放回"影响）。 */
	private void restoreExpired() {
		if (ejected == 0) {
			return;
		}
		long now = clock.getAsLong();
		synchronized (lock) {
			for (Node node : nodes.values()) {
				restoreIfExpiredLocked(node, now);
			}
		}
	}

	/**
	 * 注册中心推送之后：只保留 {@code current} 里的地址，其余地址的全部状态（窗口、连续计数、摘除期与累计被摘次数）清掉。
	 * 清掉处于摘除期的地址不计放回。
	 */
	public void retain(Collection<ServiceInstance> current) {
		Set<String> keep = new HashSet<>();
		for (ServiceInstance instance : current) {
			keep.add(instance.address());
		}
		synchronized (lock) {
			nodes.values().removeIf(node -> {
				if (keep.contains(node.address)) {
					return false;
				}
				if (node.ejected) {
					node.ejected = false;
					ejected = ejected - 1;
				}
				return true;
			});
		}
	}

	// ---------------------------------------------------------------- 观测

	/** 当前处于摘除期的地址 → 到期时刻（墙钟换算，按地址排序）。已到期的先放回，不在其中。 */
	public Map<String, Instant> ejectedNodes() {
		restoreExpired();
		Map<String, Instant> out = new TreeMap<>();
		if (ejected == 0) {
			return out;
		}
		long now = clock.getAsLong();
		Instant wall = Instant.now();
		for (Node node : nodes.values()) {
			if (node.ejected) {
				out.put(node.address, wall.plusNanos(Math.max(0, node.ejectedUntil - now)));
			}
		}
		return out;
	}

	/** 当前处于摘除期的地址数（已到期的先放回）。 */
	public int ejectedCount() {
		restoreExpired();
		return ejected;
	}

	/**
	 * 某地址的状态快照（测试与诊断用）：窗口内的 {@code attempts} / {@code failures}、{@code consecutive}、累计被摘次数 {@code ejections}；
	 * 没有状态时为 null。
	 */
	public Map<String, Long> snapshot(String address) {
		Node node = nodes.get(address);
		if (node == null) {
			return null;
		}
		long s = second();
		Map<String, Long> out = new LinkedHashMap<>();
		out.put("attempts", sum(node.attempts, s));
		out.put("failures", sum(node.failures, s));
		out.put("consecutive", (long) node.consecutive.get());
		synchronized (lock) {
			decayLocked(node, clock.getAsLong());
			out.put("ejections", (long) node.ejections);
		}
		return out;
	}

	// ---------------------------------------------------------------- 桶

	private long second() {
		return (clock.getAsLong() - origin) / SECOND_NANOS;
	}

	/** 第 {@code s} 秒的桶加一：桶里存的秒序号比 s 旧（上一圈）时在同一次 CAS 里换成 (s, 1)；比 s 还新时丢弃这次计数。 */
	static void increment(AtomicLongArray buckets, long s) {
		int index = (int) (s % BUCKETS);
		for (;;) {
			long v = buckets.get(index);
			long epoch = v >>> 32;
			long next;
			if (epoch == s) {
				next = v + 1;
			} else if (epoch < s) {
				next = (s << 32) | 1;
			} else {
				return;
			}
			if (buckets.compareAndSet(index, v, next)) {
				return;
			}
		}
	}

	/** 窗口 (s − {@value OutlierDetectionConfig#WINDOW_SECONDS}, s] 内的计数之和。 */
	static long sum(AtomicLongArray buckets, long s) {
		long total = 0;
		for (int i = 0; i < BUCKETS; i++) {
			long v = buckets.get(i);
			long epoch = v >>> 32;
			if (epoch <= s && s - epoch < BUCKETS) {
				total += v & COUNT_MASK;
			}
		}
		return total;
	}

	private static final class Node {
		final String address;
		final AtomicLongArray attempts = new AtomicLongArray(BUCKETS);
		final AtomicLongArray failures = new AtomicLongArray(BUCKETS);
		final AtomicInteger consecutive = new AtomicInteger();
		/** 摘除期内为 true；只在 lock 内写。 */
		volatile boolean ejected;
		/** 摘除到期时刻（clock 纳秒）；只在 lock 内写。 */
		volatile long ejectedUntil;
		/** 累计被摘次数，决定下一次摘除的时长；只在 lock 内读写。 */
		int ejections;
		/** 最近一次放回的时刻（clock 纳秒），倍数衰减的起点；只在 lock 内读写。 */
		long healthySince;

		Node(String address) {
			this.address = address;
		}
	}
}
