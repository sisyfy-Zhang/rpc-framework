package com.feynman.rpc.core.internal;

import java.util.concurrent.TimeUnit;

/**
 * 优雅停机用的"闸门 + 在途计数"：{@link #tryEnter()} 在闸门关闭后返回 false（拒绝新的工作），已进入的工作
 * {@link #exit()} 时计数减一，{@link #awaitIdle} 等到计数归零或超时。RpcServer（在途请求）与 RpcClient（在途调用）
 * 各持有一个。框架内部类型。
 *
 * <p>为什么"关闸"和"计数"必须在同一把锁下：如果先读 closed 再自增（两个独立的原子变量），关闸线程可能在
 * 某个请求"读到未关闸"与"自增"之间看到计数为 0 并认为已排空，随后那个请求才进来、在连接已关闭后才执行。
 * 一把锁让"进入"与"关闸"严格有先后，排空判定才可靠。临界区只有几条指令，锁竞争可以忽略。
 */
public final class InFlightGate {

	private int inFlight; // guarded by this
	private boolean closed; // guarded by this

	/** 闸门开着则计数加一并返回 true；已关闭返回 false（调用方拒绝这次工作，且不要调用 {@link #exit()}）。 */
	public synchronized boolean tryEnter() {
		if (closed) {
			return false;
		}
		inFlight++;
		return true;
	}

	/** 一次已进入的工作结束。每次成功的 {@link #tryEnter()} 必须恰好对应一次。 */
	public synchronized void exit() {
		if (inFlight <= 0) {
			throw new IllegalStateException("exit() without a matching tryEnter()");
		}
		inFlight--;
		if (inFlight == 0) {
			notifyAll();
		}
	}

	/** 关闸：之后的 {@link #tryEnter()} 都返回 false。可重复调用。 */
	public synchronized void close() {
		closed = true;
	}

	public synchronized boolean isClosed() {
		return closed;
	}

	public synchronized int inFlight() {
		return inFlight;
	}

	/**
	 * 等到在途归零或超时。
	 *
	 * @return 是否已归零（false 表示超时，仍有工作在途）
	 */
	public synchronized boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (inFlight > 0) {
			long remainingNanos = deadline - System.nanoTime();
			if (remainingNanos <= 0) {
				return false;
			}
			TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
		}
		return true;
	}
}
