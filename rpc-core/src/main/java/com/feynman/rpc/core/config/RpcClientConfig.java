package com.feynman.rpc.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.feynman.rpc.core.cluster.Cluster;
import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.filter.Filter;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.serialize.Serializer;

/**
 * RpcClient 的不可变配置。为什么不可变、为什么不读 System 属性：同一 JVM 里多个 client 必须能各自
 * 配置（集成测试、多注册中心），而进程级可变配置让"谁先读到什么"取决于初始化顺序。
 * Spring Boot 侧由 starter 把 properties 绑定成这个对象，纯 Java 侧用 builder，两条路径产出同一种配置。
 *
 * <p>SPI 名字为 null 时在构造时解析成 {@code @SPI} 上的默认名，之后配置里永远是确定值，日志和断言不需要再猜。
 *
 * <p>{@code serializerOptions} 交给每个 Serializer 实现的 {@code withOptions}，键以实现的 SPI 名为前缀，
 * 例如 {@code protobuf.allowed-packages=com.acme.api.,com.acme.dto.}（Protostuff 多态位置按名加载的包前缀白名单）。
 *
 * <p>{@code shutdownTimeout}（默认 10s）：{@code close()} 等待在途调用完成的上限，之后其余调用以 RpcException 失败。
 *
 * <p>{@code exceptionAllowlist}：服务端异常按类名还原成本地同类型时，额外允许的包前缀（规则见
 * {@link Builder#exceptionAllowlist(String...)}）。
 *
 * <p>{@code ioThreads}（默认 {@value #DEFAULT_IO_THREADS}）：客户端 IO 事件循环线程数，0 = Netty 缺省（2 × 可用核数），
 * 见 {@link Builder#ioThreads(int)}。
 *
 * <p>{@code syncWaitGrace}（默认 1s）：同步代理方法在"本次调用 deadline 的剩余时间 + grace"之后不再等待，
 * 见 {@link Builder#syncWaitGrace(Duration)}。
 */
public record RpcClientConfig(
		RegistryConfig registry,
		String serializer,
		String loadBalance,
		String cluster,
		int connectionsPerNode,
		Duration defaultTimeout,
		boolean emptyProtection,
		List<Filter> clientFilters,
		Map<String, String> serializerOptions,
		Duration shutdownTimeout,
		List<String> exceptionAllowlist,
		int ioThreads,
		Duration syncWaitGrace) {

	public static final int DEFAULT_CONNECTIONS_PER_NODE = 2;
	/** 0 = 交给 Netty 决定（{@code NioEventLoopGroup(0)}：2 × 可用核数，或 {@code -Dio.netty.eventLoopThreads}）。 */
	public static final int DEFAULT_IO_THREADS = 0;
	/** IO 线程数上限：防止把"连接数"之类的数误填进来，一次起上万个线程。 */
	public static final int MAX_IO_THREADS = 1024;
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
	public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
	public static final Duration DEFAULT_SYNC_WAIT_GRACE = Duration.ofSeconds(1);

	public RpcClientConfig {
		if (registry == null) {
			throw new IllegalArgumentException("registry is required: the client discovers providers through it");
		}
		serializer = orSpiDefault(serializer, Serializer.class);
		loadBalance = orSpiDefault(loadBalance, LoadBalance.class);
		cluster = orSpiDefault(cluster, Cluster.class);
		if (connectionsPerNode < 1) {
			throw new IllegalArgumentException("connectionsPerNode must be >= 1: " + connectionsPerNode);
		}
		if (defaultTimeout == null) {
			defaultTimeout = DEFAULT_TIMEOUT;
		}
		if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
			throw new IllegalArgumentException("defaultTimeout must be positive: " + defaultTimeout);
		}
		clientFilters = clientFilters == null ? List.of() : List.copyOf(clientFilters);
		serializerOptions = serializerOptions == null ? Map.of() : Map.copyOf(serializerOptions);
		if (shutdownTimeout == null) {
			shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
		}
		if (shutdownTimeout.isNegative()) {
			throw new IllegalArgumentException("shutdownTimeout must not be negative: " + shutdownTimeout);
		}
		exceptionAllowlist = exceptionAllowlist == null ? List.of() : List.copyOf(exceptionAllowlist);
		for (String prefix : exceptionAllowlist) {
			if (prefix.isBlank()) {
				throw new IllegalArgumentException("exceptionAllowlist entries must not be blank");
			}
		}
		checkIoThreads(ioThreads);
		if (syncWaitGrace == null) {
			syncWaitGrace = DEFAULT_SYNC_WAIT_GRACE;
		}
		if (syncWaitGrace.isNegative()) {
			throw new IllegalArgumentException("syncWaitGrace must not be negative: " + syncWaitGrace);
		}
	}

	static void checkIoThreads(int ioThreads) {
		if (ioThreads < 0 || ioThreads > MAX_IO_THREADS) {
			throw new IllegalArgumentException("ioThreads must be 0 (Netty default) .. " + MAX_IO_THREADS + ": " + ioThreads);
		}
	}

	public static Builder<RpcClientConfig> builder() {
		return new Builder<>(Function.identity());
	}

	static String orSpiDefault(String name, Class<?> spiType) {
		if (name != null && !name.isBlank()) {
			return name.trim();
		}
		return spiType.getAnnotation(SPI.class).value();
	}

	/**
	 * 同一个 builder 既能产出配置（{@code RpcClientConfig.builder()}），也能直接产出客户端
	 * （{@code RpcClient.builder()}）：{@code finisher} 决定 build() 的产物，避免两套重复的 setter。
	 */
	public static final class Builder<T> {

		private final Function<RpcClientConfig, T> finisher;
		private RegistryConfig registry;
		private String serializer;
		private String loadBalance;
		private String cluster;
		private int connectionsPerNode = DEFAULT_CONNECTIONS_PER_NODE;
		private Duration defaultTimeout = DEFAULT_TIMEOUT;
		private boolean emptyProtection = true;
		private final List<Filter> clientFilters = new ArrayList<>();
		private final Map<String, String> serializerOptions = new LinkedHashMap<>();
		private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
		private final List<String> exceptionAllowlist = new ArrayList<>();
		private int ioThreads = DEFAULT_IO_THREADS;
		private Duration syncWaitGrace = DEFAULT_SYNC_WAIT_GRACE;

		public Builder(Function<RpcClientConfig, T> finisher) {
			this.finisher = finisher;
		}

		public Builder<T> registry(RegistryConfig registry) {
			this.registry = registry;
			return this;
		}

		/** 首选序列化方式的 SPI 名，不设置时为 Serializer 的 SPI 缺省名 {@code protobuf}；连接建立后仍会与服务端协商，对端不支持时自动退让。 */
		public Builder<T> serializer(String serializer) {
			this.serializer = serializer;
			return this;
		}

		public Builder<T> loadBalance(String loadBalance) {
			this.loadBalance = loadBalance;
			return this;
		}

		public Builder<T> cluster(String cluster) {
			this.cluster = cluster;
			return this;
		}

		public Builder<T> connectionsPerNode(int connectionsPerNode) {
			this.connectionsPerNode = connectionsPerNode;
			return this;
		}

		public Builder<T> defaultTimeout(Duration defaultTimeout) {
			this.defaultTimeout = defaultTimeout;
			return this;
		}

		/**
		 * 推空保护（默认开）：注册中心推送空列表时保留旧地址并告警。注册中心抖动 / 会话重建时推空
		 * 的概率远大于"所有提供者同时下线"，后者宁可让调用在连接层失败，也不要前者把全部连接摘光。
		 */
		public Builder<T> emptyProtection(boolean emptyProtection) {
			this.emptyProtection = emptyProtection;
			return this;
		}

		/**
		 * 追加客户端 Filter。执行顺序按 {@link Filter#order()} 升序（越小越先），同 order 按追加顺序；
		 * 内置的 DeadlineClientFilter 由 RpcClient 自动加入，不需要在这里注册。
		 */
		public Builder<T> clientFilter(Filter... filters) {
			clientFilters.addAll(Arrays.asList(filters));
			return this;
		}

		/** 序列化器配置项，见类注释。 */
		public Builder<T> serializerOption(String key, String value) {
			serializerOptions.put(key, value);
			return this;
		}

		/**
		 * {@code close()} 等待在途调用完成的上限（默认 10s）。为什么要等：调用方线程可能正卡在一次马上就要返回的调用上，
		 * 直接关连接会让它以"客户端已关闭"失败，发布 / 缩容期间就会出现本可避免的错误。超过上限仍未完成的调用以
		 * RpcException 失败，close 不会无限期挂住。
		 */
		public Builder<T> shutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * 追加允许按类名还原的异常包前缀（可多次调用）。规则与 Protostuff 白名单一致：以 {@code .} 结尾表示该包及其子包，
		 * 否则按类名前缀匹配。
		 *
		 * <p>默认允许：{@code java.}、{@code javax.}、框架包 {@code com.feynman.rpc.core.}、每个引用接口所在的包及其子包（业务异常最常见的位置）、以及方法
		 * {@code throws} 里声明的异常类。白名单外的类名<b>不加载、不实例化</b>，一律降级为 RpcRemoteException（保留类名与消息）。
		 * 为什么要白名单：类名来自对端的字节，"能加载就实例化"意味着对端可以让本进程实例化 classpath 上任意一个带
		 * String 构造器的 Exception 子类——与 fastjson autoType 是同一类风险，默认拒绝比事后补黑名单可靠。
		 */
		public Builder<T> exceptionAllowlist(String... packagePrefixes) {
			exceptionAllowlist.addAll(Arrays.asList(packagePrefixes));
			return this;
		}

		/**
		 * 客户端 IO 事件循环（{@code rpc-client-io-*}）的线程数，默认 0 = Netty 缺省（2 × 可用核数）。每个 RpcClient 各有一组，
		 * 只影响本客户端（不像 {@code -Dio.netty.eventLoopThreads} 那样改掉进程里所有缺省大小的 Netty 线程组）。
		 * 一条连接固定在一个事件循环上，线程数多于"提供者数 × connectionsPerNode"的部分不会被用到。
		 */
		public Builder<T> ioThreads(int ioThreads) {
			this.ioThreads = ioThreads;
			return this;
		}

		/**
		 * 同步代理方法等待结果的第二道上界（默认 1s）：调用方线程最多等"本次调用 deadline 的剩余时间 + grace"。
		 *
		 * <p>正常情况下超时由在途表的 deadline 定时器负责，同步等待总会先被它唤醒，grace 不起作用。它只防一种情况：
		 * 定时器没有按时触发（例如进程提交内存耗尽时运行时线程建不起来、定时任务里分配失败被线程池吞掉），调用方线程
		 * 若无界地等下去就会永远挂住。超过 grace 时抛 {@code RpcDeadlineExceededException}（消息含
		 * "deadline timer did not fire within grace"），并让该次调用的在途请求失败。
		 *
		 * <p>取 0 也合法（等到 deadline 就放弃），但会与正常的定时器抢先后，异常消息因此不稳定；不建议小于几十毫秒。
		 * 返回 {@code CompletableFuture} 的异步方法不受它约束，调用方应自带 {@code orTimeout}。
		 */
		public Builder<T> syncWaitGrace(Duration syncWaitGrace) {
			this.syncWaitGrace = syncWaitGrace;
			return this;
		}

		public T build() {
			return finisher.apply(new RpcClientConfig(registry, serializer, loadBalance, cluster,
					connectionsPerNode, defaultTimeout, emptyProtection, clientFilters, serializerOptions,
					shutdownTimeout, exceptionAllowlist, ioThreads, syncWaitGrace));
		}
	}
}
