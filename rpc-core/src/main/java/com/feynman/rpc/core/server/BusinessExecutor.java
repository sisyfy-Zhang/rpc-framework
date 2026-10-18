package com.feynman.rpc.core.server;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.feynman.rpc.core.config.ExecutorConfig;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 服务端业务执行器：按 {@link ExecutorConfig} 创建，统一两种执行模型的"拒绝"语义与观测口径。框架内部类型。
 *
 * <ul>
 *   <li>固定池：{@code threads} 个线程 + 容量 {@code queueCapacity} 的 {@link ArrayBlockingQueue}（0 时为
 *       {@link SynchronousQueue}，即不排队）。线程全忙且队列已满时拒绝。</li>
 *   <li>虚拟线程：每请求一个虚拟线程，{@link Semaphore} 限制同时执行的任务数为 {@code maxConcurrent}；
 *       拿不到许可立即拒绝（不排队：虚拟线程模型下排队没有意义，等待的请求本身就是一个挂起的虚拟线程）。</li>
 * </ul>
 * 拒绝时抛 {@link Rejected}，{@link Rejected#shuttingDown()} 区分"过载"与"已在关闭"，ServerHandler 据此回
 * SERVER_OVERLOADED 并在消息里写明原因。
 *
 * <p>{@link #running()} 是"正在业务线程上执行的任务数"（不含排队、不含业务方法返回之后仍未完成的 future），
 * 两种模型同一口径：服务端异步到底的效果正是靠它观测——业务方法返回未完成 future 后它应立即回落。
 */
final class BusinessExecutor {

	/** 执行器拒绝了任务。 */
	static final class Rejected extends RejectedExecutionException {
		private static final long serialVersionUID = 1L;
		private final boolean shuttingDown;

		Rejected(String message, boolean shuttingDown) {
			super(message);
			this.shuttingDown = shuttingDown;
		}

		boolean shuttingDown() {
			return shuttingDown;
		}
	}

	private final ExecutorService delegate;
	private final Semaphore permits;
	private final String description;
	private final AtomicInteger running = new AtomicInteger();

	BusinessExecutor(ExecutorConfig config) {
		switch (config) {
			case ExecutorConfig.Fixed fixed -> {
				BlockingQueue<Runnable> queue = fixed.queueCapacity() > 0
						? new ArrayBlockingQueue<>(fixed.queueCapacity())
						: new SynchronousQueue<>();
				this.delegate = new ThreadPoolExecutor(fixed.threads(), fixed.threads(), 0L, TimeUnit.MILLISECONDS, queue,
						new DefaultThreadFactory("rpc-server-biz"), new ThreadPoolExecutor.AbortPolicy());
				this.permits = null;
				this.description = "fixed(threads=" + fixed.threads() + ", queueCapacity=" + fixed.queueCapacity() + ")";
			}
			case ExecutorConfig.VirtualThreads virtual -> {
				this.delegate = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rpc-server-biz-", 0).factory());
				this.permits = new Semaphore(virtual.maxConcurrent());
				this.description = "virtualThreads(maxConcurrent=" + virtual.maxConcurrent() + ")";
			}
		}
	}

	/**
	 * 提交一个请求处理任务。
	 *
	 * @throws Rejected 过载（队列满 / 并发达到上限）或执行器已关闭
	 */
	void execute(Runnable task) {
		if (delegate.isShutdown()) {
			throw new Rejected("server shutting down: business executor is closed", true);
		}
		if (permits != null && !permits.tryAcquire()) {
			throw new Rejected("server overloaded: business executor " + description + " is saturated", false);
		}
		try {
			delegate.execute(new Tracked(task));
		} catch (RejectedExecutionException e) {
			if (permits != null) {
				permits.release();
			}
			boolean shuttingDown = delegate.isShutdown();
			throw new Rejected(shuttingDown ? "server shutting down: business executor is closed"
					: "server overloaded: business executor " + description + " is saturated", shuttingDown);
		}
	}

	/** 正在业务线程上执行的任务数（不含排队中的）。 */
	int running() {
		return running.get();
	}

	/** 排队中、尚未开始执行的任务数（虚拟线程模型恒为 0）。 */
	int queued() {
		return delegate instanceof ThreadPoolExecutor pool ? pool.getQueue().size() : 0;
	}

	String description() {
		return description;
	}

	void shutdown() {
		delegate.shutdown();
	}

	/** 中断执行中的任务，返回排队中、不会再执行的任务（已解包成提交时的原始任务）。 */
	List<Runnable> shutdownNow() {
		return delegate.shutdownNow().stream().map(r -> r instanceof Tracked tracked ? tracked.task : r).toList();
	}

	boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return delegate.awaitTermination(timeout, unit);
	}

	/** 维护 running 计数并归还虚拟线程许可的包装。 */
	private final class Tracked implements Runnable {
		private final Runnable task;

		Tracked(Runnable task) {
			this.task = task;
		}

		@Override
		public void run() {
			running.incrementAndGet();
			try {
				task.run();
			} finally {
				running.decrementAndGet();
				if (permits != null) {
					permits.release();
				}
			}
		}
	}
}
