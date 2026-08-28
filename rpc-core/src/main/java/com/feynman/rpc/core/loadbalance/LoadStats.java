package com.feynman.rpc.core.loadbalance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录每个服务地址当前的在途请求数。每次发送都无条件维护（开销很小：一次原子自增/自减），
 * 这样无论当前用的是不是 {@link P2cLoadBalance}，它都能读到最新数据。
 */
public final class LoadStats {

	private static final ConcurrentHashMap<String, AtomicInteger> ACTIVE = new ConcurrentHashMap<>();

	private LoadStats() {
	}

	public static void begin(String address) {
		ACTIVE.computeIfAbsent(address, a -> new AtomicInteger()).incrementAndGet();
	}

	public static void end(String address) {
		AtomicInteger counter = ACTIVE.get(address);
		if (counter != null) {
			counter.decrementAndGet();
		}
	}

	public static int active(String address) {
		AtomicInteger counter = ACTIVE.get(address);
		return counter == null ? 0 : counter.get();
	}
}
