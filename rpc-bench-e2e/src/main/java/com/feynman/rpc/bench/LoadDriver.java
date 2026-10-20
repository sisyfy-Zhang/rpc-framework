package com.feynman.rpc.bench;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跑一轮负载，把每次调用的结果交给 {@link RoundRecorder}。
 *
 * <ul>
 *   <li><b>闭环</b>：{@code concurrency} 个虚拟线程，各自循环"调用 → 返回 → 立即下一次"直到 duration；
 *       延迟 = 调用前 nanoTime 到返回。服务变慢时发压随之变慢，所以闭环会低估尾延迟（报告里自动附声明）。</li>
 *   <li><b>开环</b>：{@link OpenLoopScheduler} 按 rate 等间隔产生计划发出时刻，到点把计划时刻交给执行者
 *       （{@link HarnessOptions#openWorkers()}：每个请求一个新虚拟线程，或固定的 {@link OpenLoopWorkers} 工作者池），
 *       执行者调用同步代理方法（与闭环走同一个远程方法、同一条客户端代码路径——RpcClient 的同步调用本身就是异步 future 上的等待，
 *       不需要为开环另造一个返回 CompletableFuture 的接口、让服务端多出一个方法）。
 *       延迟 = 完成时刻 − <b>计划</b>发出时刻（修正 coordinated omission）；另记"实际发出（执行者开始执行）→ 完成"的服务时间。
 *       在途数达到 {@code rate × timeout × 2}（或工作者池的队列满）时不再发出，计为 {@code backlog_dropped}，本轮无效。
 *       调度线程名 {@value ThreadAffinity#DISPATCHER_THREAD_NAME}；{@code --pin-dispatcher} 时在本轮计时开始前把它单独钉到一个核
 *       （{@link ThreadAffinity}）。</li>
 * </ul>
 * 每轮结束后等全部在途调用返回（它们最晚在 timeout 后返回）才算本轮结束。
 */
final class LoadDriver {

	private LoadDriver() {
	}

	static RoundRecorder runRound(int round, Profile profile, CallTarget target) {
		return runRound(round, profile, target, null, HarnessOptions.DEFAULTS);
	}

	static RoundRecorder runRound(int round, Profile profile, CallTarget target, CpuSampler cpu) {
		return runRound(round, profile, target, cpu, HarnessOptions.DEFAULTS);
	}

	/**
	 * @param cpu 非 null 时在本轮统计窗口的开始与结束各取一次 {@code /proc/stat} 快照，结果进 {@code rounds[].cpu}；
	 *            null 时记 n/a（非 Linux 或拿不到核列表）。GC 不论 cpu 是否为 null 都在同样两个时刻采样
	 *            （本进程的 GC MXBean + 服务端的 {@link CallTarget#serverStats()}），差值进 {@code rounds[].gc}。
	 *            采样在一个只睡眠的虚拟线程里做，不占发压线程。
	 * @param options 开环的执行者与调度线程绑核（{@link HarnessOptions}）
	 */
	static RoundRecorder runRound(int round, Profile profile, CallTarget target, CpuSampler cpu, HarnessOptions options) {
		if (profile.load().closed()) {
			RoundRecorder recorder = new RoundRecorder(round, profile);
			Thread sampler = startSampler(cpu, target, profile, recorder);
			closedLoop(profile, target, recorder);
			return finish(recorder, sampler);
		}
		return openLoop(round, profile, target, cpu, options, true);
	}

	/**
	 * 第 1 轮之前的预热段（{@code --pre-warmup}）：以卡片同样的负载跑 {@code length}，不计入任何一轮。用一个临时的
	 * "整段都是窗口"的记录器统计它，摘要进 {@code run.pre_warmup}（只作观察：看冷 JVM 的第一段时间里发生了什么）。
	 */
	static Map<String, Object> preWarmup(Profile profile, CallTarget target, HarnessOptions options, Duration length) {
		Profile.Load l = profile.load();
		Profile.Load load = new Profile.Load(l.model(), l.concurrency(), l.rate(), length, Duration.ZERO, Duration.ZERO, length, 1,
				l.timeout());
		Profile segment = new Profile(profile.id(), profile.question(), profile.target(), load, profile.isolation(), profile.measure(),
				List.of(), profile.rawYaml());
		RoundRecorder recorder;
		if (load.closed()) {
			recorder = new RoundRecorder(0, segment);
			closedLoop(segment, target, recorder);
			recorder.finish();
		} else {
			recorder = openLoop(0, segment, target, null, options, false);
		}
		Map<String, Object> round = recorder.toJson();
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("duration_ms", Durations.millis(length));
		summary.put("issued", round.get("issued"));
		summary.put("successes", round.get("successes_total"));
		summary.put("errors", round.get("errors_total"));
		summary.put("errors_by_type", round.get("errors_total_by_type"));
		summary.put("latency_ms", round.get("latency_ms"));
		summary.put("service_time_ms", round.get("service_time_ms"));
		summary.put("dispatch_lag_ms", round.get("dispatch_lag_ms"));
		summary.put("backlog_dropped", round.get("backlog_dropped"));
		summary.put("scheduler", round.get("scheduler"));
		return summary;
	}

	private static Thread startSampler(CpuSampler cpu, CallTarget target, Profile profile, RoundRecorder recorder) {
		return Thread.ofVirtual().name("bench-window-sampler").start(() -> sampleWindow(cpu, target, profile, recorder));
	}

	private static RoundRecorder finish(RoundRecorder recorder, Thread sampler) {
		recorder.finish();
		if (sampler != null) {
			try {
				// 窗口结束 ≤ duration ≤ 本轮结束，此时采样线程最多还差读一次文件、取一次服务端统计
				sampler.join(5_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			sampler.interrupt();
		}
		return recorder;
	}

	/**
	 * 窗口起止各采一次：先 CPU（读文件，最便宜、对时刻最敏感）、再本进程 GC 与系统调用 / 上下文切换（{@link IoSampler}）、
	 * 再服务端统计（一次 RPC / HTTP 调用，GC 与 io 同一份）。互不影响：任何一项失败只把那一项记为 unknown。
	 */
	private static void sampleWindow(CpuSampler cpu, CallTarget target, Profile profile, RoundRecorder recorder) {
		long windowMs = profile.load().window().toMillis();
		try {
			long windowStart = recorder.startNanos + profile.load().windowStart().toNanos();
			long windowEnd = recorder.startNanos + profile.load().windowEnd().toNanos();
			sleepUntil(windowStart);
			Map<Integer, long[]> cpuBefore = cpu == null ? null : cpuSnapshot();
			GcSampler.Snapshot clientBefore = GcSampler.snapshot();
			IoSampler.Snapshot clientIoBefore = IoSampler.snapshot();
			Map<String, String> serverBefore = serverStats(target);
			sleepUntil(windowEnd);
			Map<Integer, long[]> cpuAfter = cpu == null ? null : cpuSnapshot();
			GcSampler.Snapshot clientAfter = GcSampler.snapshot();
			IoSampler.Snapshot clientIoAfter = IoSampler.snapshot();
			Map<String, String> serverAfter = serverBefore == null ? null : serverStats(target);
			if (cpu != null) {
				recorder.cpu(cpuBefore == null || cpuAfter == null ? EnvironmentProbe.UNKNOWN : cpu.measure(cpuBefore, cpuAfter, windowMs));
			}
			GcSampler.Snapshot serverGcBefore = gcOf(serverBefore);
			GcSampler.Snapshot serverGcAfter = gcOf(serverAfter);
			Map<String, Object> gc = new LinkedHashMap<>();
			gc.put("client", GcSampler.delta(clientBefore, clientAfter, windowMs));
			gc.put("server", serverGcBefore == null || serverGcAfter == null ? EnvironmentProbe.UNKNOWN
					: GcSampler.delta(serverGcBefore, serverGcAfter, windowMs));
			recorder.gc(gc);
			Map<String, Object> io = new LinkedHashMap<>();
			io.put("source", IoSampler.SOURCE);
			io.put("client", clientIoBefore == null || clientIoAfter == null ? EnvironmentProbe.NA : IoSampler.delta(clientIoBefore, clientIoAfter));
			io.put("server", ioSide(serverBefore, serverAfter));
			recorder.io(io);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			if (cpu != null && recorder.cpu() == EnvironmentProbe.NA) {
				recorder.cpu(EnvironmentProbe.UNKNOWN);
			}
		}
	}

	private static Map<Integer, long[]> cpuSnapshot() {
		try {
			return CpuSampler.snapshot();
		} catch (Exception e) {
			return null;
		}
	}

	/** 服务端统计（{@code BenchService.stats()} / {@code GET /stats}）；取不到（旧服务端、外部目标）时 null，记 unknown。 */
	private static Map<String, String> serverStats(CallTarget target) {
		try {
			return target.serverStats();
		} catch (Exception e) {
			return null;
		}
	}

	private static GcSampler.Snapshot gcOf(Map<String, String> stats) {
		try {
			return stats == null ? null : GcSampler.Snapshot.fromStrings(stats);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 服务端 io 差值：统计取不到记 unknown；取到了但没有 io 字段（非 Linux 服务端、旧服务端）记 n/a。 */
	private static Object ioSide(Map<String, String> before, Map<String, String> after) {
		if (before == null || after == null) {
			return EnvironmentProbe.UNKNOWN;
		}
		IoSampler.Snapshot b = IoSampler.Snapshot.fromStrings(before);
		IoSampler.Snapshot a = IoSampler.Snapshot.fromStrings(after);
		return a == null || b == null ? EnvironmentProbe.NA : IoSampler.delta(b, a);
	}

	private static void sleepUntil(long nanoTime) throws InterruptedException {
		long remaining;
		while ((remaining = nanoTime - System.nanoTime()) > 0) {
			java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
		}
	}

	private static void closedLoop(Profile profile, CallTarget target, RoundRecorder recorder) {
		long end = recorder.startNanos + profile.load().duration().toNanos();
		int concurrency = profile.load().concurrency();
		try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < concurrency; i++) {
				users.execute(() -> {
					while (System.nanoTime() - end < 0) {
						recorder.issued();
						long start = System.nanoTime();
						try {
							target.call();
							recorder.success(start, start, System.nanoTime());
						} catch (Throwable e) {
							recorder.error(ErrorClassifier.classify(e), System.nanoTime());
						}
					}
				});
			}
		} // close() 等全部虚拟用户退出
	}

	/** 一次调用：执行者（新虚拟线程或池里的工作者）在 {@code actual} 时刻开始执行计划时刻为 {@code scheduled} 的请求。 */
	private static void execute(CallTarget target, RoundRecorder recorder, AtomicLong inFlight, long scheduled, long actual) {
		try {
			target.call();
			recorder.success(scheduled, actual, System.nanoTime());
		} catch (Throwable e) {
			recorder.error(ErrorClassifier.classify(e), System.nanoTime());
		} finally {
			inFlight.decrementAndGet();
		}
	}

	/**
	 * 开环一轮。调度线程先起来、在门闩上等着；需要绑核时此刻钉好（{@code taskset} 要 fork，不能占本轮的时间线），
	 * 然后才建记录器（本轮计时从这里开始）、放行调度线程。
	 *
	 * @param sample 是否做统计窗口的 CPU / GC 采样（预热段不做）
	 */
	private static RoundRecorder openLoop(int round, Profile profile, CallTarget target, CpuSampler cpu, HarnessOptions options,
			boolean sample) {
		Profile.Load load = profile.load();
		// 开环调度器在本轮计时开始之前构造：构造时的 park 校准不占用本轮的时间线
		OpenLoopScheduler scheduler = new OpenLoopScheduler(load.rate());
		long cap = load.inflightCap();
		AtomicLong inFlight = new AtomicLong();
		AtomicLong maxInFlight = new AtomicLong();
		AtomicReference<Throwable> pacerFailure = new AtomicReference<>();
		AtomicReference<RoundRecorder> recorderRef = new AtomicReference<>();
		CountDownLatch go = new CountDownLatch(1);
		// 调度线程进入 run 体时 JVM 已把线程名写进 /proc 的 comm（钉核按名字找它）
		CountDownLatch ready = new CountDownLatch(1);
		boolean pool = options.pool(profile);
		int poolSize = options.effectivePoolSize(profile);
		// 工作者池的队列容量 = 在途上限：在途（排队 + 执行中）先触顶，队列满只是同一件事的另一道保险
		int queueCapacity = (int) Math.min(Integer.MAX_VALUE - poolSize, cap);
		ExecutorService perRequest = pool ? null : Executors.newVirtualThreadPerTaskExecutor();
		OpenLoopWorkers workers = pool ? new OpenLoopWorkers(poolSize, queueCapacity, options.platformWorkers(profile),
				(scheduled, actual) -> execute(target, recorderRef.get(), inFlight, scheduled, actual)) : null;
		Thread pacer = Thread.ofPlatform().name(ThreadAffinity.DISPATCHER_THREAD_NAME).unstarted(() -> {
			try {
				ready.countDown();
				go.await();
				RoundRecorder recorder = recorderRef.get();
				long start = recorder.startNanos;
				long end = start + load.duration().toNanos();
				scheduler.run(start, end, (scheduled, now) -> {
					recorder.dispatchLag(now - scheduled);
					if (inFlight.get() >= cap) {
						recorder.issued();
						recorder.error(RoundRecorder.BACKLOG_DROPPED, now);
						return;
					}
					long current = inFlight.incrementAndGet();
					if (current > maxInFlight.get()) {
						maxInFlight.set(current); // 只有调度线程写，无需 CAS
					}
					recorder.issued();
					if (workers != null) {
						if (!workers.offer(scheduled)) {
							inFlight.decrementAndGet();
							recorder.error(RoundRecorder.BACKLOG_DROPPED, now);
						}
					} else {
						perRequest.execute(() -> execute(target, recorder, inFlight, scheduled, System.nanoTime()));
					}
				});
			} catch (Throwable t) {
				pacerFailure.set(t);
			}
		});
		pacer.setPriority(Thread.MAX_PRIORITY);
		pacer.setDaemon(true);
		pacer.start();
		try {
			ready.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while starting the open-loop scheduler", e);
		}
		Map<String, Object> pin = null;
		long dispatcherTid = -1;
		if (options.pinDispatcher() != null) {
			pin = ThreadAffinity.pin(ThreadAffinity.DISPATCHER_THREAD_NAME, options.pinDispatcher());
			if (pin.get("dispatcher_tid") instanceof Long tid) {
				dispatcherTid = tid;
			}
		}
		RoundRecorder recorder = new RoundRecorder(round, profile);
		recorderRef.set(recorder);
		Thread sampler = sample ? startSampler(cpu, target, profile, recorder) : null;
		go.countDown();
		try {
			pacer.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while running the open-loop schedule", e);
		} finally {
			// 等全部在途调用返回
			if (workers != null) {
				workers.close();
			} else {
				perRequest.close();
			}
		}
		if (pin != null && dispatcherTid > 0) {
			List<String> strays = ThreadAffinity.strays(options.pinDispatcher(), dispatcherTid);
			pin.put("stray_threads_at_end", strays == null ? EnvironmentProbe.NA : strays);
		}
		finish(recorder, sampler);
		if (pacerFailure.get() != null) {
			throw new IllegalStateException("open-loop scheduler failed", pacerFailure.get());
		}
		Map<String, Object> info = scheduler.describe();
		info.put("inflight_cap", cap);
		info.put("max_inflight", maxInFlight.get());
		info.put("workers", options.effectiveOpenWorkers(profile));
		if (pool) {
			info.put("pool_size", poolSize);
			info.put("queue_capacity", queueCapacity);
		}
		info.put("call_style", pool
				? "sync proxy method on one of " + poolSize + (options.platformWorkers(profile) ? " platform" : " virtual") + "-thread workers taking scheduled times from a bounded FIFO queue"
				: "sync proxy method on a new virtual thread per request");
		info.put("dispatcher_thread", ThreadAffinity.DISPATCHER_THREAD_NAME);
		info.put("pin", pin == null ? "off" : pin);
		recorder.schedulerInfo(info);
		return recorder;
	}
}
