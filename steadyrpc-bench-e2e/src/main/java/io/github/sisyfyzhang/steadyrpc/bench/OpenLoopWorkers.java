package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 开环的固定工作者（{@code --open-workers pool}）：N 个虚拟线程从一个有界 FIFO 队列里取计划时刻、各自串行执行调用。
 * 与"每个请求一个新虚拟线程"相比，发出路径上不再有虚拟线程的创建与首次调度，调度线程只做一次入队（唤醒一个
 * 在队列上等待的工作者）。
 *
 * <ul>
 *   <li><b>保序</b>：队列是 FIFO，工作者按入队（= 计划时刻）先后取走；同一时刻有多个空闲工作者时谁先拿到不确定，
 *       但没有哪个计划时刻会被后来的插队。</li>
 *   <li><b>背压</b>：队列容量有界，{@link #offer} 满了返回 false，由驱动计为 {@code backlog_dropped}（与在途上限
 *       {@code rate × timeout × 2} 同一种失败：压测端已经跟不上计划）。</li>
 *   <li><b>收尾</b>：{@link #close} 在队尾放 N 个停止标记再等全部工作者退出，所以已入队的计划时刻全部执行完。</li>
 * </ul>
 * 实际开始时刻（服务时间的起点）= 工作者从队列取到它的时刻，排队等工作者的时间与"每请求新线程"时等连接的时间一样，
 * 计入从计划时刻起算的延迟，不计入服务时间。
 */
final class OpenLoopWorkers implements AutoCloseable {

	/** 一次计划发出；{@link #STOP} 是收尾标记（按引用比较）。 */
	private static final class Planned {
		final long scheduledNanos;

		Planned(long scheduledNanos) {
			this.scheduledNanos = scheduledNanos;
		}
	}

	private static final Planned STOP = new Planned(0);

	/** 工作者对一个计划时刻要做的事：{@code actualNanos} 为工作者取到它的时刻。 */
	@FunctionalInterface
	interface Task {
		void run(long scheduledNanos, long actualNanos);
	}

	private final BlockingQueue<Planned> queue;
	private final List<Thread> workers = new ArrayList<>();
	private final int capacity;

	OpenLoopWorkers(int workerCount, int capacity, Task task) {
		this(workerCount, capacity, false, task);
	}

	/** @param platform 工作者用平台线程（{@code --open-workers pool-platform}），否则虚拟线程 */
	OpenLoopWorkers(int workerCount, int capacity, boolean platform, Task task) {
		if (workerCount < 1 || capacity < 1) {
			throw new IllegalArgumentException("workers and capacity must be >= 1");
		}
		this.capacity = capacity;
		// 容量 + 停止标记的位置：close 时一定放得下
		this.queue = new LinkedBlockingQueue<>(capacity + workerCount);
		for (int i = 0; i < workerCount; i++) {
			Thread.Builder builder = platform ? Thread.ofPlatform().daemon(true) : Thread.ofVirtual();
			workers.add(builder.name("bench-open-worker-" + i).start(() -> work(task)));
		}
	}

	private void work(Task task) {
		for (;;) {
			Planned planned;
			try {
				planned = queue.take();
			} catch (InterruptedException e) {
				return;
			}
			if (planned == STOP) {
				return;
			}
			task.run(planned.scheduledNanos, System.nanoTime());
		}
	}

	/** 入队一个计划时刻；队列已有 {@code capacity} 个待执行时返回 false（不阻塞）。 */
	boolean offer(long scheduledNanos) {
		if (queue.size() >= capacity) {
			return false;
		}
		return queue.offer(new Planned(scheduledNanos));
	}

	int workerCount() {
		return workers.size();
	}

	/** 已入队的全部执行完、工作者全部退出后返回。 */
	@Override
	public void close() {
		for (int i = 0; i < workers.size(); i++) {
			queue.add(STOP);
		}
		boolean interrupted = false;
		for (Thread worker : workers) {
			for (;;) {
				try {
					worker.join();
					break;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
