package com.feynman.rpc.core.client;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.feynman.rpc.core.RpcException;

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
	private volatile boolean closed = false;

	ConnectionPool(Bootstrap bootstrap, String host, int port, int size) {
		this.bootstrap = bootstrap;
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
	 * 取一条可写的连接。没有已激活的连接但有正在建立的连接时，等这次建连完成再交出去——
	 * 不能直接往未连接的 channel 上写：Netty 会让这次写以 NotYetConnectedException 失败，
	 * 而不是缓冲到连上为止（旧实现的注释假设会缓冲，首个请求因此偶发失败）。
	 */
	CompletableFuture<Channel> acquire() {
		int size = slots.size();
		ChannelFuture connecting = null;
		for (int i = 0; i < size; i++) {
			Slot slot = slots.get(Math.floorMod(cursor.getAndIncrement(), size));
			ChannelFuture connect = slot.connect;
			if (connect == null) {
				continue;
			}
			if (connect.channel().isActive()) {
				return CompletableFuture.completedFuture(connect.channel());
			}
			if (connecting == null && !connect.isDone()) {
				connecting = connect;
			}
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
