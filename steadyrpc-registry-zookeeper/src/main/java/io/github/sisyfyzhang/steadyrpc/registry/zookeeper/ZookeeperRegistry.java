package io.github.sisyfyzhang.steadyrpc.registry.zookeeper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistrationListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryException;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 基于 ZooKeeper 的 {@link Registry}（主实现，DECISIONS D-007）。节点布局：
 * <pre>
 * /rpc/{interface:version:group}/providers/{host:port}   临时节点，数据 = weight=..&amp;proto=..&amp;ts=..&amp;pid=..
 * </pre>
 * 按接口分目录；地址作为节点名保证同一进程重复注册不会产生两个节点；元数据放节点数据而不是拼进节点名。
 *
 * <h2>韧性设计</h2>
 * <ul>
 *   <li><b>订阅</b>用 {@link CuratorCache}（ZK 3.6+ 持久递归 watch）。旧实现的一次性 watcher 在某次刷新失败后
 *       不再重挂，订阅静默死亡；CuratorCache 的持久 watch 在每次（重）连上后由 Curator 重新登记并全量重建缓存，
 *       单次读失败不会让订阅失效。NODE_CREATED / CHANGED / DELETED 事件不逐个转发，而是置位"待推送"并在
 *       {@code push-merge-ms} 窗口后从缓存取一次全量列表推送：一次事件风暴（初次加载 N 个子节点、会话恢复时的
 *       删除 + 重建）只产生一次回调；某个监听者已经收到过的相同列表不再重复推给它。</li>
 *   <li><b>会话过期重注册</b>：临时节点随旧会话消失。{@link ConnectionState#LOST} 或重连后会话 ID 变化时，
 *       把 {@link #registered} 里的全部实例在新会话上重新创建。创建是幂等的：节点已存在且属于当前会话就只在
 *       元数据不同时 setData；属于别的（旧）会话就删掉重建，保证节点绑在活着的会话上。
 *       丢失与重建通过 {@link RegistrationListener} 报告（LOST / 发现会话已换时报丢失，全部重建成功后报恢复），
 *       RpcServer 据此让 isRegistered() 与 health 在这段时间里为"不可被发现"。注意 ZK 整体不可达时，LOST 要等
 *       会话超时（{@code session-timeout-ms}）才由 Curator 判定，之前的 SUSPENDED 期间节点在服务端仍然存在。</li>
 *   <li><b>不在构造里等连接</b>：构造函数立即返回，ZK 不可达不会卡住 RpcServer.start() 或
 *       RpcClient 的构造。register / subscribe 各自最多等 {@code connect-wait-ms}（默认 1000ms）：register 等不到就
 *       抛 {@link RegistryException}，由 RpcServer 的后台注册退避重试；subscribe 等不到就先返回、不推首次列表，
 *       缓存在连上后完成加载时补推（"没连上"不能被当成"没有提供者"推一个空列表）。</li>
 *   <li><b>线程</b>：每个实例一个进程内递增的 {@code instanceId}，Curator 线程与本类的事件线程命名为
 *       {@code rpc-registry-zk-{instanceId}-{n}}，ZooKeeper 客户端自己的 SendThread / EventThread 命名为
 *       {@code rpc-registry-zk-{instanceId}-SendThread(..)} / {@code -EventThread}（见 {@link NamedZookeeperFactory}）。
 *       同一 JVM 两个实例的线程可区分，close 之后可以按前缀断言无残留。</li>
 * </ul>
 * 推空保护不在这里：本实现忠实转发缓存里看到的列表（包括空列表），"空列表可信吗"由消费端 ServiceDirectory 决定。
 *
 * <h2>已知窗口：ZK 不可达时，下线节点残留至会话超时</h2>
 * 进程崩溃（或被 SIGKILL）且没来得及关闭会话时，它的临时节点要等 ZooKeeper 服务端判定会话过期才删除，
 * 最长约 {@code session-timeout-ms}；同理，provider 与 ZK 之间网络中断时，节点也会在会话超时内一直"看起来在线"。
 * 这是 ZooKeeper 会话模型（CP：服务端只凭心跳超时判定客户端死亡）的固有结果，客户端无法缩短。期间消费端仍会
 * 选到它：连接失败走 failover / 重连退避，阶段 4 的熔断与节点摘除会把它从选择中更快拿掉。
 * 正常停机（RpcServer.close）先显式摘除节点、再关会话，不受这个窗口影响。
 * 默认会话超时因此从 60s 收紧到 30s：窗口减半，而 30s 仍远大于 GC 停顿与网络抖动的常见时长，不会频繁误判过期。
 *
 * <p>支持的 {@link RegistryConfig#properties()}：
 * <ul>
 *   <li>{@code session-timeout-ms}（默认 30000）：上面那个窗口的上限；</li>
 *   <li>{@code connection-timeout-ms}（默认 5000）：单次建连等待。从 15s 收紧：断连瞬间发起的一次 ZK 操作不该阻塞到十几秒；</li>
 *   <li>{@code connect-wait-ms}（默认 1000）：register / subscribe 等待连接的上限；</li>
 *   <li>{@code push-merge-ms}（默认 50）：事件合并窗口。</li>
 * </ul>
 * 操作重试为指数退避 500ms 起、最多 3 次（原为 1000ms 起）：与 connection-timeout 一起，把断连时单次操作的最坏阻塞
 * 从近一分钟压到十几秒以内；更长时间的不可达由上层（RpcServer 的后台注册重试、订阅补推）处理。
 */
public class ZookeeperRegistry implements Registry {

	private static final Logger log = LoggerFactory.getLogger(ZookeeperRegistry.class);
	static final String ROOT = "/rpc";
	private static final String PROVIDERS = "providers";
	/**
	 * 进程内实例序号，只用于线程命名。这是全仓"无可变静态状态"原则的一个刻意例外：它不承载任何业务状态，
	 * 两个实例共享它不会互相影响，而线程名必须跨实例唯一才能做"关闭后无残留"的断言。
	 */
	private static final AtomicInteger INSTANCE_IDS = new AtomicInteger();
	private static final long REREGISTER_RETRY_MS = 1000;
	static final String DEFAULT_SESSION_TIMEOUT_MS = "30000";
	static final String DEFAULT_CONNECTION_TIMEOUT_MS = "5000";
	static final int RETRY_BASE_SLEEP_MS = 500;
	static final int RETRY_MAX = 3;

	private final int instanceId;
	private final String threadPrefix;
	private final String address;
	private final long connectWaitMs;
	private final long pushMergeMs;
	private final CuratorFramework client;
	/** 本实例的事件线程：合并推送、会话恢复后的重注册与延迟删除都在这里串行执行，不占用 Curator 的回调线程。 */
	private final ScheduledExecutorService events;
	/**
	 * 交给 Curator 的 runSafe 执行器（CuratorCache 的监听回调在这里执行）。必须自己提供并在 close 时关闭：
	 * Curator 5.8 默认按 threadFactory 新建一个单线程池，但 {@code CuratorFramework.close()} 从不关闭它，每个实例泄漏一个线程。
	 */
	private final ExecutorService runSafe;

	/** 已注册实例，按节点路径索引（同地址重复注册时新元数据覆盖旧的）。增删与重建都在 {@link #registrationLock} 下进行。 */
	private final Map<String, ServiceInstance> registered = new ConcurrentHashMap<>();
	/**
	 * 未连上时 unregister 的节点路径 → 当时的会话 ID。连上后只删"仍属于那个会话"的节点：会话已换则节点随旧会话消失，
	 * 同地址上别的进程新建的节点不能误删。
	 */
	private final Map<String, Long> pendingDeletes = new ConcurrentHashMap<>();
	private final Object registrationLock = new Object();

	private final Object watchesLock = new Object();
	private final Map<ServiceKey, ProvidersWatch> watches = new HashMap<>(); // guarded by watchesLock

	private volatile boolean closed;
	/** 最近一次连上时的会话 ID；0 表示还没连上过。 */
	private volatile long sessionId;
	/** 自上次成功重注册以来是否发生过 LOST / 会话变化，需要重注册。 */
	private final AtomicBoolean reRegisterNeeded = new AtomicBoolean();
	/** 已向 {@link #registrationListeners} 报告丢失、尚未报告恢复：保证 lost / restored 成对出现。 */
	private final AtomicBoolean registrationsLost = new AtomicBoolean();
	private final List<RegistrationListener> registrationListeners = new CopyOnWriteArrayList<>();

	public ZookeeperRegistry(RegistryConfig config) {
		this.instanceId = INSTANCE_IDS.incrementAndGet();
		this.threadPrefix = "rpc-registry-zk-" + instanceId;
		this.address = config.address();
		int sessionTimeoutMs = Integer.parseInt(config.property("session-timeout-ms", DEFAULT_SESSION_TIMEOUT_MS));
		int connectionTimeoutMs = Integer.parseInt(config.property("connection-timeout-ms", DEFAULT_CONNECTION_TIMEOUT_MS));
		this.connectWaitMs = Long.parseLong(config.property("connect-wait-ms", "1000"));
		this.pushMergeMs = Long.parseLong(config.property("push-merge-ms", "50"));
		ThreadFactory threadFactory = namedThreadFactory(threadPrefix + "-");
		this.runSafe = Executors.newSingleThreadExecutor(threadFactory);
		this.client = CuratorFrameworkFactory.builder()
				.connectString(address)
				.sessionTimeoutMs(sessionTimeoutMs)
				.connectionTimeoutMs(connectionTimeoutMs)
				.retryPolicy(new ExponentialBackoffRetry(RETRY_BASE_SLEEP_MS, RETRY_MAX))
				.threadFactory(threadFactory)
				.zookeeperFactory(new NamedZookeeperFactory(threadPrefix))
				.runSafeService(runSafe)
				.build();
		this.events = Executors.newSingleThreadScheduledExecutor(threadFactory);
		client.getConnectionStateListenable().addListener((c, state) -> onConnectionStateChanged(state));
		// start() 只启动后台连接，不等待：注册中心不可达不应拖住调用方（RpcServer.start / RpcClient 构造）
		client.start();
	}

	static String providersPath(ServiceKey key) {
		return ROOT + "/" + key + "/" + PROVIDERS;
	}

	static String instancePath(ServiceInstance instance) {
		return providersPath(instance.key()) + "/" + instance.address();
	}

	// ------------------------------------------------------------------ 注册

	@Override
	public void register(ServiceInstance instance) {
		ensureOpen();
		String path = instancePath(instance);
		awaitConnected("register " + path);
		synchronized (registrationLock) {
			ensureOpen();
			try {
				createOrUpdate(path, instance);
			} catch (Exception e) {
				throw new RegistryException("Failed to register " + path + " at ZooKeeper " + address, e);
			}
			pendingDeletes.remove(path);
			registered.put(path, instance);
		}
	}

	/**
	 * 幂等地让 {@code path} 成为当前会话的临时节点、数据为实例元数据。会话恢复后的重注册与普通注册共用它。
	 */
	private void createOrUpdate(String path, ServiceInstance instance) throws Exception {
		byte[] data = InstanceMetadata.encode(instance.metadata()).getBytes(StandardCharsets.UTF_8);
		try {
			client.create().creatingParentContainersIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path, data);
			return;
		} catch (KeeperException.NodeExistsException e) {
			// 往下：判断已有节点属于谁
		}
		Stat stat = new Stat();
		byte[] existing;
		try {
			existing = client.getData().storingStatIn(stat).forPath(path);
		} catch (KeeperException.NoNodeException e) {
			existing = null;
		}
		if (existing != null && stat.getEphemeralOwner() == currentSessionId()) {
			if (!Arrays.equals(existing, data)) {
				client.setData().withVersion(stat.getVersion()).forPath(path, data);
			}
			return;
		}
		// 不属于当前会话（上一个会话尚未在服务端过期、或同地址的旧进程留下的）：删掉重建，确保节点绑在活着的会话上
		client.delete().quietly().forPath(path);
		client.create().creatingParentContainersIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path, data);
	}

	@Override
	public void unregister(ServiceInstance instance) {
		if (closed) {
			return;
		}
		String path = instancePath(instance);
		synchronized (registrationLock) {
			registered.remove(path);
			if (!client.getZookeeperClient().isConnected()) {
				// 不阻塞下线流程：连上后补删；若会话已换，节点本来就随旧会话消失了
				pendingDeletes.put(path, sessionId);
				log.info("ZooKeeper {} not connected, {} will be deleted once reconnected", address, path);
				return;
			}
			try {
				client.delete().quietly().forPath(path);
			} catch (Exception e) {
				pendingDeletes.put(path, sessionId);
				log.warn("Failed to delete {} now, will retry after reconnect: {}", path, e.toString());
			}
		}
	}

	private void awaitConnected(String action) {
		if (client.getZookeeperClient().isConnected()) {
			return;
		}
		boolean connected;
		try {
			connected = client.blockUntilConnected((int) connectWaitMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RegistryException("Interrupted while waiting for ZooKeeper " + address + " to " + action, e);
		}
		if (!connected) {
			throw new RegistryException("Not connected to ZooKeeper " + address + " within " + connectWaitMs
					+ "ms, cannot " + action + " (Curator keeps reconnecting in the background)");
		}
	}

	// ------------------------------------------------------------------ 会话

	private void onConnectionStateChanged(ConnectionState state) {
		if (closed) {
			return;
		}
		switch (state) {
			case CONNECTED, RECONNECTED -> {
				long current = currentSessionId();
				long previous = sessionId;
				sessionId = current;
				boolean sessionChanged = previous != 0 && previous != current;
				if (sessionChanged) {
					reRegisterNeeded.set(true);
					// 没收到 LOST 就发现会话已换（旧会话在断连期间过期）：节点同样已随旧会话消失
					reportLost();
				}
				log.info("ZooKeeper {} {} (session 0x{}{})", address, state, Long.toHexString(current),
						sessionChanged ? ", previous session 0x" + Long.toHexString(previous) + " is gone" : "");
				submit(this::afterConnected);
			}
			case SUSPENDED -> log.warn("ZooKeeper {} connection SUSPENDED; keeping the last known provider lists", address);
			case LOST -> {
				reRegisterNeeded.set(true);
				log.warn("ZooKeeper {} session LOST; ephemeral nodes of this registry are gone and will be re-created on reconnect",
						address);
				reportLost();
			}
			default -> log.info("ZooKeeper {} {}", address, state);
		}
	}

	/** 连上之后（事件线程）：会话变过就把已注册实例全部重建；再补做断连期间积压的删除。 */
	private void afterConnected() {
		if (closed) {
			return;
		}
		if (reRegisterNeeded.get() && !reRegisterAll()) {
			return;
		}
		for (Map.Entry<String, Long> pending : List.copyOf(pendingDeletes.entrySet())) {
			String path = pending.getKey();
			synchronized (registrationLock) {
				if (closed || registered.containsKey(path)) {
					pendingDeletes.remove(path);
					continue;
				}
				try {
					Stat stat = client.checkExists().forPath(path);
					if (stat != null && stat.getEphemeralOwner() == pending.getValue()) {
						client.delete().quietly().withVersion(stat.getVersion()).forPath(path);
					}
					pendingDeletes.remove(path);
				} catch (Exception e) {
					log.warn("Deferred delete of {} failed: {}", path, e.toString());
				}
			}
		}
	}

	/** @return 是否全部成功；失败时（且仍连着）1 秒后重试，断开则等下一次 RECONNECTED */
	private boolean reRegisterAll() {
		reRegisterNeeded.set(false);
		int count = 0;
		for (Map.Entry<String, ServiceInstance> entry : List.copyOf(registered.entrySet())) {
			synchronized (registrationLock) {
				// 等锁期间可能被摘除或用新元数据覆盖：以表里现在的为准
				ServiceInstance current = registered.get(entry.getKey());
				if (closed || current == null) {
					continue;
				}
				try {
					createOrUpdate(entry.getKey(), current);
					count++;
				} catch (Exception e) {
					reRegisterNeeded.set(true);
					log.warn("Re-registering {} on the new ZooKeeper session failed, will retry: {}", entry.getKey(), e.toString());
					if (!closed && client.getZookeeperClient().isConnected()) {
						schedule(this::afterConnected, REREGISTER_RETRY_MS);
					}
					return false;
				}
			}
		}
		if (count > 0) {
			log.info("Re-registered {} instance(s) on ZooKeeper {} session 0x{}", count, address, Long.toHexString(sessionId));
		}
		// 期间又丢了（再次 LOST 会重新置位 reRegisterNeeded）就不报恢复，等下一轮重建
		if (!reRegisterNeeded.get() && registrationsLost.compareAndSet(true, false)) {
			notifyRegistrationListeners(false);
		}
		return true;
	}

	@Override
	public boolean addRegistrationListener(RegistrationListener listener) {
		ensureOpen();
		registrationListeners.add(listener);
		return true;
	}

	private void reportLost() {
		if (registrationsLost.compareAndSet(false, true)) {
			notifyRegistrationListeners(true);
		}
	}

	private void notifyRegistrationListeners(boolean lost) {
		for (RegistrationListener listener : registrationListeners) {
			if (closed) {
				return;
			}
			try {
				if (lost) {
					listener.onRegistrationsLost();
				} else {
					listener.onRegistrationsRestored();
				}
			} catch (RuntimeException e) {
				log.warn("Registration listener threw; other listeners are still notified", e);
			}
		}
	}

	private long currentSessionId() {
		try {
			return client.getZookeeperClient().getZooKeeper().getSessionId();
		} catch (Exception e) {
			return 0;
		}
	}

	// ------------------------------------------------------------------ 订阅

	@Override
	public void subscribe(ServiceKey key, InstanceListener listener) {
		ProvidersWatch watch;
		synchronized (watchesLock) {
			ensureOpen();
			watch = watches.get(key);
			if (watch == null) {
				watch = new ProvidersWatch(key);
				watches.put(key, watch);
				watch.start();
			}
			watch.listeners.add(listener);
		}
		if (watch.awaitInitialized(connectWaitMs)) {
			watch.pushTo(listener);
		} else {
			log.warn("Providers of {} not loaded from ZooKeeper {} within {}ms; the subscriber gets the list as soon as "
					+ "the connection is established (no empty list is pushed meanwhile)", key, address, connectWaitMs);
		}
	}

	@Override
	public void unsubscribe(ServiceKey key, InstanceListener listener) {
		ProvidersWatch watch;
		synchronized (watchesLock) {
			watch = watches.get(key);
		}
		if (watch == null) {
			return;
		}
		watch.removeListener(listener); // 在推送锁下移除：返回之后不会再回调它
		boolean last = false;
		synchronized (watchesLock) {
			if (watch.listeners.isEmpty() && watches.get(key) == watch) {
				watches.remove(key);
				last = true;
			}
		}
		if (last) {
			watch.deactivate();
		}
	}

	// ------------------------------------------------------------------ 关闭

	@Override
	public void close() {
		List<ProvidersWatch> toClose;
		synchronized (watchesLock) {
			if (closed) {
				return;
			}
			closed = true;
			toClose = new ArrayList<>(watches.values());
			watches.clear();
		}
		// 先停订阅：deactivate 拿推送锁，等正在进行的推送结束，之后不会再有任何回调
		toClose.forEach(ProvidersWatch::deactivate);
		events.shutdown();
		synchronized (registrationLock) {
			registered.clear();
			pendingDeletes.clear();
		}
		registrationListeners.clear();
		// 关闭会话：服务端立即删除本会话的临时节点
		client.close();
		runSafe.shutdown();
		awaitTermination(events);
		awaitTermination(runSafe);
	}

	private static void awaitTermination(ExecutorService executor) {
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("ZookeeperRegistry for " + address + " is closed");
		}
	}

	private void submit(Runnable task) {
		schedule(task, 0);
	}

	private void schedule(Runnable task, long delayMs) {
		try {
			events.schedule(() -> {
				try {
					task.run();
				} catch (RuntimeException e) {
					log.warn("ZooKeeper registry task failed", e);
				}
			}, delayMs, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// 已关闭
		}
	}

	// ------------------------------------------------------------------ 测试可见

	CuratorFramework curator() {
		return client;
	}

	/** 本实例全部线程名的公共前缀（含末尾的 {@code -}）。 */
	String threadNamePrefix() {
		return threadPrefix + "-";
	}

	/**
	 * 一个服务的提供者订阅：一个 {@link CuratorCache} + 若干监听者。所有回调在本对象的锁下进行，因此
	 * 同一监听者收到的列表不会乱序，取消订阅 / 关闭返回之后不会再有回调。
	 */
	private final class ProvidersWatch {

		private final ServiceKey key;
		private final String path;
		private final CuratorCache cache;
		final List<InstanceListener> listeners = new CopyOnWriteArrayList<>();
		private final CountDownLatch initialized = new CountDownLatch(1);
		private final AtomicBoolean pushScheduled = new AtomicBoolean();
		/** 每个监听者最后收到的列表：只在它看到的列表真的变了时才回调，订阅时的首次推送与初始化推送不会重复。 */
		private final Map<InstanceListener, List<ServiceInstance>> delivered = new HashMap<>(); // guarded by this
		private boolean active = true; // guarded by this

		ProvidersWatch(ServiceKey key) {
			this.key = key;
			this.path = providersPath(key);
			this.cache = CuratorCache.builder(client, path)
					.withExceptionHandler(e -> log.warn("CuratorCache error for {} (the cache rebuilds on reconnect): {}", path, e.toString()))
					.build();
			cache.listenable().addListener(CuratorCacheListener.builder()
					.forCreates(this::onNodeEvent)
					.forChanges((oldNode, node) -> onNodeEvent(node))
					.forDeletes(this::onNodeEvent)
					.forInitialized(() -> {
						initialized.countDown();
						schedulePush();
					})
					.build());
		}

		void start() {
			// 路径不存在也没关系：持久 watch 会在 provider 第一次创建它时收到事件
			cache.start();
		}

		private void onNodeEvent(ChildData node) {
			// 初次加载期间的逐个事件不推：forInitialized 会推一次全量
			if (initialized.getCount() == 0 && isProviderNode(node.getPath())) {
				schedulePush();
			}
		}

		private boolean isProviderNode(String nodePath) {
			return path.equals(ZKPaths.getPathAndNode(nodePath).getPath());
		}

		boolean awaitInitialized(long timeoutMs) {
			try {
				return initialized.await(timeoutMs, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		/** 合并窗口：窗口内的多次事件只触发一次推送，推送时再从缓存读最新全量。 */
		private void schedulePush() {
			if (pushScheduled.compareAndSet(false, true)) {
				schedule(this::pushAll, pushMergeMs);
			}
		}

		private synchronized void pushAll() {
			pushScheduled.set(false);
			if (!active || closed) {
				return;
			}
			List<ServiceInstance> snapshot = snapshot();
			for (InstanceListener listener : listeners) {
				deliver(listener, snapshot);
			}
		}

		/** 新订阅者的首次推送（在订阅线程上同步执行）。 */
		synchronized void pushTo(InstanceListener listener) {
			if (active && !closed && listeners.contains(listener)) {
				deliver(listener, snapshot());
			}
		}

		private void deliver(InstanceListener listener, List<ServiceInstance> snapshot) {
			if (snapshot.equals(delivered.put(listener, snapshot))) {
				return;
			}
			try {
				listener.onInstancesChanged(snapshot);
			} catch (RuntimeException e) {
				log.warn("Provider listener for {} threw; other listeners are still notified", key, e);
			}
		}

		synchronized void removeListener(InstanceListener listener) {
			listeners.remove(listener);
			delivered.remove(listener);
		}

		void deactivate() {
			synchronized (this) {
				active = false;
				listeners.clear();
				delivered.clear();
			}
			cache.close();
		}

		private List<ServiceInstance> snapshot() {
			List<ServiceInstance> instances = new ArrayList<>();
			cache.stream().filter(node -> isProviderNode(node.getPath())).forEach(node -> {
				ServiceInstance instance = toInstance(node);
				if (instance != null) {
					instances.add(instance);
				}
			});
			instances.sort(Comparator.comparing(ServiceInstance::address));
			return List.copyOf(instances);
		}

		private ServiceInstance toInstance(ChildData node) {
			String child = ZKPaths.getNodeFromPath(node.getPath());
			int colon = child.lastIndexOf(':');
			if (colon <= 0) {
				log.warn("Ignoring malformed provider node {}", node.getPath());
				return null;
			}
			try {
				byte[] data = node.getData();
				Map<String, String> metadata = InstanceMetadata.decode(data == null ? "" : new String(data, StandardCharsets.UTF_8));
				return new ServiceInstance(key, child.substring(0, colon), Integer.parseInt(child.substring(colon + 1)), metadata);
			} catch (RuntimeException e) {
				log.warn("Ignoring malformed provider node {}: {}", node.getPath(), e.toString());
				return null;
			}
		}
	}

	private static ThreadFactory namedThreadFactory(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
