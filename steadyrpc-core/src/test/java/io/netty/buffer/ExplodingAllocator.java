package io.netty.buffer;

/**
 * 测试用分配器：分配的缓冲区一旦被写入字节数组或 short（信封编码的必经之路）就抛出 {@link OutOfMemoryError}，
 * 模拟"缓冲区已分配、写入时直接内存耗尽"。用来证明各条编码路径在 Error（不是 RuntimeException）面前也会释放已分配的缓冲区。
 * 底层缓冲区来自池化分配器、受泄漏检测跟踪；包装层的 release 透传给它。
 */
public final class ExplodingAllocator extends AbstractByteBufAllocator {

	public ExplodingAllocator() {
		super(true);
	}

	@Override
	protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
		return explode(PooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity));
	}

	@Override
	protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
		return explode(PooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity));
	}

	@Override
	public boolean isDirectBufferPooled() {
		return true;
	}

	private static ByteBuf explode(ByteBuf real) {
		return new WrappedByteBuf(real) {
			@Override
			public ByteBuf writeBytes(byte[] src) {
				throw new OutOfMemoryError("simulated: direct buffer memory exhausted while writing " + src.length + " bytes");
			}

			@Override
			public ByteBuf writeShort(int value) {
				throw new OutOfMemoryError("simulated: direct buffer memory exhausted while writing a short");
			}
		};
	}
}
