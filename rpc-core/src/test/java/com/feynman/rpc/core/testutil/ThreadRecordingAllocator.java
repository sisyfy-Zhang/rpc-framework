package com.feynman.rpc.core.testutil;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * 测试用分配器：委托给池化分配器，记录每次分配所在的线程；虚拟线程、或名字不以 {@code allowedThreadPrefix} 开头的线程
 * 记为违规（附分配处的调用栈）。客户端测试传 {@code rpc-client-io}，服务端测试传 {@code rpc-server-worker}——
 * 证明池化分配只发生在事件循环上（docs/pitfalls.md 第 32 条）。
 */
public final class ThreadRecordingAllocator implements ByteBufAllocator {
	private final ByteBufAllocator delegate = PooledByteBufAllocator.DEFAULT;
	private final String allowedThreadPrefix;
	private final Set<String> offending = ConcurrentHashMap.newKeySet();
	private final AtomicLong allocations = new AtomicLong();

	public ThreadRecordingAllocator(String allowedThreadPrefix) {
		this.allowedThreadPrefix = allowedThreadPrefix;
	}

	public void reset() {
		offending.clear();
		allocations.set(0);
	}

	public Set<String> offendingThreads() {
		return Set.copyOf(offending);
	}

	public long allocations() {
		return allocations.get();
	}

	private <T> T record(T buf) {
		allocations.incrementAndGet();
		Thread t = Thread.currentThread();
		if (t.isVirtual() || !t.getName().startsWith(allowedThreadPrefix)) {
			offending.add((t.isVirtual() ? "virtual " : "") + t + " @ " + caller());
		}
		return buf;
	}

	private static String caller() {
		StackTraceElement[] stack = new Throwable().getStackTrace();
		StringBuilder sb = new StringBuilder();
		for (int i = 3; i < Math.min(stack.length, 9); i++) {
			sb.append(stack[i]).append(" <- ");
		}
		return sb.toString();
	}

	@Override
	public ByteBuf buffer() {
		return record(delegate.buffer());
	}

	@Override
	public ByteBuf buffer(int initialCapacity) {
		return record(delegate.buffer(initialCapacity));
	}

	@Override
	public ByteBuf buffer(int initialCapacity, int maxCapacity) {
		return record(delegate.buffer(initialCapacity, maxCapacity));
	}

	@Override
	public ByteBuf ioBuffer() {
		return record(delegate.ioBuffer());
	}

	@Override
	public ByteBuf ioBuffer(int initialCapacity) {
		return record(delegate.ioBuffer(initialCapacity));
	}

	@Override
	public ByteBuf ioBuffer(int initialCapacity, int maxCapacity) {
		return record(delegate.ioBuffer(initialCapacity, maxCapacity));
	}

	@Override
	public ByteBuf heapBuffer() {
		return record(delegate.heapBuffer());
	}

	@Override
	public ByteBuf heapBuffer(int initialCapacity) {
		return record(delegate.heapBuffer(initialCapacity));
	}

	@Override
	public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
		return record(delegate.heapBuffer(initialCapacity, maxCapacity));
	}

	@Override
	public ByteBuf directBuffer() {
		return record(delegate.directBuffer());
	}

	@Override
	public ByteBuf directBuffer(int initialCapacity) {
		return record(delegate.directBuffer(initialCapacity));
	}

	@Override
	public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
		return record(delegate.directBuffer(initialCapacity, maxCapacity));
	}

	@Override
	public CompositeByteBuf compositeBuffer() {
		return record(delegate.compositeBuffer());
	}

	@Override
	public CompositeByteBuf compositeBuffer(int maxNumComponents) {
		return record(delegate.compositeBuffer(maxNumComponents));
	}

	@Override
	public CompositeByteBuf compositeHeapBuffer() {
		return record(delegate.compositeHeapBuffer());
	}

	@Override
	public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
		return record(delegate.compositeHeapBuffer(maxNumComponents));
	}

	@Override
	public CompositeByteBuf compositeDirectBuffer() {
		return record(delegate.compositeDirectBuffer());
	}

	@Override
	public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
		return record(delegate.compositeDirectBuffer(maxNumComponents));
	}

	@Override
	public boolean isDirectBufferPooled() {
		return delegate.isDirectBufferPooled();
	}

	@Override
	public int calculateNewCapacity(int minNewCapacity, int maxCapacity) {
		return delegate.calculateNewCapacity(minNewCapacity, maxCapacity);
	}
}
