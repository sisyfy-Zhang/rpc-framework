package com.feynman.rpc.core.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 某个服务在消费端视角下的提供者列表，由注册中心订阅推送维护。Cluster 每次调用都从这里读最新列表。
 *
 * <p>推空保护在这里实现：推送空列表且此前非空时保留旧列表并告警。判断放在消费端而不是注册中心实现里，
 * 因为"空列表可信吗"是消费端的策略问题，与用 ZK 还是 Nacos 无关；两个实现因此只需忠实转发。
 *
 * <p>{@link #awaitProviders(Duration)} 挂在同一条更新路径上：每次列表替换并执行完 {@code onChange}（建连接池）后
 * 唤醒等待者，等待者在条件变量上等"列表非空"，不轮询。用 {@link ReentrantLock} 而不是 {@code synchronized}：等待者可能是虚拟线程，
 * JDK 21 里在 {@code synchronized} 内 {@code wait} 会钉住载体线程。
 * 框架内部类型。
 */
public final class ServiceDirectory implements InstanceListener {

	private static final Logger log = LoggerFactory.getLogger(ServiceDirectory.class);

	private final ServiceKey key;
	private final boolean emptyProtection;
	private final Consumer<ServiceDirectory> onChange;
	private volatile List<ServiceInstance> instances = List.of();
	/** 只用于"列表已替换"的通知；instances 本身是 volatile，读路径不加锁。 */
	private final ReentrantLock changeLock = new ReentrantLock();
	private final Condition changed = changeLock.newCondition();

	/**
	 * @param onChange 列表真正发生替换后回调（RpcClient 据此调整连接池）
	 */
	public ServiceDirectory(ServiceKey key, boolean emptyProtection, Consumer<ServiceDirectory> onChange) {
		this.key = key;
		this.emptyProtection = emptyProtection;
		this.onChange = onChange;
	}

	@Override
	public void onInstancesChanged(List<ServiceInstance> latest) {
		List<ServiceInstance> current = instances;
		if (latest.isEmpty() && emptyProtection && !current.isEmpty()) {
			log.warn("Registry pushed an EMPTY provider list for {}; empty protection keeps the previous {} provider(s) {}",
					key, current.size(), current.stream().map(ServiceInstance::address).toList());
			return;
		}
		instances = List.copyOf(latest);
		log.info("Providers of {} updated: {}", key, instances.stream().map(ServiceInstance::address).toList());
		// 先建连接池、再唤醒等待者：awaitProviders 的调用方醒来后会立刻发起调用，此时新地址的连接池必须已经存在
		// （ConnectionManager.acquire 对没有连接池的地址直接失败 "No connection pool"，有连接池时会等 connect 完成）。
		// 反过来的顺序在负载高时会让刚醒来的调用抢在 refreshConnections 之前，见 AwaitProvidersTest 的偶发失败。
		onChange.accept(this);
		signalChanged();
	}

	private void signalChanged() {
		changeLock.lock();
		try {
			changed.signalAll();
		} finally {
			changeLock.unlock();
		}
	}

	/**
	 * 阻塞到提供者列表非空，最多 {@code timeout}。列表已经非空时立即返回 true。
	 * 不丢唤醒：写入方先写 volatile 列表、执行 {@code onChange}（RpcClient 据此为新地址建连接池），再在锁内 signal；
	 * 等待方在锁内检查列表后才 await，两者的先后只有"检查时已非空"与"await 之后被 signal 唤醒"两种结果。
	 * signal 放在 {@code onChange} 之后，是为了让被唤醒的等待者醒来时连接池已经存在：它紧接着发起的第一次调用
	 * 走 {@code ConnectionManager.acquire}，有连接池就会等 connect 完成，没有连接池则直接失败。
	 * （"检查时已非空"的那条路径不受这个顺序保护：列表写入在 onChange 之前，极端情况下仍可能先看到列表；
	 * 等待中的调用方——即"先订阅后注册"的场景——走的是 signal 路径。）
	 *
	 * @return 超时前列表变为非空时 true，超时仍为空时 false
	 * @throws InterruptedException 等待中被中断
	 */
	public boolean awaitProviders(Duration timeout) throws InterruptedException {
		long remaining = timeout.toNanos();
		changeLock.lock();
		try {
			while (instances.isEmpty()) {
				if (remaining <= 0) {
					return false;
				}
				remaining = changed.awaitNanos(remaining);
			}
			return true;
		} finally {
			changeLock.unlock();
		}
	}

	public ServiceKey key() {
		return key;
	}

	public List<ServiceInstance> instances() {
		return instances;
	}
}
