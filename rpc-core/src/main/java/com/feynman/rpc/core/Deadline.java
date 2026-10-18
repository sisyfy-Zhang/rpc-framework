package com.feynman.rpc.core;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 一个本进程内的截止时刻（基于 {@link System#nanoTime()} 的单调时钟），不可变。
 *
 * <p>为什么不是墙钟时间：截止时刻只在一个 JVM 内比较（"还剩多少"），单调时钟不受 NTP 调整影响；跨进程传递的是
 * 剩余时长（{@code rpc.timeout}），由接收方按到达时刻重新起算，两台机器的时钟偏差因此不影响判定（与 gRPC 的
 * grpc-timeout 相同）。所以本类刻意没有 epoch 形式，也不可序列化。
 *
 * <p>用途：服务端在业务方法执行期间通过 {@link RpcContext#serverDeadline()} 暴露本次请求剩余的预算；业务方法
 * 发起的出站调用自动取"引用超时"与"继承预算"的较小者（{@code ReferenceOptions.inheritDeadline}，默认开启）；
 * 把工作切到别的线程时用 {@link RpcContext#withDeadline(Deadline, java.util.function.Supplier)} 显式带过去。
 */
public final class Deadline implements Comparable<Deadline> {

	private final long nanoTime;

	private Deadline(long nanoTime) {
		this.nanoTime = nanoTime;
	}

	/** 从现在起 {@code timeout} 之后。 */
	public static Deadline after(Duration timeout) {
		return atNanoTime(System.nanoTime() + timeout.toNanos());
	}

	/** 以 {@link System#nanoTime()} 时钟表示的绝对时刻；框架内部（按请求到达时刻 + rpc.timeout 构造）使用。 */
	public static Deadline atNanoTime(long nanoTime) {
		return new Deadline(nanoTime);
	}

	/** 剩余纳秒；已过期为负数或 0。 */
	public long remainingNanos() {
		return nanoTime - System.nanoTime();
	}

	/** 剩余时长，已过期时为 {@link Duration#ZERO}（不返回负数，调用方可以直接拿去做超时参数）。 */
	public Duration remaining() {
		long nanos = remainingNanos();
		return nanos > 0 ? Duration.ofNanos(nanos) : Duration.ZERO;
	}

	public boolean isExpired() {
		return remainingNanos() <= 0;
	}

	/** 两者中较早的一个；{@code other} 为 null 时返回自身。截止时间只会收紧不会放宽（与 gRPC Context 的语义一致）。 */
	public Deadline earlier(Deadline other) {
		if (other == null) {
			return this;
		}
		return compareTo(other) <= 0 ? this : other;
	}

	public long nanoTime() {
		return nanoTime;
	}

	/** 按 nanoTime 的差值比较（nanoTime 可能溢出回绕，不能直接比较大小）。 */
	@Override
	public int compareTo(Deadline other) {
		long diff = nanoTime - other.nanoTime;
		return diff < 0 ? -1 : (diff == 0 ? 0 : 1);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Deadline other && other.nanoTime == nanoTime;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(nanoTime);
	}

	@Override
	public String toString() {
		return "Deadline(remaining=" + TimeUnit.NANOSECONDS.toMillis(remainingNanos()) + "ms)";
	}
}
