package com.feynman.rpc.core;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.client.NettyClient;
import com.feynman.rpc.core.client.ServiceDirectory;
import com.feynman.rpc.core.cluster.Cluster;
import com.feynman.rpc.core.cluster.ClusterInvocation;
import com.feynman.rpc.core.codec.AttachmentsCodec;
import com.feynman.rpc.core.codec.EnvelopeCodec;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.config.RpcClientConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.filter.DeadlineClientFilter;
import com.feynman.rpc.core.filter.Filter;
import com.feynman.rpc.core.filter.FilterChain;
import com.feynman.rpc.core.filter.Invocation;
import com.feynman.rpc.core.filter.Invoker;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.internal.InFlightGate;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.AttachmentKeys;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.ProtocolException;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * 消费端入口：一个可以 new、可以 close 的对象，不依赖任何容器。
 *
 * <pre>{@code
 * try (RpcClient client = RpcClient.builder()
 *         .registry(RegistryConfig.zookeeper("localhost:2181"))
 *         .build()) {
 *     UserFacade users = client.reference(UserFacade.class);
 *     users.saveUser(user);
 * }
 * }</pre>
 *
 * <p>为什么是实例而不是静态门面：旧实现的连接表、在途表、负载统计、注册中心订阅全是进程级静态状态，
 * 类加载即连注册中心，同 JVM 起不了第二个客户端，也关不掉。现在它们全部是本对象的字段，
 * {@link #close()} 关闭连接、注销订阅、关闭注册中心会话与 IO 线程组，关完不留线程。
 *
 * <p>调用路径：代理 → 客户端 Filter 链（内置 DeadlineClientFilter + 用户 Filter，按 order 稳定排序）→ Cluster
 * （含 LoadBalance 与重试，对 Filter 不可见）→ 发送。Filter 链在 {@link #reference} 时组装一次。
 *
 * <p>失败的两个通道：传输失败（连不上、写失败）让 future 异常完成，Cluster 据此重试；服务端已经给出答复的错误
 * （业务异常、DEADLINE_EXCEEDED、序列化失败等）以及本次调用的 deadline 已到，都放进 {@link Result}，不重试——
 * deadline 是一次调用共享的预算，过期后换节点重发只会多一次注定无用的执行。例外是 SERVER_OVERLOADED：服务端拒绝时
 * 业务一定没执行，它与"连接被拒绝"同样安全，所以走传输失败的通道，让 failover 排除该节点、换节点重试。
 *
 * <p>生命周期：OPEN → CLOSING（拒绝新调用、等在途）→ CLOSED（不再建连接池，随后关连接、注销订阅、关注册中心、关线程组）。
 * 状态变化与"注册中心推送 → 重建连接池"共用 {@code lifecycleLock}，保证 CLOSED 之后不会再创建连接池；close 在锁外
 * 调用注册中心（见 {@link #close()}）。
 */
public final class RpcClient implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(RpcClient.class);
	private enum State { OPEN, CLOSING, CLOSED }
	/** 默认允许还原的框架异常包（含子包）。 */
	static final String FRAMEWORK_EXCEPTION_PACKAGE = "com.feynman.rpc.core.";

	private final RpcClientConfig config;
	private final Registry registry;
	private final LoadBalance loadBalance;
	private final Cluster cluster;
	private final LoadStats loadStats = new LoadStats();
	private final Serializers serializers;
	private final List<Filter> filters;
	private final NettyClient transport;
	private final Map<ServiceKey, ServiceDirectory> directories = new ConcurrentHashMap<>();
	private final Map<Method, RemoteMethod> remoteMethods = new ConcurrentHashMap<>();
	private final List<String> exceptionPrefixes;
	/** 在途调用（代理层）：close 关闸后新调用抛 IllegalStateException，已进入的调用 close 会等它们完成。 */
	private final InFlightGate gate = new InFlightGate();
	/** 保护 state 与连接池重建（refreshConnections）。持有它时绝不调用 Registry（见 close()）。 */
	private final Object lifecycleLock = new Object();
	private volatile State state = State.OPEN;

	private RpcClient(RpcClientConfig config) {
		this.config = config;
		// SPI 只负责找到类：LoadBalance / Cluster 可能有状态（游标、重试线程），每个客户端各自 new 一份
		this.loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).newExtension(config.loadBalance());
		this.cluster = ExtensionLoader.getExtensionLoader(Cluster.class).newExtension(config.cluster());
		// 序列化器同理：可能带配置（Protostuff 白名单），按客户端各持一组
		this.serializers = Serializers.load(config.serializer(), config.serializerOptions());
		List<Filter> all = new ArrayList<>();
		all.add(new DeadlineClientFilter());
		all.addAll(config.clientFilters());
		this.filters = FilterChain.ordered(all);
		// 默认允许还原的异常包：JDK 的异常类在两端一定一致，且没有"实例化即有副作用"的风险；框架自己的异常
		// （RpcException 及其子类）两端同一份代码，还原成同类型后调用方才能按 RpcDeadlineExceededException 等类型处理
		List<String> prefixes = new ArrayList<>(List.of("java.", "javax.", FRAMEWORK_EXCEPTION_PACKAGE));
		prefixes.addAll(config.exceptionAllowlist());
		this.exceptionPrefixes = List.copyOf(prefixes);
		RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class)
				.getExtension(config.registry().type());
		this.registry = registryFactory.create(config.registry());
		try {
			this.transport = new NettyClient(serializers, config.connectionsPerNode(), loadStats);
		} catch (RuntimeException e) {
			registry.close();
			cluster.close();
			throw e;
		}
	}

	public static RpcClientConfig.Builder<RpcClient> builder() {
		return new RpcClientConfig.Builder<>(RpcClient::new);
	}

	/** Spring Boot starter 等"先得到配置对象"的场景使用。 */
	public static RpcClient create(RpcClientConfig config) {
		return new RpcClient(config);
	}

	public RpcClientConfig config() {
		return config;
	}

	public <T> T reference(Class<T> itf) {
		return reference(itf, ReferenceOptions.defaults());
	}

	/**
	 * 创建远程服务代理。同一个 {@link ServiceKey} 只订阅一次注册中心，多个代理共享提供者列表与连接。
	 * 返回 {@code CompletableFuture<T>} 的方法走真正的非阻塞调用；其余方法在同一个 future 上等待——
	 * 同步只是异步之上的语法糖，不是另一条代码路径。
	 *
	 * <p>注册中心不可达时（Registry 契约，rpc-registry-tck 约束）：subscribe 不抛异常，最多阻塞实现声明的等待上限
	 * （ZooKeeper / Nacos 的 {@code connect-wait-ms}）后返回代理；此时提供者列表为空，调用以 "No available provider"
	 * 快速失败（不是挂到超时）；后端连上之后订阅补推首次列表，调用自动恢复，无需重新 reference。
	 *
	 * <p>{@link ReferenceOptions#awaitProviders()} 非 null 时，订阅之后阻塞到该服务的提供者视图非空（由注册中心推送唤醒，
	 * 不轮询），最多等这么久；超时抛 {@link RpcException}（订阅保留，之后的推送照常生效，客户端照常可 close）。
	 * 默认 null：不等待，行为同上。
	 *
	 * @throws RpcException 设置了 awaitProviders 且超时仍没有提供者，或等待中被中断（中断标志保留）
	 */
	public <T> T reference(Class<T> itf, ReferenceOptions options) {
		ensureOpen();
		if (!itf.isInterface()) {
			throw new IllegalArgumentException("reference type must be an interface: " + itf.getName());
		}
		ServiceKey key = ServiceKey.of(itf, options.version(), options.group());
		ServiceDirectory directory = directoryFor(key);
		if (options.awaitProviders() != null) {
			awaitProviders(directory, options.awaitProviders());
		}
		Duration timeout = options.timeout() != null ? options.timeout() : config.defaultTimeout();
		int retries = options.retries();
		Invoker chain = FilterChain.build(filters, invocation -> invokeCluster(directory, invocation, retries));
		Object proxy = Proxy.newProxyInstance(itf.getClassLoader(), new Class<?>[] { itf },
				new ReferenceProxy(key, chain, timeout, options.inheritDeadline(), gate));
		return itf.cast(proxy);
	}

	private void awaitProviders(ServiceDirectory directory, Duration wait) {
		boolean found;
		try {
			found = directory.awaitProviders(wait);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RpcException("Interrupted while waiting for providers of " + directory.key(), e);
		}
		if (!found) {
			throw new RpcException("No provider of " + directory.key() + " discovered within " + wait.toMillis()
					+ "ms (ReferenceOptions.awaitProviders)");
		}
	}

	/** 经代理发起、尚未完成的调用数（含重试中的）。close() 等它归零；也可作为泄漏断言。 */
	public int inFlightCalls() {
		return gate.inFlight();
	}

	/** 到某个提供者地址（host:port）当前已激活的连接数，含下线后仍在排空的连接。用于观测与测试。 */
	public int activeConnections(String address) {
		return transport.activeConnections(address);
	}

	/**
	 * 到全部提供者当前已激活的连接总数，含下线后仍在排空的连接（与 {@link #activeConnections(String)} 同一口径）。
	 * 只读，用于观测（指标的连接数 Gauge）与测试；close 之后为 0。
	 */
	public int activeConnections() {
		return transport.activeConnections();
	}

	/** 某服务当前已知的提供者（经过推空保护之后的视图），用于观测与测试。 */
	public List<ServiceInstance> providers(ServiceKey key) {
		ServiceDirectory directory = directories.get(key);
		return directory == null ? List.of() : directory.instances();
	}

	/** 已发出、尚未收到响应的请求数。正常结束的调用之后应回到 0，可作为泄漏断言。 */
	public int pendingRequests() {
		return transport.pendingRequests();
	}

	private ServiceDirectory directoryFor(ServiceKey key) {
		ServiceDirectory existing = directories.get(key);
		if (existing != null) {
			return existing;
		}
		synchronized (directories) {
			existing = directories.get(key);
			if (existing != null) {
				return existing;
			}
			ServiceDirectory directory = new ServiceDirectory(key, config.emptyProtection(), changed -> refreshConnections());
			// 先放进表再订阅：订阅会立即推送一次，推送回调里重建连接时必须能看到这个目录
			directories.put(key, directory);
			try {
				registry.subscribe(key, directory);
			} catch (RuntimeException e) {
				directories.remove(key);
				throw e;
			}
			return directory;
		}
	}

	/**
	 * 连接池 = 所有已订阅服务的提供者地址并集；某地址不再被任何服务需要时，它的连接排空在途请求后关闭。
	 * 在注册中心的回调线程上执行。与 close() 的竞态：二者共用 lifecycleLock，CLOSED 之后这里什么都不做，
	 * 因此 close 关完连接之后不会有迟到的推送再建出新的连接池。CLOSING（等在途）期间照常调整，
	 * 下线的节点仍按排空流程处理。
	 */
	private void refreshConnections() {
		synchronized (lifecycleLock) {
			if (state == State.CLOSED) {
				return;
			}
			List<ServiceInstance> all = new ArrayList<>();
			for (ServiceDirectory directory : directories.values()) {
				all.addAll(directory.instances());
			}
			transport.updateConnections(all);
		}
	}

	private CompletableFuture<Result> invokeCluster(ServiceDirectory directory, Invocation invocation, int retries) {
		ClusterInvocation clusterInvocation = new ClusterInvocation(directory::instances, loadBalance, loadStats,
				instance -> attempt(instance, invocation), retries, invocation.toString());
		return cluster.invoke(clusterInvocation);
	}

	/**
	 * 对一个节点发起一次请求。超时用本次调用 deadline 的剩余时间（重试共享同一份预算），这是该请求唯一的定时器；
	 * 已经没有剩余时间时不再发送，直接以 {@link RpcDeadlineExceededException} 结束。
	 */
	private CompletableFuture<Result> attempt(ServiceInstance instance, Invocation invocation) {
		if (state == State.CLOSED) {
			return CompletableFuture.failedFuture(new RpcException("RpcClient closed"));
		}
		long remainingNanos = invocation.remainingNanos();
		if (remainingNanos <= 0) {
			return CompletableFuture.completedFuture(Result.exception(new RpcDeadlineExceededException(
					"deadline of " + invocation.timeout().toMillis() + "ms exceeded before sending: " + invocation)));
		}
		RemoteMethod remoteMethod = remoteMethods.computeIfAbsent(invocation.method(), RemoteMethod::of);
		Map<String, String> attachments = wireAttachments(invocation, remainingNanos);
		try {
			AttachmentsCodec.encodedLength(attachments, ProtocolConstants.DEFAULT_MAX_ATTACHMENTS_LENGTH);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.completedFuture(Result.exception(
					new RpcException("Invalid attachments for " + invocation + ": " + e.getMessage(), e)));
		}
		Object[] args = invocation.args();
		ServiceKey key = invocation.serviceKey();
		String address = instance.address();

		loadStats.begin(address);
		CompletableFuture<RpcMessage> response;
		try {
			response = transport.send(address, (alloc, serializer) -> {
				List<byte[]> encodedArgs = new ArrayList<>(args.length);
				for (int i = 0; i < args.length; i++) {
					encodedArgs.add(args[i] == null ? null : serializer.serialize(args[i], remoteMethod.genericParamTypes()[i]));
				}
				ByteBuf body = alloc.buffer();
				try {
					EnvelopeCodec.writeRequest(body, key.interfaceName(), key.version(), key.group(),
							remoteMethod.method().getName(), remoteMethod.paramSig(), encodedArgs);
					return body;
				} catch (RuntimeException e) {
					body.release();
					throw e;
				}
			}, attachments, remainingNanos);
		} catch (RuntimeException e) {
			response = CompletableFuture.failedFuture(e);
		}
		return response
				.whenComplete((r, e) -> loadStats.end(address))
				.handle((message, error) -> {
					if (error == null) {
						try {
							if (message.messageType() == MessageType.RESPONSE && message.status() == Status.SERVER_OVERLOADED) {
								// 服务端拒绝（过载 / 停机）：业务没执行，与传输失败同一通道，Cluster 可排除该节点重试
								throw new CompletionException(overloaded(message, address, invocation));
							}
							return decode(message, remoteMethod, invocation);
						} finally {
							message.release();
						}
					}
					Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
					if (cause instanceof TimeoutException) {
						return Result.exception(new RpcDeadlineExceededException("no response within "
								+ invocation.timeout().toMillis() + "ms from " + address + ": " + invocation, cause));
					}
					if (cause instanceof RpcSerializationException) {
						return Result.exception(cause);
					}
					// 传输失败：让 future 异常完成，Cluster 据此决定是否换节点重试
					throw error instanceof CompletionException ce ? ce : new CompletionException(cause);
				});
	}

	private static RpcOverloadedException overloaded(RpcMessage response, String address, Invocation invocation) {
		String reason;
		try {
			reason = EnvelopeCodec.readError(response.body()).message();
		} catch (ProtocolException e) {
			reason = "(malformed error envelope: " + e.getMessage() + ")";
		}
		return new RpcOverloadedException("SERVER_OVERLOADED from " + address + " for " + invocation + ": " + reason);
	}

	/** 本次发送实际带上的 attachments：DeadlineClientFilter 写过 rpc.timeout 时，按此刻的剩余时间刷新（重试时预算已变少）。 */
	private static Map<String, String> wireAttachments(Invocation invocation, long remainingNanos) {
		Map<String, String> attachments = invocation.attachments();
		if (!attachments.containsKey(AttachmentKeys.TIMEOUT)) {
			return attachments;
		}
		Map<String, String> refreshed = new LinkedHashMap<>(attachments);
		refreshed.put(AttachmentKeys.TIMEOUT, Long.toString(DeadlineClientFilter.remainingMillis(remainingNanos)));
		return refreshed;
	}

	/** 把响应帧解成 Result。运行在完成在途 future 的线程上（通常是 IO 线程）；不负责释放 message。 */
	private Result decode(RpcMessage response, RemoteMethod remoteMethod, Invocation invocation) {
		if (response.messageType() != MessageType.RESPONSE) {
			return Result.exception(new RpcException("Unexpected message type: " + response.messageType()));
		}
		if (response.malformedAttachments() != null) {
			return Result.exception(new RpcException("Malformed response attachments from server: "
					+ response.malformedAttachments()));
		}
		Map<String, String> attachments = response.attachments();
		Status status = response.status();
		try {
			if (status == Status.OK) {
				return decodeValue(response, remoteMethod).withAttachments(attachments);
			}
			EnvelopeCodec.ErrorEnvelope error = EnvelopeCodec.readError(response.body());
			Throwable failure = switch (status) {
				case BIZ_EXCEPTION, SERVER_ERROR -> RemoteExceptions.restore(error, status, classLoaderOf(remoteMethod),
						exceptionAllowlist(invocation));
				case DEADLINE_EXCEEDED -> new RpcDeadlineExceededException(
						"server rejected expired request " + invocation + ": " + error.message());
				case SERIALIZATION_ERROR -> new RpcSerializationException(
						"SERIALIZATION_ERROR from server for " + invocation + ": " + error.message());
				default -> new RpcException(status + " from server for " + invocation + ": " + error.message());
			};
			return Result.exception(failure).withAttachments(attachments);
		} catch (ProtocolException e) {
			return Result.exception(new RpcException("Malformed " + status + " response for " + invocation + ": " + e.getMessage(), e));
		}
	}

	private Result decodeValue(RpcMessage response, RemoteMethod remoteMethod) {
		ByteBuf value = EnvelopeCodec.readValue(response.body());
		if (value == null || remoteMethod.voidReturn()) {
			return Result.value(null);
		}
		Serializer serializer = serializers.find(response.serializer());
		if (serializer == null) {
			return Result.exception(new RpcSerializationException(
					"Response uses unsupported serializer code " + response.serializer()));
		}
		try {
			return Result.value(serializer.deserialize(ByteBufUtil.getBytes(value), remoteMethod.returnValueType()));
		} catch (RuntimeException e) {
			return Result.exception(new RpcSerializationException("Failed to deserialize result as "
					+ remoteMethod.returnValueType().getTypeName() + " with " + serializer.name() + ": " + e, e));
		}
	}

	/**
	 * 本次调用允许还原的异常类名：{@code java.} / {@code javax.} / {@code com.feynman.rpc.core.} + 配置的前缀 + 被引用接口所在的包及其子包 +
	 * 方法 {@code throws} 里声明的异常类。只在错误路径上计算。
	 */
	private Predicate<String> exceptionAllowlist(Invocation invocation) {
		return className -> {
			for (String prefix : exceptionPrefixes) {
				if (className.startsWith(prefix)) {
					return true;
				}
			}
			String itf = invocation.serviceKey().interfaceName();
			int lastDot = itf.lastIndexOf('.');
			if (lastDot > 0 && className.startsWith(itf.substring(0, lastDot + 1))) {
				return true;
			}
			for (Class<?> declared : invocation.method().getExceptionTypes()) {
				if (declared.getName().equals(className)) {
					return true;
				}
			}
			return false;
		};
	}

	/** 还原远端异常时优先用接口的类加载器：业务异常类通常和接口在同一个 API 包里。 */
	private static ClassLoader classLoaderOf(RemoteMethod remoteMethod) {
		ClassLoader loader = remoteMethod.method().getDeclaringClass().getClassLoader();
		return loader != null ? loader : RpcClient.class.getClassLoader();
	}

	private void ensureOpen() {
		if (state != State.OPEN) {
			throw new IllegalStateException("RpcClient is closed");
		}
	}

	/**
	 * 优雅关闭，按顺序：
	 * <ol>
	 *   <li><b>停止发起新调用</b>：关闸，之后代理上的调用抛 {@link IllegalStateException}，{@code reference()} 同样；</li>
	 *   <li><b>等在途调用完成</b>，上限 {@code RpcClientConfig.shutdownTimeout}（默认 10s）。期间注册中心推送照常处理，
	 *       Cluster 的重试照常进行（它们属于已经发起的调用）；</li>
	 *   <li>状态置 CLOSED（与连接池重建同一把锁，之后迟到的推送不再建连接池），剩余仍未完成的请求以 RpcException 失败；</li>
	 *   <li>关闭全部连接 → 注销订阅 → 关闭注册中心会话 → 关闭 Cluster 与 IO 线程组。</li>
	 * </ol>
	 * 与注册中心回调的竞态：注册中心实现通常在持有自己内部锁的线程上回调（ZK 实现在订阅对象的锁下推送，推送回调里
	 * 我们要拿 lifecycleLock 重建连接池）；若 close 持有 lifecycleLock 去调 {@code unsubscribe}（它要拿注册中心那把锁、
	 * 等正在进行的推送结束），两边就会互相等待。所以 close 只在锁内改状态，注销订阅、关注册中心都在锁外进行；
	 * "CLOSED 之后不再建连接池"由 refreshConnections 在同一把锁内检查状态保证。可重复调用，并发的第二次调用立即返回。
	 */
	@Override
	public void close() {
		synchronized (lifecycleLock) {
			if (state != State.OPEN) {
				return;
			}
			state = State.CLOSING;
		}
		gate.close();
		long start = System.nanoTime();
		boolean drained;
		try {
			drained = gate.awaitIdle(config.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			drained = false;
		}
		if (!drained) {
			log.warn("RpcClient closing with {} call(s) still in flight after shutdownTimeout {}ms; failing them",
					gate.inFlight(), config.shutdownTimeout().toMillis());
		}
		synchronized (lifecycleLock) {
			state = State.CLOSED;
		}
		transport.failPending(new RpcException("RpcClient closed before the response arrived"));
		transport.closeConnections();
		directories.forEach((key, directory) -> {
			try {
				registry.unsubscribe(key, directory);
			} catch (RuntimeException e) {
				log.warn("Failed to unsubscribe {}", key, e);
			}
		});
		directories.clear();
		try {
			registry.close();
		} catch (RuntimeException e) {
			log.warn("Failed to close registry", e);
		}
		cluster.close();
		transport.shutdownIo();
		log.info("RpcClient closed in {}ms ({})", (System.nanoTime() - start) / 1_000_000,
				drained ? "all in-flight calls completed" : "remaining calls failed after shutdownTimeout");
	}
}
