package io.github.sisyfyzhang.steadyrpc.core.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 某个服务在消费端视角下的提供者列表，由注册中心订阅推送维护。Cluster 每次调用都从这里读最新列表。
 *
 * <p>推空保护在这里实现：推送空列表且此前非空时保留旧列表并告警。判断放在消费端而不是注册中心实现里，
 * 因为"空列表可信吗"是消费端的策略问题，与用 ZK 还是 Nacos 无关；两个实现因此只需忠实转发。
 *
 * <p><b>两个视图</b>：{@link #instances()} 是对调用方可见的路由视图（Cluster 选点、{@code RpcClient.providers}），
 * {@link #connectionTargets()} 是连接池应当覆盖的地址（{@code onChange} 里 RpcClient 据此建池 / 排空）。一次推送按
 * "先建池、再发布、再排空"三步走：连接目标先取新旧列表的并集并执行 {@code onChange}（新地址建好连接池、下线地址的池暂留），
 * 然后才发布新的路由视图，有地址下线时再把连接目标收窄到新列表、执行第二次 {@code onChange}（下线地址进入排空）。
 * 所以任何时刻路由视图里的地址都已有连接池："providers() 非空即可发起调用"；以前先发布、后建池，
 * 两步之间读到新列表的调用在 {@code ConnectionManager.acquire} 上直接失败 "No connection pool"（ProvidersVisibleImpliesCallableTest）。
 *
 * <p>{@link #awaitProviders(Duration)} 挂在同一条更新路径上：每次列表发布之后唤醒等待者，等待者在条件变量上等"列表非空"，不轮询。用 {@link ReentrantLock} 而不是 {@code synchronized}：等待者可能是虚拟线程，
 * JDK 21 里在 {@code synchronized} 内 {@code wait} 会钉住载体线程。
 * 框架内部类型。
 */
public final class ServiceDirectory implements InstanceListener {

	private static final Logger log = LoggerFactory.getLogger(ServiceDirectory.class);

	private final ServiceKey key;
	private final boolean emptyProtection;
	private final Consumer<ServiceDirectory> onChange;
	private volatile List<ServiceInstance> instances = List.of();
	/** 连接池应覆盖的地址：发布前是新旧列表的并集，发布后收窄到新列表。 */
	private volatile List<ServiceInstance> connectionTargets = List.of();
	/** 只用于"列表已替换"的通知；instances 本身是 volatile，读路径不加锁。 */
	private final ReentrantLock changeLock = new ReentrantLock();
	private final Condition changed = changeLock.newCondition();

	/**
	 * @param onChange 连接目标变化时回调（RpcClient 据 {@link #connectionTargets()} 调整连接池）；一次推送回调一到两次，见类注释
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
		List<ServiceInstance> next = List.copyOf(latest);
		boolean removes = !next.containsAll(current);
		// 1. 先建池：连接目标 = 新旧并集。新地址的连接池在发布前就存在（ConnectionManager.acquire 对没有连接池的地址
		//    直接失败 "No connection pool"，有连接池时会等 connect 完成）；将要下线的地址此刻仍在路由视图里，池也还在
		List<ServiceInstance> union = new ArrayList<>(current);
		for (ServiceInstance instance : next) {
			if (!union.contains(instance)) {
				union.add(instance);
			}
		}
		connectionTargets = List.copyOf(union);
		onChange.accept(this);
		// 2. 再发布路由视图：从这一刻起读到新列表的调用方，选中的每个地址都已有连接池
		instances = next;
		log.info("Providers of {} updated: {}", key, next.stream().map(ServiceInstance::address).toList());
		// 3. 有地址下线时收窄连接目标：它们已不在路由视图里，连接池进入排空（在途请求照常收到响应）
		connectionTargets = next;
		if (removes) {
			onChange.accept(this);
		}
		// 发布之后才唤醒等待者：awaitProviders 的调用方醒来后会立刻发起调用
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
	 * 不丢唤醒：写入方先执行 {@code onChange}（RpcClient 据此为新地址建连接池）、再写 volatile 列表，最后在锁内 signal；
	 * 等待方在锁内检查列表后才 await，两者的先后只有"检查时已非空"与"await 之后被 signal 唤醒"两种结果。
	 * 两条路径看到非空列表时，列表里每个地址的连接池都已存在（列表在建池之后才发布），紧接着发起的第一次调用
	 * 走 {@code ConnectionManager.acquire}，会等 connect 完成而不是失败。
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

	/** 路由视图：对调用方可见的提供者列表，其中每个地址都已有连接池。 */
	public List<ServiceInstance> instances() {
		return instances;
	}

	/** 连接池应覆盖的提供者：推送处理中是新旧列表的并集，其余时刻与 {@link #instances()} 相同。供 {@code onChange} 使用。 */
	public List<ServiceInstance> connectionTargets() {
		return connectionTargets;
	}
}
