package com.feynman.rpc.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.loadbalance.LoadStats;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

/**
 * 管理每个服务地址对应的 {@link ConnectionPool}。节点选择逻辑在 LoadBalance，这里只负责连接本身：
 * "选哪个节点"和"怎么连到它"是两件事。连接按地址共享：同一个提供者进程上的多个服务复用同一组连接。
 *
 * <p>归单个客户端实例所有（旧实现是进程级静态表，两个客户端会共享、互相摘除对方的连接）。
 *
 * <p><b>下线节点的连接排空</b>：注册中心推送某地址已下线时，它的连接池不立即关闭，而是移入 draining 表：
 * 不再被 {@link #acquire} 选中（新调用拿不到它），已经发出的请求仍能在原连接上收到响应；等该地址的在途数
 * （{@link LoadStats#active}）归零、最多 {@value #DRAIN_LIMIT_MS}ms 后再关闭。旧实现立即关连接，provider 优雅下线时
 * 本端正在等响应的请求只能挂到超时——服务端明明还在认真把它们处理完。排空期间该地址重新上线则直接恢复使用。
 */
final class ConnectionManager {

	private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);
	/** 下线节点的连接最多保留多久：服务端停机超时默认 10s，但消费端不必陪到最后——超过就让剩下的请求以连接关闭失败。 */
	static final long DRAIN_LIMIT_MS = 5000;
	private static final long DRAIN_POLL_MS = 20;

	private final Bootstrap bootstrap;
	private final int connectionsPerNode;
	private final LoadStats loadStats;
	private final ConcurrentHashMap<String, ConnectionPool> pools = new ConcurrentHashMap<>();
	/** 已下线、等在途归零后关闭的连接池。 */
	private final ConcurrentHashMap<String, ConnectionPool> draining = new ConcurrentHashMap<>();

	ConnectionManager(Bootstrap bootstrap, int connectionsPerNode) {
		this(bootstrap, connectionsPerNode, new LoadStats());
	}

	ConnectionManager(Bootstrap bootstrap, int connectionsPerNode, LoadStats loadStats) {
		this.bootstrap = bootstrap;
		this.connectionsPerNode = connectionsPerNode;
		this.loadStats = loadStats;
	}

	/** 确保到该地址有连接池；已存在则是空操作，不会重连。该地址正在排空时直接恢复使用原连接池。 */
	synchronized void ensurePool(String address, String host, int port) {
		if (pools.containsKey(address)) {
			return;
		}
		ConnectionPool revived = draining.remove(address);
		if (revived != null) {
			log.info("Provider {} is back while its connections were draining; reusing them", address);
			pools.put(address, revived);
			return;
		}
		pools.put(address, new ConnectionPool(bootstrap, host, port, connectionsPerNode));
	}

	/**
	 * 只保留 {@code addresses} 里的连接池，其余进入排空（在途为零的立即关闭）——按需增量调整，
	 * 而不是每次注册中心变化都推倒重连所有连接。
	 */
	synchronized void retainOnly(Set<String> addresses) {
		for (String address : List.copyOf(pools.keySet())) {
			if (addresses.contains(address)) {
				continue;
			}
			ConnectionPool removed = pools.remove(address);
			if (removed == null) {
				continue;
			}
			int active = loadStats.active(address);
			if (active <= 0) {
				removed.closeAll();
				continue;
			}
			log.info("Provider {} went offline with {} request(s) in flight; keeping its connections until they complete "
					+ "(at most {}ms)", address, active, DRAIN_LIMIT_MS);
			draining.put(address, removed);
			scheduleDrainCheck(address, removed, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_LIMIT_MS));
		}
	}

	private void scheduleDrainCheck(String address, ConnectionPool pool, long deadlineNanos) {
		EventLoop loop = bootstrap.config().group().next();
		try {
			loop.schedule(() -> checkDrained(address, pool, deadlineNanos), DRAIN_POLL_MS, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// IO 线程组已关闭（客户端正在关闭）：closeAll 会处理
			finishDrain(address, pool);
		}
	}

	private void checkDrained(String address, ConnectionPool pool, long deadlineNanos) {
		synchronized (this) {
			if (draining.get(address) != pool) {
				return; // 已恢复使用或已被 closeAll 处理
			}
		}
		int active = loadStats.active(address);
		if (active > 0 && System.nanoTime() - deadlineNanos < 0) {
			scheduleDrainCheck(address, pool, deadlineNanos);
			return;
		}
		if (active > 0) {
			log.warn("Closing connections to offline provider {} with {} request(s) still in flight after {}ms",
					address, active, DRAIN_LIMIT_MS);
		} else {
			log.info("Connections to offline provider {} drained; closing them", address);
		}
		finishDrain(address, pool);
	}

	private void finishDrain(String address, ConnectionPool pool) {
		synchronized (this) {
			if (!draining.remove(address, pool)) {
				return;
			}
		}
		pool.closeAll();
	}

	/** 只从在用的连接池里取连接：排空中的地址不会被选中（它也不在服务目录里）。 */
	CompletableFuture<Channel> acquire(String address) {
		ConnectionPool pool = pools.get(address);
		if (pool == null) {
			return CompletableFuture.failedFuture(new RpcException("No connection pool for: " + address));
		}
		return pool.acquire();
	}

	/**
	 * 到该地址当前已激活的连接（快照，包括排空中的），没有连接池时为空。供观测与测试，选路请用 {@link #acquire}。
	 */
	List<Channel> activeChannels(String address) {
		List<Channel> channels = new ArrayList<>();
		ConnectionPool pool = pools.get(address);
		if (pool != null) {
			channels.addAll(pool.activeChannels());
		}
		ConnectionPool drainingPool = draining.get(address);
		if (drainingPool != null) {
			channels.addAll(drainingPool.activeChannels());
		}
		return channels;
	}

	/** 在用的地址集合（不含排空中的）。 */
	Set<String> addresses() {
		return Set.copyOf(pools.keySet());
	}

	synchronized void closeAll() {
		pools.values().forEach(ConnectionPool::closeAll);
		pools.clear();
		draining.values().forEach(ConnectionPool::closeAll);
		draining.clear();
	}
}
