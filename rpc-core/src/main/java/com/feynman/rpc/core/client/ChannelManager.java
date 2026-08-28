package com.feynman.rpc.core.client;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;

/**
 * 管理每个服务地址对应的 {@link ConnectionPool}，以及供 LoadBalance 选择的地址→权重表。
 * 节点选择逻辑已下沉到 LoadBalance SPI，这里只负责连接本身："选哪个节点"和"怎么连到它"是两件事。
 */
public class ChannelManager {
	private static final int CONNECTIONS_PER_NODE = 2;

	private static final ConcurrentHashMap<String, ConnectionPool> pools = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Integer> addressWeights = new ConcurrentHashMap<>();

	public static boolean contains(String addr) {
		return addr != null && pools.containsKey(addr);
	}

	public static void ensurePool(String addr, Bootstrap bootstrap, String host, int port) {
		if (addr == null) {
			return;
		}
		pools.computeIfAbsent(addr, a -> new ConnectionPool(bootstrap, host, port, CONNECTIONS_PER_NODE));
	}

	/**
	 * 用 newAddressWeights 替换地址/权重表，只摘除已下线地址对应的连接池——
	 * 按需增量调整，而不是每次注册中心变化都推倒重连所有连接。
	 */
	public static void reconcile(Map<String, Integer> newAddressWeights) {
		for (String addr : pools.keySet()) {
			if (!newAddressWeights.containsKey(addr)) {
				ConnectionPool removed = pools.remove(addr);
				if (removed != null) {
					removed.closeAll();
				}
			}
		}
		addressWeights.clear();
		addressWeights.putAll(newAddressWeights);
	}

	public static void clearAll() {
		pools.values().forEach(ConnectionPool::closeAll);
		pools.clear();
		addressWeights.clear();
	}

	/** 当前所有存活地址及其权重的快照。 */
	public static Map<String, Integer> addressWeights() {
		return Collections.unmodifiableMap(addressWeights);
	}

	/** 把 LoadBalance 选出的地址解析成一条可用连接，找不到则返回 null。 */
	public static Channel getChannel(String address) {
		ConnectionPool pool = pools.get(address);
		return pool == null ? null : pool.next();
	}

}
