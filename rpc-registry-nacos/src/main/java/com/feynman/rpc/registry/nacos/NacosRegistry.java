package com.feynman.rpc.registry.nacos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 基于 Nacos 的 {@link Registry}（第二实现，只承诺通过 Registry 契约测试，见 DECISIONS D-007），映射规则见
 * {@link NacosNaming}。与 ZooKeeper 实现在一致性模型上相反（Nacos 临时实例靠客户端连接 / 心跳维持、分区时继续服务），
 * 但对上层呈现同一套语义：订阅在返回前推送当前全量、变化推送全量、只推健康且启用的实例、close 后不再回调。
 *
 * <ul>
 *   <li>注册为<b>临时实例</b>（{@code ephemeral = true}）：生命周期跟着本客户端，进程消失后由 Nacos 摘除，
 *       与 ZK 临时节点同义；持久实例需要显式注销，进程崩溃会留下死节点。</li>
 *   <li>订阅：先 {@code getAllInstances} 直接查一次服务端并推给监听者（契约要求订阅返回前有首次推送），
 *       再 {@code NamingService.subscribe} 接收后续变化。这两步在本实例的后台线程上执行，{@code subscribe} 最多等
 *       {@code connect-wait-ms}（默认 3000）：服务端可达时首次推送在返回前完成；不可达时<b>不抛异常、不推空列表</b>，
 *       到时即返回，后台按指数退避（500ms 起、10s 封顶）一直重试，成功后补推首次列表并开始接收变化——与 ZooKeeper
 *       实现同一语义（Registry 契约）。为什么不再抛 RegistryException：调用方（RpcClient.reference）拿到异常只能
 *       自己重试，而"注册中心暂时不可达"本该由注册中心实现消化；"没连上"也不能被当成"没有提供者"推一个空列表。
 *       不依赖 Nacos 客户端自己的断线补推（未经验证），首次列表由本实现主动查询。</li>
 *   <li>每个订阅包一层 {@link Subscription}：回调在它的锁下进行，{@code unsubscribe} / {@code close} 拿同一把锁
 *       置失效，之后 Nacos 线程上迟到的事件不会再回调监听者。</li>
 *   <li>线程：Nacos 客户端自己创建并命名线程（{@code com.alibaba.nacos.*}、{@code nacos-grpc-*} 等，部分是进程级共享的），
 *       本类无法让它们带上实例前缀，契约里的线程泄漏检查对 Nacos 跳过。本类自己的订阅重试线程名为
 *       {@code rpc-registry-nacos-{实例哈希}-subscribe}，close 时关闭。</li>
 * </ul>
 *
 * <p>{@link RegistryConfig#properties()} 原样作为 Nacos 客户端属性（如 {@code namespace}、{@code username}、
 * {@code password}），core 不需要认识它们；{@code connect-wait-ms} 由本类读取。
 */
public class NacosRegistry implements Registry {

	private static final Logger log = LoggerFactory.getLogger(NacosRegistry.class);
	static final String CONNECT_WAIT_MS = "connect-wait-ms";
	static final String DEFAULT_CONNECT_WAIT_MS = "3000";
	private static final long INITIAL_RETRY_MS = 500;
	private static final long MAX_RETRY_MS = 10_000;

	private final String address;
	private final long connectWaitMs;
	private final NamingService namingService;
	private final Map<SubscriptionId, Subscription> subscriptions = new ConcurrentHashMap<>();
	/** 订阅的首次查询与失败重试在这里执行；线程在第一次订阅时才创建。 */
	private final ScheduledExecutorService subscriber;
	private volatile boolean closed;

	public NacosRegistry(RegistryConfig config) {
		this.address = config.address();
		this.connectWaitMs = Long.parseLong(config.property(CONNECT_WAIT_MS, DEFAULT_CONNECT_WAIT_MS));
		Properties properties = new Properties();
		properties.putAll(config.properties());
		properties.remove(CONNECT_WAIT_MS);
		properties.setProperty(PropertyKeyConst.SERVER_ADDR, config.address());
		try {
			this.namingService = NacosFactory.createNamingService(properties);
		} catch (NacosException e) {
			throw new RegistryException("Failed to create Nacos naming service at " + config.address(), e);
		}
		// 不用进程级计数器命名（全仓"无可变静态状态"），用实例的身份哈希区分同 JVM 的多个实例
		String threadName = "rpc-registry-nacos-" + Integer.toHexString(System.identityHashCode(this)) + "-subscribe";
		this.subscriber = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, threadName);
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public void register(ServiceInstance instance) {
		ensureOpen();
		try {
			// 同 ip:port 再次注册时 Nacos 以新实例覆盖旧的：元数据以最新为准、不产生重复实例
			namingService.registerInstance(NacosNaming.serviceName(instance.key()), NacosNaming.groupName(instance.key()),
					NacosNaming.toNacos(instance));
		} catch (NacosException e) {
			throw new RegistryException("Failed to register " + instance + " at Nacos " + address, e);
		}
	}

	@Override
	public void unregister(ServiceInstance instance) {
		if (closed) {
			return;
		}
		try {
			namingService.deregisterInstance(NacosNaming.serviceName(instance.key()), NacosNaming.groupName(instance.key()),
					instance.host(), instance.port());
		} catch (NacosException e) {
			throw new RegistryException("Failed to unregister " + instance + " at Nacos " + address, e);
		}
	}

	/**
	 * 订阅：首次查询与挂监听在后台线程上进行，本方法最多等 {@code connect-wait-ms}。服务端可达时首次推送在返回前完成；
	 * 不可达时不抛异常、不推任何列表，后台一直重试，成功后补推（见类注释）。
	 */
	@Override
	public void subscribe(ServiceKey key, InstanceListener listener) {
		ensureOpen();
		SubscriptionId id = new SubscriptionId(key, listener);
		Subscription subscription = new Subscription(key, listener);
		if (subscriptions.putIfAbsent(id, subscription) != null) {
			return; // 同一监听者重复订阅同一服务：已经在收推送了
		}
		subscription.schedule(0);
		try {
			subscription.established.get(connectWaitMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.warn("Providers of {} not loaded from Nacos {} within {}ms; the subscriber gets the list as soon as "
					+ "Nacos is reachable (no empty list is pushed meanwhile)", key, address, connectWaitMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			// established 只会正常完成（失败一直重试）；防御性地记一笔
			log.warn("Subscription to {} at Nacos {} failed unexpectedly", key, address, e.getCause());
		}
	}

	@Override
	public void unsubscribe(ServiceKey key, InstanceListener listener) {
		if (closed) {
			return;
		}
		Subscription subscription = subscriptions.remove(new SubscriptionId(key, listener));
		if (subscription == null) {
			return;
		}
		subscription.deactivate(); // 先在本地失效：返回之后不会再回调，不依赖 Nacos 的 unsubscribe 是否成功
		try {
			namingService.unsubscribe(NacosNaming.serviceName(key), NacosNaming.groupName(key), subscription);
		} catch (NacosException e) {
			log.warn("Failed to unsubscribe {} from Nacos {} (listener already silenced locally): {}", key, address, e.toString());
		}
	}

	@Override
	public void close() {
		synchronized (this) {
			if (closed) {
				return;
			}
			closed = true;
		}
		subscriptions.values().forEach(Subscription::deactivate);
		subscriptions.clear();
		subscriber.shutdownNow();
		try {
			if (!subscriber.awaitTermination(5, TimeUnit.SECONDS)) {
				log.warn("Nacos subscribe thread did not stop within 5s (blocked in a Nacos client call)");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			// 关闭 gRPC 连接与客户端线程；临时实例随连接断开由服务端摘除
			namingService.shutDown();
		} catch (NacosException e) {
			log.warn("Failed to shut down Nacos naming service {}: {}", address, e.toString());
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("NacosRegistry for " + address + " is closed");
		}
	}

	private static List<ServiceInstance> toInstances(ServiceKey key, List<Instance> instances) {
		List<ServiceInstance> result = new ArrayList<>();
		for (Instance instance : instances) {
			if (instance.isHealthy() && instance.isEnabled()) {
				result.add(NacosNaming.fromNacos(key, instance));
			}
		}
		result.sort(Comparator.comparing(ServiceInstance::address));
		return List.copyOf(result);
	}

	private record SubscriptionId(ServiceKey key, InstanceListener listener) {
	}

	/**
	 * 注册给 Nacos 的监听者：把 NamingEvent 转成全量列表，并保证失效之后不再回调；同时负责"首次查询 + 挂监听"
	 * 这一步的后台重试。
	 */
	private final class Subscription implements EventListener {

		private final ServiceKey key;
		private final InstanceListener listener;
		private final String serviceName;
		private final String groupName;
		/** 首次列表已推送、Nacos 监听已挂上时完成。 */
		final CompletableFuture<Void> established = new CompletableFuture<>();
		private int failures; // 只在订阅线程上读写
		private boolean active = true; // guarded by this

		Subscription(ServiceKey key, InstanceListener listener) {
			this.key = key;
			this.listener = listener;
			this.serviceName = NacosNaming.serviceName(key);
			this.groupName = NacosNaming.groupName(key);
		}

		void schedule(long delayMs) {
			try {
				subscriber.schedule(this::establish, delayMs, TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException e) {
				// 已关闭
			}
		}

		/**
		 * 先推一次服务端当前状态（subscribe=false：直接查服务端，不读本地缓存），再挂监听接收后续变化。
		 * 两步之间存在一个很小的窗口：其间的变化依赖 Nacos 在订阅建立后推送的服务信息收敛（本实现未对此做额外补偿；
		 * 已由 NacosTimingProbeTest 验证不漏事件：20 轮"订阅同时注册"全部在 614ms 内可见，见 docs/probes/nacos-timing.md）。
		 * 任何一步失败都按退避重试，直到成功或订阅被取消。
		 */
		private void establish() {
			if (!isActive()) {
				return;
			}
			try {
				deliver(toInstances(key, namingService.getAllInstances(serviceName, groupName, false)));
				namingService.subscribe(serviceName, groupName, this);
				if (!isActive()) {
					// 挂监听期间被取消：撤销刚挂上的监听，避免 Nacos 侧残留
					namingService.unsubscribe(serviceName, groupName, this);
					return;
				}
				if (failures > 0) {
					log.info("Subscribed to {} at Nacos {} after {} failed attempt(s)", key, address, failures);
				}
				established.complete(null);
			} catch (NacosException | RuntimeException e) {
				failures++;
				long delay = Math.min(INITIAL_RETRY_MS * (1L << Math.min(failures - 1, 10)), MAX_RETRY_MS);
				if (failures == 1) {
					log.warn("Failed to subscribe to {} at Nacos {}, retrying in the background: {}", key, address, e.toString());
				} else {
					log.debug("Subscribe to {} at Nacos {} failed (attempt {}), retrying in {}ms: {}", key, address, failures,
							delay, e.toString());
				}
				schedule(delay);
			}
		}

		private synchronized boolean isActive() {
			return active && !closed;
		}

		@Override
		public void onEvent(Event event) {
			if (event instanceof NamingEvent namingEvent) {
				deliver(toInstances(key, namingEvent.getInstances()));
			}
		}

		synchronized void deliver(List<ServiceInstance> instances) {
			if (!active || closed) {
				return;
			}
			try {
				listener.onInstancesChanged(instances);
			} catch (RuntimeException e) {
				log.warn("Provider listener for {} threw", key, e);
			}
		}

		synchronized void deactivate() {
			active = false;
		}
	}
}
