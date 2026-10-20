package com.feynman.rpc.core.internal;

import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.internal.PlatformDependent;

/**
 * 每条连接一个的写队列（{@code RpcClientConfig.writeQueue} / {@code RpcServerConfig.writeQueue}）：事件循环外的线程（调用方、
 * 业务线程）要写出的消息先进一个 MPSC 队列，只有把 {@link #scheduled} 从 false 切到 true 的那个线程向事件循环投一次
 * {@link #drain} 任务；drain 在事件循环上把队列取空，逐条交给条目自己的检查（{@link Entry#beforeWrite}）后
 * {@code channel.write}（不 flush），批末一次 flush。与 gRPC {@code WriteQueue}、Dubbo {@code BatchExecutorQueue} 同构。
 * 框架内部类型。
 *
 * <p>省掉的是"每条消息一个 Runnable / WriteTask + 一次事件循环任务队列入队"，以及 FlushConsolidationHandler 为事件循环外来的
 * flush 每批再提交的那个 flush 任务（drain 本身就是一批，批末直接从 FlushConsolidationHandler 之下冲刷）。事件循环的唤醒
 * Netty 自己已经合并（{@code nextWakeupNanos}），这里不再多做。
 *
 * <p><b>分配原则不变</b>：队列里放的是只带备好字节的消息（{@code RpcMessage} + {@code OutboundBody}），帧缓冲区仍由编码器在事件循环上
 * 分配；生产者线程只分配条目对象本身（它同时是这条消息的 promise 与写完成监听器，不再另配 lambda）。
 *
 * <p>单消费者不变式：持有 {@code scheduled == true} 的一方（已投出的 drain 任务、正在运行的 drain、或投递被拒绝的那个生产者）
 * 是队列唯一的消费者。drain 取空后先置 false 再查一次队列，非空且 CAS 成功就<b>在同一个任务里</b>接着取（不重新投递），
 * 这样已入队的消息总在"入队之后才投进同一事件循环的任务"（例如停机时的关连接任务）之前写出；单次 drain 超过
 * {@link #MAX_PER_DRAIN} 条才让出事件循环、重新投递自己（只在单连接上积压上千条时发生）。
 */
public final class ConnectionWriteQueue {

	private static final Logger log = LoggerFactory.getLogger(ConnectionWriteQueue.class);

	/** 批内每写出这么多条 flush 一次，不让一大批全堆在出站缓冲区里才开始发（与 gRPC WriteQueue 的 128 相同）。 */
	static final int FLUSH_EVERY = 128;
	/** 单个 drain 任务最多写出的条数，之后让出事件循环（IO 与其它任务），重新投递自己。 */
	static final int MAX_PER_DRAIN = 1024;

	/**
	 * 一条待写出的消息，同时是它的 {@link io.netty.channel.ChannelPromise} 与写完成监听器（一个对象，不另配 lambda）。
	 * 子类决定排队期间失效的条目怎么处理、写失败怎么报告。
	 */
	public abstract static class Entry extends DefaultChannelPromise implements ChannelFutureListener {
		private final Object message;

		protected Entry(Channel channel, Object message) {
			super(channel);
			this.message = message;
		}

		/**
		 * 事件循环上、写出之前调用：返回 false 则不写出（条目自己已经处理了结果，例如请求已超时、连接已断）。
		 * 不应抛异常；抛出时按写失败处理（{@link #tryFailure}）。
		 */
		protected abstract boolean beforeWrite(Channel channel);

		/** 写出完成（成功或失败），事件循环上调用。 */
		protected abstract void written(ChannelFuture future);

		/**
		 * 条目不会被写出：事件循环已关闭、drain 投递被拒绝。在投递被拒绝的那个生产者线程上调用，不经 promise
		 * （事件循环已关，promise 的监听器通知无处执行）。
		 */
		protected abstract void notWritten(RejectedExecutionException cause);

		@Override
		public final void operationComplete(ChannelFuture future) {
			written(future);
		}

		final Object message() {
			return message;
		}
	}

	private final Channel channel;
	private final EventLoop eventLoop;
	private final Queue<Entry> queue = PlatformDependent.newMpscQueue();
	private final AtomicBoolean scheduled = new AtomicBoolean();
	/** 一个连接只建一次的 drain 任务（每批投递的是同一个对象）。 */
	private final Runnable drainTask = this::drain;
	/**
	 * 批末从这里 flush：pipeline 里有 FlushConsolidationHandler 时取它自己的 context（{@code ctx.flush()} 从它往 head 走，
	 * 不再经它提交 flush 任务），没有时为 null（用 {@code channel.flush()}）。首次 drain 时在事件循环上解析。
	 */
	private ChannelHandlerContext flushContext;
	private boolean flushContextResolved;

	public ConnectionWriteQueue(Channel channel) {
		this.channel = channel;
		this.eventLoop = channel.eventLoop();
	}

	/** 当前线程是否就是这条连接的事件循环（是的话调用方应直接 {@link #writeNow}，不入队）。 */
	public boolean inEventLoop() {
		return eventLoop.inEventLoop();
	}

	/**
	 * 事件循环上直接写出一条（不入队，即原有路径）：检查后 {@code writeAndFlush}——读循环里的 flush 仍由 FlushConsolidationHandler
	 * 推迟到本轮读完。只能在事件循环上调用。
	 */
	public static void writeNow(Channel channel, Entry entry) {
		if (beforeWriteSafely(channel, entry)) {
			entry.addListener(entry);
			channel.writeAndFlush(entry.message(), entry);
		}
	}

	/**
	 * 从任意线程入队。在事件循环上调用时等价于 {@link #writeNow}（不入队）。
	 */
	public void enqueue(Entry entry) {
		if (eventLoop.inEventLoop()) {
			writeNow(channel, entry);
			return;
		}
		queue.offer(entry);
		if (scheduled.compareAndSet(false, true)) {
			schedule();
		}
	}

	/** 已持有 scheduled 的一方投递 drain。投递被拒绝（事件循环已关）时由本线程把队列取空、逐条通知不会写出。 */
	private void schedule() {
		try {
			eventLoop.execute(drainTask);
		} catch (RejectedExecutionException e) {
			failAllRejected(e);
		}
	}

	private void failAllRejected(RejectedExecutionException cause) {
		for (;;) {
			Entry entry;
			while ((entry = queue.poll()) != null) {
				try {
					entry.notWritten(cause);
				} catch (Throwable t) {
					log.warn("Write queue entry callback failed", t);
				}
			}
			scheduled.set(false);
			// 置 false 之后又有人入队：它的 CAS 可能输给了我们，由我们接着处理
			if (queue.isEmpty() || !scheduled.compareAndSet(false, true)) {
				return;
			}
		}
	}

	/** 在事件循环上：取空队列，逐条写出，每 {@link #FLUSH_EVERY} 条与批末各 flush 一次。 */
	private void drain() {
		int drained = 0;
		int sinceFlush = 0;
		for (;;) {
			Entry entry;
			while ((entry = queue.poll()) != null) {
				if (writeQueued(entry) && ++sinceFlush == FLUSH_EVERY) {
					flush();
					sinceFlush = 0;
				}
				if (++drained >= MAX_PER_DRAIN && !queue.isEmpty()) {
					if (sinceFlush > 0) {
						flush();
					}
					// 仍持有 scheduled：让出事件循环，稍后接着取
					schedule();
					return;
				}
			}
			if (sinceFlush > 0) {
				flush();
				sinceFlush = 0;
			}
			scheduled.set(false);
			// 置 false 与上面最后一次 poll 之间入队的生产者 CAS 失败（看到 true）就不会投递：这里替它接着取
			if (queue.isEmpty() || !scheduled.compareAndSet(false, true)) {
				return;
			}
		}
	}

	/** @return 是否写出了（交给了 pipeline） */
	private boolean writeQueued(Entry entry) {
		if (!beforeWriteSafely(channel, entry)) {
			return false;
		}
		try {
			entry.addListener(entry);
			channel.write(entry.message(), entry);
		} catch (Throwable t) {
			entry.tryFailure(t);
		}
		return true;
	}

	private static boolean beforeWriteSafely(Channel channel, Entry entry) {
		try {
			return entry.beforeWrite(channel);
		} catch (Throwable t) {
			entry.addListener(entry);
			entry.tryFailure(t);
			return false;
		}
	}

	private void flush() {
		if (!flushContextResolved) {
			flushContext = channel.pipeline().context(FlushConsolidation.HANDLER_NAME);
			flushContextResolved = true;
		}
		if (flushContext != null && !flushContext.isRemoved()) {
			flushContext.flush();
		} else {
			channel.flush();
		}
	}

	/** 队列里尚未写出的条数（观测与测试用；并发下是近似值）。 */
	public int size() {
		return queue.size();
	}
}
