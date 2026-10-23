package io.github.sisyfyzhang.steadyrpc.core.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import io.github.sisyfyzhang.steadyrpc.core.RpcException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

/**
 * 到单个服务节点的小型固定容量连接池。每个 slot 在连接意外断开时会自行按指数退避重连，
 * 而不是整个节点直接失联、只能等下一次注册中心事件才重建。
 */
class ConnectionPool {

	private static final long INITIAL_BACKOFF_MS = 500;
	private static final long MAX_BACKOFF_MS = 30_000;

	private final Bootstrap bootstrap;
	private final String host;
	private final int port;
	private final List<Slot> slots;
	private final AtomicInteger cursor = new AtomicInteger(0);
	/** 轮询时跳过了一条已激活但不可写的连接、改用另一条可写连接的次数（所属客户端共用）。 */
	private final LongAdder unwritableSwitches;
	private volatile boolean closed = false;

	ConnectionPool(Bootstrap bootstrap, String host, int port, int size) {
		this(bootstrap, host, port, size, new LongAdder());
	}

	ConnectionPool(Bootstrap bootstrap, String host, int port, int size, LongAdder unwritableSwitches) {
		this.bootstrap = bootstrap;
		this.unwritableSwitches = unwritableSwitches;
		this.host = host;
		this.port = port;
		this.slots = new CopyOnWriteArrayList<>();
		for (int i = 0; i < size; i++) {
			Slot slot = new Slot();
			slots.add(slot);
			connect(slot, INITIAL_BACKOFF_MS);
		}
	}

	/** 当前已激活的连接（快照），供观测与测试；选路请用 {@link #acquire()}。 */
	List<Channel> activeChannels() {
		List<Channel> active = new java.util.ArrayList<>();
		for (Slot slot : slots) {
			ChannelFuture connect = slot.connect;
			if (connect != null && connect.channel().isActive()) {
				active.add(connect.channel());
			}
		}
		return active;
	}

	/**
	 * 取一条连接：轮询，优先已激活且可写（{@link Channel#isWritable()}，待写字节没超过高水位）的；已激活的都不可写时
	 * 交出轮询到的第一条（照常写，不阻塞调用方——超过出站硬上限的由 NettyClient 拒绝）。
	 * 没有已激活的连接但有正在建立的连接时，等这次建连完成再交出去——
	 * 不能直接往未连接的 channel 上写：Netty 会让这次写以 NotYetConnectedException 失败，
	 * 而不是缓冲到连上为止（旧实现的注释假设会缓冲，首个请求因此偶发失败）。
	 *
	 * <p>可写性在这里（调用方线程，取连接时）判断，不在事件循环上写出时重新选连接：后者要把请求从一个事件循环转投另一个，
	 * 而可写性是一读即可的 volatile 状态，取连接时的判断已经足够近。
	 */
	CompletableFuture<Channel> acquire() {
		int size = slots.size();
		ChannelFuture connecting = null;
		Channel unwritable = null;
		for (int i = 0; i < size; i++) {
			Slot slot = slots.get(Math.floorMod(cursor.getAndIncrement(), size));
			ChannelFuture connect = slot.connect;
			if (connect == null) {
				continue;
			}
			Channel channel = connect.channel();
			if (channel.isActive()) {
				if (channel.isWritable()) {
					if (unwritable != null) {
						unwritableSwitches.increment();
					}
					return CompletableFuture.completedFuture(channel);
				}
				if (unwritable == null) {
					unwritable = channel;
				}
				continue;
			}
			if (connecting == null && !connect.isDone()) {
				connecting = connect;
			}
		}
		if (unwritable != null) {
			return CompletableFuture.completedFuture(unwritable);
		}
		if (connecting == null) {
			return CompletableFuture.failedFuture(new RpcException("No active connection to " + host + ":" + port));
		}
		CompletableFuture<Channel> result = new CompletableFuture<>();
		connecting.addListener(f -> {
			if (f.isSuccess()) {
				result.complete(((ChannelFuture) f).channel());
			} else {
				result.completeExceptionally(new RpcException("Failed to connect to " + host + ":" + port, f.cause()));
			}
		});
		return result;
	}

	void closeAll() {
		closed = true;
		for (Slot slot : slots) {
			ChannelFuture connect = slot.connect;
			if (connect != null) {
				connect.channel().close();
			}
		}
	}

	private void connect(Slot slot, long backoffMs) {
		if (closed) {
			return;
		}
		ChannelFuture future;
		try {
			future = bootstrap.connect(host, port);
		} catch (RejectedExecutionException e) {
			// 客户端正在关闭，事件循环已不接收新任务
			return;
		}
		slot.connect = future;
		Channel channel = future.channel();
		channel.closeFuture().addListener(f -> {
			if (closed) {
				return;
			}
			slot.connect = null;
			long nextBackoff = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
			try {
				channel.eventLoop().schedule(() -> connect(slot, nextBackoff), backoffMs, TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException e) {
				// 事件循环已关闭：不再重连
			}
		});
	}

	private static final class Slot {
		private volatile ChannelFuture connect;
	}
}
