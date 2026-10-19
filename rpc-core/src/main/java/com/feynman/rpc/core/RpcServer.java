package com.feynman.rpc.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.config.ServiceOptions;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.filter.DeadlineServerFilter;
import com.feynman.rpc.core.filter.Filter;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializers;
import com.feynman.rpc.core.server.NettyServer;
import com.feynman.rpc.core.server.ServiceDispatcher;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 服务端入口：一个可以 new、start、close 的对象，不依赖任何容器。
 *
 * <pre>{@code
 * RpcServer server = RpcServer.builder()
 *         .port(9000)
 *         .registry(RegistryConfig.zookeeper("localhost:2181"))
 *         .build();
 * server.export(UserFacade.class, new UserFacadeImpl());
 * server.start();                       // 绑定端口后立即返回，注册在后台进行
 * server.registered().get(10, SECONDS); // 需要"已可被发现"时再等它（如 readiness 探针）
 * ...
 * server.close();   // 取消注册重试 → 摘注册 → 拒新请求 → 等在途 → 关端口与连接 → 关线程组 → 关注册中心
 * }</pre>
 *
 * <p>为什么 {@link #start()} 不阻塞：旧实现在 Spring 的 ContextRefreshedEvent 里 {@code closeFuture().sync()}，
 * 容器永远停在 refresh 上，Boot 应用的 run() 不返回、ApplicationRunner 不执行。进程保活交给 Netty 的
 * 非守护 IO 线程，停机交给调用方的 {@link #close()}（Spring 下由 SmartLifecycle.stop 调用）。
 *
 * <p>为什么注册是异步的：绑定端口与注册刻意分成两步、各自处理失败。先 bind 成功再注册，消费端不会拿到一个
 * 还连不上的地址；而注册中心不可达只是"暂时不可被发现"，不是"服务起不来"——它不该让 start() 卡住
 * （任务 1 的实现会在 ZK 不可达时同步阻塞到 15s），更不该拆掉已经健康的监听端口。注册在专用的
 * {@code rpc-server-registry-*} 线程上按指数退避（500ms 起、封顶 10s）无限重试，每次失败打 warn、
 * 每个服务首次成功打 info。不放在 Netty IO 线程上：注册中心客户端的调用会阻塞（ZK 最多等 connect-wait-ms），
 * 而 boss 线程要负责接受连接。是否已可被发现由 {@link #registered()} / {@link #isRegistered()} 暴露。
 *
 * <p>状态机：{@link State#NEW} → {@link State#STARTED} → {@link State#CLOSING}（取消注册重试、摘注册、可选宽限期；请求照常处理）
 * → {@link State#DRAINING}（新请求回 SERVER_OVERLOADED "server shutting down"，等在途）→ {@link State#CLOSED}。
 * 优雅下线的完整顺序与理由见 {@link #close()}。
 */
public final class RpcServer implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(RpcServer.class);
	private static final long INITIAL_REGISTER_BACKOFF_MS = 500;
	private static final long MAX_REGISTER_BACKOFF_MS = 10_000;
	private static final String PROTOCOL_VERSION = "2";

	/** 服务端生命周期状态，见类注释。 */
	public enum State {
		/** 已创建，未绑定端口。 */
		NEW,
		/** 已绑定端口，正常服务（注册可能仍在后台进行）。 */
		STARTED,
		/** close() 已开始：正在取消注册重试、从注册中心摘除、等待 deregisterGrace；请求照常处理（消费端还没收到下线推送）。 */
		CLOSING,
		/** 已摘注册，拒绝新请求（SERVER_OVERLOADED "server shutting down"），等待在途请求完成；心跳照常。 */
		DRAINING,
		/** 端口、连接、线程组、业务执行器、注册中心会话都已关闭。 */
		CLOSED
	}

	private final RpcServerConfig config;
	private final Serializers serializers;
	private final ServiceDispatcher dispatcher;
	private final List<ExportedService> exported = new CopyOnWriteArrayList<>();
	private final Set<ServiceInstance> registered = ConcurrentHashMap.newKeySet();

	// ---- 后台注册的状态：除 registry 外都由 this 的监视器保护 ----
	/** 已暴露、尚未注册成功的实例，按暴露顺序注册。 */
	private final Set<ServiceInstance> pendingRegistration = new LinkedHashSet<>();
	/** "当前全部已暴露服务都已注册"的 future；它完成之后再 export 会换一个新的。 */
	private CompletableFuture<Void> registeredFuture = new CompletableFuture<>();
	private RegistryFactory registryFactory;
	private ScheduledExecutorService registrar;
	private ScheduledFuture<?> nextAttempt;
	private long backoffMs = INITIAL_REGISTER_BACKOFF_MS;
	private int failedAttempts;

	private volatile State state = State.NEW;
	private volatile NettyServer transport;
	/** 只在注册线程上创建；close 在注册线程结束之后读取。 */
	private volatile Registry registry;
	private volatile int boundPort = -1;
	private volatile String advertisedHost;
	private volatile long startedAt;

	private RpcServer(RpcServerConfig config) {
		this.config = config;
		// 构造只做校验、不起线程不连注册中心：Spring 容器里即使最终没有服务要暴露，创建这个 bean 也没有副作用
		this.serializers = Serializers.load(config.serializer(), config.serializerOptions());
		List<Filter> filters = new ArrayList<>();
		filters.add(new DeadlineServerFilter());
		filters.addAll(config.serverFilters());
		this.dispatcher = new ServiceDispatcher(filters, serializers, config.remoteStackTrace(), config.remoteStackTraceMaxBytes());
	}

	public static RpcServerConfig.Builder<RpcServer> builder() {
		return new RpcServerConfig.Builder<>(RpcServer::new);
	}

	public static RpcServer create(RpcServerConfig config) {
		return new RpcServer(config);
	}

	public RpcServerConfig config() {
		return config;
	}

	public <T> void export(Class<T> itf, T impl) {
		export(itf, impl, ServiceOptions.defaults());
	}

	/**
	 * 暴露一个服务。通常在 {@link #start()} 之前调用；启动之后调用也可以，会在后台注册（{@link #registered()}
	 * 随之换成"包括它在内全部注册完"的 future）。服务端 Filter 链在这里为该服务组装一次。同一接口可以用不同的
	 * version / group 暴露多份；完全相同的 interface:version:group 重复暴露会被拒绝。
	 */
	public synchronized <T> void export(Class<T> itf, T impl, ServiceOptions options) {
		if (state != State.NEW && state != State.STARTED) {
			throw new IllegalStateException("RpcServer is closed");
		}
		if (!itf.isInterface()) {
			throw new IllegalArgumentException("exported type must be an interface: " + itf.getName());
		}
		if (!itf.isInstance(impl)) {
			throw new IllegalArgumentException(impl.getClass().getName() + " does not implement " + itf.getName());
		}
		ServiceKey key = ServiceKey.of(itf, options.version(), options.group());
		int weight = options.weight() > 0 ? options.weight() : config.weight();
		dispatcher.register(key, itf, impl);
		ExportedService service = new ExportedService(key, weight);
		exported.add(service);
		log.info("Exported {} -> {}", key, impl.getClass().getName());
		if (state == State.STARTED && registrar != null) {
			if (registeredFuture.isDone()) {
				registeredFuture = new CompletableFuture<>();
			}
			pendingRegistration.add(instanceOf(service));
			scheduleRegistration(0);
		}
	}

	/**
	 * 绑定端口后立即返回；向注册中心注册所有已暴露服务在后台进行（见类注释），用 {@link #registered()} 观察结果。
	 * 重复调用是空操作。注册中心类型（SPI 扩展名）在绑定之前解析：名字写错这种重试也不会好的配置错误直接抛出。
	 */
	public synchronized void start() {
		if (state == State.STARTED) {
			return;
		}
		if (state != State.NEW) {
			throw new IllegalStateException("RpcServer is closed");
		}
		if (config.registry() != null) {
			registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(config.registry().type());
		}
		byte fallbackCode = serializers.preferred().code();
		NettyServer server = new NettyServer(dispatcher, config.executor(), fallbackCode);
		InetSocketAddress bound;
		try {
			bound = server.bind(config.port());
		} catch (RuntimeException e) {
			server.close(true);
			throw e;
		}
		this.transport = server;
		this.boundPort = bound.getPort();
		this.advertisedHost = config.host() != null ? config.host() : detectLocalHost();
		this.startedAt = System.currentTimeMillis();
		this.state = State.STARTED;
		log.info("RpcServer bound on port {} (advertised as {}:{}), services={}",
				boundPort, advertisedHost, boundPort, exportedServices());

		if (registryFactory == null) {
			// 没有配置注册中心（直连 / 测试）：没有需要注册的东西
			registeredFuture.complete(null);
			return;
		}
		registrar = Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("rpc-server-registry", true));
		for (ExportedService service : exported) {
			pendingRegistration.add(instanceOf(service));
		}
		scheduleRegistration(0);
	}

	/** 安排一轮注册；已有一轮在排队或在执行时不重复安排（那一轮会处理所有待注册实例）。调用方持有 this 的锁。 */
	private void scheduleRegistration(long delayMs) {
		if (state != State.STARTED || registrar == null) {
			return;
		}
		if (nextAttempt != null && !nextAttempt.isDone()) {
			return;
		}
		try {
			nextAttempt = registrar.schedule(this::attemptRegistration, delayMs, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// close() 已关闭注册线程
		}
	}

	/**
	 * 一轮注册（在注册线程上）：必要时先创建 Registry，再按顺序注册所有待注册实例；任何一步失败就按退避安排下一轮。
	 * 待注册集合清空时完成 {@link #registered()}。
	 */
	private void attemptRegistration() {
		if (state != State.STARTED) {
			return;
		}
		Registry current = registry;
		if (current == null) {
			try {
				current = registryFactory.create(config.registry());
			} catch (RuntimeException e) {
				onRegistrationFailure("create registry " + config.registry(), e);
				return;
			}
			registry = current;
		}
		while (true) {
			ServiceInstance next;
			synchronized (this) {
				if (state != State.STARTED) {
					return;
				}
				if (pendingRegistration.isEmpty()) {
					// 先清掉 nextAttempt（本轮还没返回、尚未 done）：此后 export 的新服务能安排新的一轮，不会被漏掉
					nextAttempt = null;
					failedAttempts = 0;
					backoffMs = INITIAL_REGISTER_BACKOFF_MS;
					registeredFuture.complete(null);
					return;
				}
				next = pendingRegistration.iterator().next();
			}
			try {
				current.register(next);
			} catch (RuntimeException e) {
				onRegistrationFailure("register " + next.key() + " at " + next.address(), e);
				return;
			}
			int failuresBefore;
			synchronized (this) {
				pendingRegistration.remove(next);
				registered.add(next);
				failuresBefore = failedAttempts;
			}
			if (failuresBefore == 0) {
				log.info("Registered {} at {}", next.key(), next.address());
			} else {
				log.info("Registered {} at {} after {} failed attempt(s)", next.key(), next.address(), failuresBefore);
			}
		}
	}

	private synchronized void onRegistrationFailure(String action, RuntimeException e) {
		if (state != State.STARTED) {
			return;
		}
		failedAttempts++;
		long delay = backoffMs;
		backoffMs = Math.min(backoffMs * 2, MAX_REGISTER_BACKOFF_MS);
		log.warn("Failed to {} (attempt {}), retrying in {}ms; port {} keeps serving but is not discoverable yet: {}",
				action, failedAttempts, delay, boundPort, e.toString());
		// 本轮任务还没返回，nextAttempt 仍指向它自己（未 done）：先清掉才能安排下一轮
		nextAttempt = null;
		scheduleRegistration(delay);
	}

	/**
	 * 全部已暴露服务都在注册中心注册成功时完成。用途：readiness 探针、测试里"等到可被发现再发请求"。
	 * <ul>
	 *   <li>未配置注册中心时，{@link #start()} 之后立即完成（没有需要注册的东西）；</li>
	 *   <li>注册中心不可达时一直不完成——后台在无限重试，端口照常服务直连请求；</li>
	 *   <li>{@link #close()} 时若尚未完成，以 {@link java.util.concurrent.CancellationException} 为原因异常结束；</li>
	 *   <li>完成之后再 export 新服务，会换成一个新的 future（等新服务也注册上才完成）。所以要观察
	 *       "现在是否全部已注册"，每次重新调用本方法或用 {@link #isRegistered()}，不要缓存旧的返回值。</li>
	 * </ul>
	 * 返回的是一份副本：调用方 complete / cancel 它不会影响服务端状态。
	 */
	public synchronized CompletableFuture<Void> registered() {
		return registeredFuture.copy();
	}

	/** 当前全部已暴露服务是否都已注册成功。未 start 或已 close 时为 false。 */
	public synchronized boolean isRegistered() {
		return state == State.STARTED && registeredFuture.isDone() && !registeredFuture.isCompletedExceptionally();
	}

	private ServiceInstance instanceOf(ExportedService service) {
		Map<String, String> metadata = new LinkedHashMap<>();
		metadata.put(InstanceMetadata.WEIGHT, String.valueOf(service.weight()));
		metadata.put(InstanceMetadata.PROTOCOL, PROTOCOL_VERSION);
		metadata.put(InstanceMetadata.TIMESTAMP, String.valueOf(startedAt));
		metadata.put(InstanceMetadata.PID, String.valueOf(ProcessHandle.current().pid()));
		return new ServiceInstance(service.key(), advertisedHost, boundPort, metadata);
	}

	private static String detectLocalHost() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (Exception e) {
			log.warn("Failed to detect local address, advertising 127.0.0.1", e);
			return "127.0.0.1";
		}
	}

	/** 实际监听的端口（配置为 0 时由系统分配）；未启动时返回配置值。 */
	public int port() {
		return boundPort >= 0 ? boundPort : config.port();
	}

	public List<ServiceKey> exportedServices() {
		List<ServiceKey> keys = new ArrayList<>();
		for (ExportedService service : exported) {
			keys.add(service.key());
		}
		return keys;
	}

	public boolean isRunning() {
		return state == State.STARTED;
	}

	/** 当前生命周期状态（见 {@link State}）。 */
	public State state() {
		return state;
	}

	/**
	 * 已进入服务端（排队或执行中，含业务方法已返回未完成 future 的）、尚未回包的请求数。停机时 close() 等它归零；
	 * 也适合作为指标与测试里的泄漏断言。未启动时为 0。
	 */
	public int inFlight() {
		NettyServer server = transport;
		return server == null ? 0 : server.inFlight();
	}

	/**
	 * 当前已接受、尚未关闭的入站连接数。只读，用于观测（指标的连接数 Gauge）与测试；未启动或已关闭时为 0。
	 */
	public int activeConnections() {
		NettyServer server = transport;
		return server == null ? 0 : server.activeConnections();
	}

	/**
	 * 正在业务执行器线程上运行的任务数（不含排队中的、不含业务方法已返回 future 正在等待完成的）。
	 * 服务端异步到底的可观测证据：业务返回未完成的 future 后它应立即回落。
	 */
	public int runningBusinessTasks() {
		NettyServer server = transport;
		return server == null ? 0 : server.runningBusinessTasks();
	}

	/**
	 * 优雅下线，按顺序：
	 * <ol>
	 *   <li><b>取消注册重试</b>：停掉后台注册线程并等它退出，"正在进行的那次注册"要么没成功、要么已记进
	 *       {@code registered}，下一步的摘除一定覆盖它；</li>
	 *   <li><b>摘注册</b>：让消费端尽早不再选到本节点。此时仍正常处理请求——消费端收到下线推送前发来的请求不该失败；</li>
 *   <li><b>宽限期</b>（{@code RpcServerConfig.deregisterGrace}，默认 0，生产建议 1s）：仍处于 CLOSING、照常处理请求，
 *       等注册中心把下线推送传播到消费端；</li>
	 *   <li><b>DRAINING</b>：之后到达的请求直接回 SERVER_OVERLOADED（"server shutting down"，业务没执行，
	 *       配了重试的调用方会换节点），心跳照常；</li>
	 *   <li><b>等在途</b>：已进入的请求（排队中、执行中、异步业务的 future）全部回包，上限
	 *       {@code RpcServerConfig.shutdownTimeout}（默认 10s）；超时则中断仍在执行的业务线程、丢弃排队中的请求，
	 *       它们的调用方随连接关闭立即失败（不是挂到超时）；</li>
	 *   <li><b>关闭监听端口与全部连接</b> → <b>关 IO 线程组与业务执行器</b> → <b>关注册中心会话</b>。</li>
	 * </ol>
	 * 为什么先摘注册再拒新请求、而不是反过来：先拒绝会让"还不知道本节点要下线"的消费端在推送到达之前持续收到拒绝；
	 * 先摘注册则大部分新流量在推送到达后自然不再进来，DRAINING 只兜底推送延迟内的少量请求。
	 *
	 * <p>可重复调用；并发的第二次调用立即返回（不等第一次完成）。阻塞调用线程最多约
	 * {@code deregisterGrace + shutdownTimeout} + 几秒（注册线程退出最多等 5s、关闭 IO 线程组与注册中心会话）。
	 */
	@Override
	public void close() {
		State previous;
		ScheduledExecutorService registrationThread;
		synchronized (this) {
			previous = state;
			if (previous != State.NEW && previous != State.STARTED) {
				return;
			}
			state = State.CLOSING;
			registrationThread = registrar;
			pendingRegistration.clear();
			// 未完成的注册以取消结束：等 registered() 的调用方不会永远挂着
			registeredFuture.cancel(false);
		}
		long closeStart = System.nanoTime();
		if (registrationThread != null) {
			registrationThread.shutdownNow();
			try {
				if (!registrationThread.awaitTermination(5, TimeUnit.SECONDS)) {
					log.warn("Registration thread did not stop within 5s; closing the registry session anyway");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Registry currentRegistry = registry;
		boolean unregisteredAny = false;
		if (currentRegistry != null) {
			for (ServiceInstance instance : registered) {
				try {
					currentRegistry.unregister(instance);
					unregisteredAny = true;
					log.info("Unregistered {} at {}", instance.key(), instance.address());
				} catch (RuntimeException e) {
					log.warn("Failed to unregister {}", instance.key(), e);
				}
			}
			registered.clear();
		}
		NettyServer server = transport;
		if (server != null && unregisteredAny) {
			awaitDeregisterGrace();
		}
		boolean drained = true;
		if (server != null) {
			state = State.DRAINING;
			server.startDraining();
			Duration timeout = config.shutdownTimeout();
			int inFlightAtDrain = server.inFlight();
			log.info("RpcServer on port {} draining: rejecting new requests, waiting for {} in-flight request(s) (up to {}ms)",
					boundPort, inFlightAtDrain, timeout.toMillis());
			drained = server.awaitDrained(timeout);
			if (!drained) {
				log.warn("RpcServer on port {}: {} request(s) still in flight after shutdownTimeout {}ms; "
						+ "interrupting them and closing connections", boundPort, server.inFlight(), timeout.toMillis());
			}
			server.close(drained);
		}
		if (currentRegistry != null) {
			try {
				currentRegistry.close();
			} catch (RuntimeException e) {
				log.warn("Failed to close registry", e);
			}
		}
		state = State.CLOSED;
		if (previous == State.STARTED) {
			log.info("RpcServer on port {} closed in {}ms ({})", boundPort, (System.nanoTime() - closeStart) / 1_000_000,
					drained ? "all in-flight requests completed" : "forced after shutdownTimeout");
		}
	}

	/**
	 * 摘注册后的宽限期（{@code RpcServerConfig.deregisterGrace}，默认 0）：仍处于 CLOSING、请求照常处理，
	 * 给注册中心把"节点已删除"推送到消费端留出时间。只在确实摘掉了节点时等待——从未注册成功就没有需要传播的变更。
	 * 被中断时提前结束并保留中断标志，后续停机步骤照常进行。
	 */
	private void awaitDeregisterGrace() {
		Duration grace = config.deregisterGrace();
		if (grace.isZero()) {
			return;
		}
		log.info("RpcServer on port {} unregistered; still serving for deregisterGrace {}ms so consumers see the removal first",
				boundPort, grace.toMillis());
		try {
			Thread.sleep(grace);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private record ExportedService(ServiceKey key, int weight) {
	}
}
