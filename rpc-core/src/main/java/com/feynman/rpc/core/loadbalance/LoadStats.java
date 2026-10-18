package com.feynman.rpc.core.loadbalance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录每个服务地址当前的在途请求数。每次发送都无条件维护（开销很小：一次原子自增/自减），
 * 这样无论当前用的是不是 {@link P2cLoadBalance}，它都能读到最新数据。
 *
 * <p>每个 RpcClient 持有一份，通过参数交给 LoadBalance：在途数是"这个客户端发出去还没回来的请求"，
 * 进程级共享会让同 JVM 的两个客户端互相干扰对方的选择，也让测试之间互相污染。
 */
public final class LoadStats {

	private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

	public void begin(String address) {
		active.computeIfAbsent(address, a -> new AtomicInteger()).incrementAndGet();
	}

	public void end(String address) {
		AtomicInteger counter = active.get(address);
		if (counter != null) {
			counter.decrementAndGet();
		}
	}

	public int active(String address) {
		AtomicInteger counter = active.get(address);
		return counter == null ? 0 : counter.get();
	}
}
