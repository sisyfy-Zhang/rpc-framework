package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;
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
 *   <li><b>开环</b>：{@link OpenLoopScheduler} 按 rate 等间隔产生计划发出时刻，到点把调用交给一个新的虚拟线程
 *       （同步代理方法，与闭环走同一个远程方法、同一条客户端代码路径——RpcClient 的同步调用本身就是异步 future 上的等待，
 *       不需要为开环另造一个返回 CompletableFuture 的接口、让服务端多出一个方法）。
 *       延迟 = 完成时刻 − <b>计划</b>发出时刻（修正 coordinated omission）；另记"实际发出（虚拟线程开始执行）→ 完成"的服务时间。
 *       在途数达到 {@code rate × timeout × 2} 时不再发出，计为 {@code backlog_dropped}，本轮无效。</li>
 * </ul>
 * 每轮结束后等全部在途调用返回（它们最晚在 timeout 后返回）才算本轮结束。
 */
final class LoadDriver {

	private LoadDriver() {
	}

	static RoundRecorder runRound(int round, Profile profile, CallTarget target) {
		return runRound(round, profile, target, null);
	}

	/**
	 * @param cpu 非 null 时在本轮统计窗口的开始与结束各取一次 {@code /proc/stat} 快照，结果进 {@code rounds[].cpu}；
	 *            null 时记 n/a（非 Linux 或拿不到核列表）。GC 不论 cpu 是否为 null 都在同样两个时刻采样
	 *            （本进程的 GC MXBean + 服务端的 {@link CallTarget#serverStats()}），差值进 {@code rounds[].gc}。
	 *            采样在一个只睡眠的虚拟线程里做，不占发压线程。
	 */
	static RoundRecorder runRound(int round, Profile profile, CallTarget target, CpuSampler cpu) {
		// 开环调度器在本轮计时开始之前构造：构造时的 park 校准不占用本轮的时间线
		OpenLoopScheduler scheduler = profile.load().closed() ? null : new OpenLoopScheduler(profile.load().rate());
		RoundRecorder recorder = new RoundRecorder(round, profile);
		Thread sampler = Thread.ofVirtual().name("bench-window-sampler").start(() -> sampleWindow(cpu, target, profile, recorder));
		if (scheduler == null) {
			closedLoop(profile, target, recorder);
		} else {
			openLoop(profile, target, recorder, scheduler);
		}
		recorder.finish();
		try {
			// 窗口结束 ≤ duration ≤ 本轮结束，此时采样线程最多还差读一次文件、取一次服务端统计
			sampler.join(5_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		sampler.interrupt();
		return recorder;
	}

	/**
	 * 窗口起止各采一次：先 CPU（读文件，最便宜、对时刻最敏感）、再本进程 GC、再服务端统计（一次 RPC / HTTP 调用）。
	 * 三者互不影响：任何一项失败只把那一项记为 unknown。
	 */
	private static void sampleWindow(CpuSampler cpu, CallTarget target, Profile profile, RoundRecorder recorder) {
		long windowMs = profile.load().window().toMillis();
		try {
			long windowStart = recorder.startNanos + profile.load().windowStart().toNanos();
			long windowEnd = recorder.startNanos + profile.load().windowEnd().toNanos();
			sleepUntil(windowStart);
			Map<Integer, long[]> cpuBefore = cpu == null ? null : cpuSnapshot();
			GcSampler.Snapshot clientBefore = GcSampler.snapshot();
			GcSampler.Snapshot serverBefore = serverStats(target);
			sleepUntil(windowEnd);
			Map<Integer, long[]> cpuAfter = cpu == null ? null : cpuSnapshot();
			GcSampler.Snapshot clientAfter = GcSampler.snapshot();
			GcSampler.Snapshot serverAfter = serverBefore == null ? null : serverStats(target);
			if (cpu != null) {
				recorder.cpu(cpuBefore == null || cpuAfter == null ? EnvironmentProbe.UNKNOWN : cpu.measure(cpuBefore, cpuAfter, windowMs));
			}
			Map<String, Object> gc = new LinkedHashMap<>();
			gc.put("client", GcSampler.delta(clientBefore, clientAfter, windowMs));
			gc.put("server", serverAfter == null ? EnvironmentProbe.UNKNOWN : GcSampler.delta(serverBefore, serverAfter, windowMs));
			recorder.gc(gc);
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
	private static GcSampler.Snapshot serverStats(CallTarget target) {
		try {
			return GcSampler.Snapshot.fromStrings(target.serverStats());
		} catch (Exception e) {
			return null;
		}
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

	private static void openLoop(Profile profile, CallTarget target, RoundRecorder recorder, OpenLoopScheduler scheduler) {
		Profile.Load load = profile.load();
		long cap = load.inflightCap();
		AtomicLong inFlight = new AtomicLong();
		AtomicLong maxInFlight = new AtomicLong();
		long start = recorder.startNanos;
		long end = start + load.duration().toNanos();
		AtomicReference<Throwable> pacerFailure = new AtomicReference<>();
		try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
			Thread pacer = Thread.ofPlatform().name("bench-open-loop-scheduler").unstarted(() -> {
				try {
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
						calls.execute(() -> {
							long actual = System.nanoTime();
							try {
								target.call();
								recorder.success(scheduled, actual, System.nanoTime());
							} catch (Throwable e) {
								recorder.error(ErrorClassifier.classify(e), System.nanoTime());
							} finally {
								inFlight.decrementAndGet();
							}
						});
					});
				} catch (Throwable t) {
					pacerFailure.set(t);
				}
			});
			pacer.setPriority(Thread.MAX_PRIORITY);
			pacer.start();
			try {
				pacer.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while running the open-loop schedule", e);
			}
		} // close() 等全部在途调用返回
		if (pacerFailure.get() != null) {
			throw new IllegalStateException("open-loop scheduler failed", pacerFailure.get());
		}
		Map<String, Object> info = scheduler.describe();
		info.put("inflight_cap", cap);
		info.put("max_inflight", maxInFlight.get());
		info.put("call_style", "sync proxy method on a new virtual thread per request");
		recorder.schedulerInfo(info);
	}
}
