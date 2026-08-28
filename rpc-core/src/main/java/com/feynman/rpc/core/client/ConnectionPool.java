package com.feynman.rpc.core.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

	/**
	 * 轮询各 slot，优先选已连接的 channel。正在连接中（非 null 但未 active）的 slot 也可以兜底——
	 * Netty 会先缓冲写入，等连接建立后统一 flush，没必要直接拒绝。只有 channel 为 null
	 * （正处于重连退避期）的 slot 才彻底不可用。
	 */
	Channel next() {
		int size = slots.size();
		Channel fallback = null;
		for (int i = 0; i < size; i++) {
			Slot slot = slots.get(Math.floorMod(cursor.getAndIncrement(), size));
			Channel channel = slot.channel;
			if (channel == null) {
				continue;
			}
			if (channel.isActive()) {
				return channel;
			}
			if (fallback == null) {
				fallback = channel;
			}
		}
		return fallback;
	}

	void closeAll() {
		closed = true;
		for (Slot slot : slots) {
			Channel channel = slot.channel;
			if (channel != null) {
				channel.close();
			}
		}
	}

	private void connect(Slot slot, long backoffMs) {
		if (closed) {
			return;
		}
		ChannelFuture future = bootstrap.connect(host, port);
		Channel channel = future.channel();
		slot.channel = channel;
		channel.closeFuture().addListener(f -> {
			if (closed) {
				return;
			}
			slot.channel = null;
			long nextBackoff = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
			channel.eventLoop().schedule(() -> connect(slot, nextBackoff), backoffMs, TimeUnit.MILLISECONDS);
		});
	}

	private static final class Slot {
		private volatile Channel channel;
	}
}
