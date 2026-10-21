package com.feynman.rpc.bench;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

import com.feynman.rpc.core.filter.Invocation;
import com.feynman.rpc.core.filter.Invoker;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.filter.ServerFilter;

/**
 * bench server 的服务端 Filter：记录压测请求在业务执行器里的<b>排队等待</b>——从 IO 线程解码该帧（服务端
 * {@link Invocation#startNanos()}，即到达时刻）到业务线程开始执行 Filter 链的耗时。不改 core：服务端 Invocation 的起点本来就是到达时刻
 * （DeadlineServerFilter 用同一个起点判过期）。
 *
 * <p>order {@value #ORDER}：在 DeadlineServerFilter（-4000）之外，在队列里熬到过期、随后被拒的请求也计入（它们同样排了队）。
 * 只记 {@code echo} / {@code echoOrder}（压测调用），{@code environment()} / {@code stats()} / {@code counters()} 不计。
 *
 * <p>累计直方图（2 位有效数字、1µs ~ 60s，纳秒）由 {@link #encoded()} 以压缩 + Base64 交给压测端，压测端对两个时刻的累计值做差得到
 * 窗口 / 每秒的分位数——服务端不维护任何"区间"状态，多个读者互不干扰。direct 执行器下没有队列，这里约等于解码到执行的几微秒。
 */
final class QueueWaitFilter implements ServerFilter {

	static final int ORDER = -4500;
	static final long LOWEST_NANOS = 1_000;
	static final long HIGHEST_NANOS = 60_000_000_000L;
	static final int DIGITS = 2;

	private final Histogram histogram = new ConcurrentHistogram(LOWEST_NANOS, HIGHEST_NANOS, DIGITS);

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		String method = invocation.method() == null ? null : invocation.method().getName();
		if ("echo".equals(method) || "echoOrder".equals(method)) {
			long waited = System.nanoTime() - invocation.startNanos();
			histogram.recordValue(Math.min(HIGHEST_NANOS, Math.max(LOWEST_NANOS, waited)));
		}
		return next.invoke(invocation);
	}

	@Override
	public int order() {
		return ORDER;
	}

	/** 此刻的累计直方图（压缩 + Base64）。 */
	String encoded() {
		Histogram copy = histogram.copy();
		ByteBuffer buffer = ByteBuffer.allocate(copy.getNeededByteBufferCapacity());
		int length = copy.encodeIntoCompressedByteBuffer(buffer);
		byte[] bytes = new byte[length];
		buffer.flip();
		buffer.get(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	/** {@link #encoded()} 的逆；格式不对时抛 IllegalArgumentException。 */
	static Histogram decode(String encoded) {
		try {
			return Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(Base64.getDecoder().decode(encoded)), LOWEST_NANOS);
		} catch (java.util.zip.DataFormatException | RuntimeException e) {
			throw new IllegalArgumentException("not an encoded queue-wait histogram: " + e, e);
		}
	}

	/** 两个累计直方图之差（after − before，before 可为 null）；任一为 null（取不到）时返回 null。 */
	static Histogram delta(String before, String after) {
		if (after == null) {
			return null;
		}
		Histogram a = decode(after);
		if (before != null) {
			a.subtract(decode(before));
		}
		return a;
	}
}
