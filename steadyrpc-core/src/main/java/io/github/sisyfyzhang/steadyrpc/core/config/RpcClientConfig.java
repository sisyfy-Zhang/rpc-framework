package io.github.sisyfyzhang.steadyrpc.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.sisyfyzhang.steadyrpc.core.cluster.Cluster;
import io.github.sisyfyzhang.steadyrpc.core.extension.SPI;
import io.github.sisyfyzhang.steadyrpc.core.filter.Filter;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadBalance;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;

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
 *
 * <p>{@code flushConsolidation}（默认 true）：连接 pipeline 最靠近 transport 处是否加 Netty 的 {@code FlushConsolidationHandler}，
 * 见 {@link Builder#flushConsolidation(boolean)}。
 *
 * <p>{@code writeBufferLow} / {@code writeBufferHigh}（默认 32KiB / 64KiB，即 Netty 缺省）：每条连接的
 * {@code WRITE_BUFFER_WATER_MARK}；{@code outboundHardLimit}（默认 high × 16）：一条连接的待写字节超过它且不可写时，
 * 新请求不再写出、以"未发出"失败。见 {@link Builder#writeBufferWaterMark(int, int)} 与 {@link Builder#outboundHardLimit(long)}。
 *
 * <p>{@code outlierDetection}（默认开启，{@link OutlierDetectionConfig#defaults()}）：按提供者的离群摘除，见 {@link Builder#outlierDetection}。
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
		Duration syncWaitGrace,
		boolean flushConsolidation,
		int writeBufferLow,
		int writeBufferHigh,
		long outboundHardLimit,
		OutlierDetectionConfig outlierDetection) {

	public static final int DEFAULT_CONNECTIONS_PER_NODE = 2;
	/** 0 = 交给 Netty 决定（{@code NioEventLoopGroup(0)}：2 × 可用核数，或 {@code -Dio.netty.eventLoopThreads}）。 */
	public static final int DEFAULT_IO_THREADS = 0;
	/** IO 线程数上限：防止把"连接数"之类的数误填进来，一次起上万个线程。 */
	public static final int MAX_IO_THREADS = 1024;
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
	public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
	public static final Duration DEFAULT_SYNC_WAIT_GRACE = Duration.ofSeconds(1);
	/** 缺省合并 flush（{@code FlushConsolidationHandler}），见 {@link Builder#flushConsolidation(boolean)}。 */
	public static final boolean DEFAULT_FLUSH_CONSOLIDATION = true;
	/** 写缓冲低水位缺省值：与 Netty {@code WriteBufferWaterMark.DEFAULT} 相同（32KiB）。 */
	public static final int DEFAULT_WRITE_BUFFER_LOW = 32 * 1024;
	/** 写缓冲高水位缺省值：与 Netty {@code WriteBufferWaterMark.DEFAULT} 相同（64KiB）。 */
	public static final int DEFAULT_WRITE_BUFFER_HIGH = 64 * 1024;
	/** {@code outboundHardLimit} 未显式设置时取高水位的这么多倍。 */
	public static final int DEFAULT_OUTBOUND_HARD_LIMIT_MULTIPLIER = 16;
	/** 记录构造参数里的 {@code outboundHardLimit} 取这个值（任意负数）表示"按高水位推出缺省值"。 */
	public static final long OUTBOUND_HARD_LIMIT_DEFAULT = -1;

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
		checkWaterMark(writeBufferLow, writeBufferHigh);
		outboundHardLimit = resolveOutboundHardLimit(outboundHardLimit, writeBufferHigh);
		if (outlierDetection == null) {
			outlierDetection = OutlierDetectionConfig.defaults();
		}
	}

	/**
	 * 1.0.0 的规范构造器（没有 {@code outlierDetection}）：保留它让直接调用记录构造器的代码二进制兼容，离群摘除取缺省（开）。
	 */
	public RpcClientConfig(RegistryConfig registry, String serializer, String loadBalance, String cluster, int connectionsPerNode,
			Duration defaultTimeout, boolean emptyProtection, List<Filter> clientFilters, Map<String, String> serializerOptions,
			Duration shutdownTimeout, List<String> exceptionAllowlist, int ioThreads, Duration syncWaitGrace, boolean flushConsolidation,
			int writeBufferLow, int writeBufferHigh, long outboundHardLimit) {
		this(registry, serializer, loadBalance, cluster, connectionsPerNode, defaultTimeout, emptyProtection, clientFilters, serializerOptions,
				shutdownTimeout, exceptionAllowlist, ioThreads, syncWaitGrace, flushConsolidation, writeBufferLow, writeBufferHigh,
				outboundHardLimit, null);
	}

	static void checkIoThreads(int ioThreads) {
		if (ioThreads < 0 || ioThreads > MAX_IO_THREADS) {
			throw new IllegalArgumentException("ioThreads must be 0 (Netty default) .. " + MAX_IO_THREADS + ": " + ioThreads);
		}
	}

	/** 与 Netty {@code WriteBufferWaterMark} 相同的约束，另要求 high ≥ 1（0 会让每次写都翻成不可写）。 */
	static void checkWaterMark(int low, int high) {
		if (low < 0) {
			throw new IllegalArgumentException("writeBufferLow must be >= 0: " + low);
		}
		if (high < 1 || high < low) {
			throw new IllegalArgumentException("writeBufferHigh must be >= 1 and >= writeBufferLow (" + low + "): " + high);
		}
	}

	/** 负数 → high × {@value #DEFAULT_OUTBOUND_HARD_LIMIT_MULTIPLIER}；0 = 关闭；正数必须 ≥ high（低于高水位的硬上限没有意义）。 */
	static long resolveOutboundHardLimit(long outboundHardLimit, int high) {
		if (outboundHardLimit < 0) {
			return (long) high * DEFAULT_OUTBOUND_HARD_LIMIT_MULTIPLIER;
		}
		if (outboundHardLimit > 0 && outboundHardLimit < high) {
			throw new IllegalArgumentException("outboundHardLimit must be 0 (off) or >= writeBufferHigh (" + high + "): "
					+ outboundHardLimit);
		}
		return outboundHardLimit;
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
		private boolean flushConsolidation = DEFAULT_FLUSH_CONSOLIDATION;
		private int writeBufferLow = DEFAULT_WRITE_BUFFER_LOW;
		private int writeBufferHigh = DEFAULT_WRITE_BUFFER_HIGH;
		private long outboundHardLimit = OUTBOUND_HARD_LIMIT_DEFAULT;
		private OutlierDetectionConfig outlierDetection = OutlierDetectionConfig.defaults();

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
		 * <p>默认允许：{@code java.}、{@code javax.}、框架包 {@code io.github.sisyfyzhang.steadyrpc.core.}、每个引用接口所在的包及其子包（业务异常最常见的位置）、以及方法
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

		/**
		 * 是否合并 flush（默认 true）：每条连接的 pipeline 首位加 Netty 的 {@code FlushConsolidationHandler(256, true)}。
		 * 读循环里产生的 flush（例如 IO 线程上的异步回调里发起的下一次调用）推迟到本轮读完，事件循环外来的 flush 改为提交一个
		 * flush 任务、把它之前排队的写一起带走。不改协议字节、不加定时器。关掉它只用于对照实验（同一构建内 A/B），
		 * 见 docs/probes/flush-and-executor.md。
		 */
		public Builder<T> flushConsolidation(boolean flushConsolidation) {
			this.flushConsolidation = flushConsolidation;
			return this;
		}

		/**
		 * 每条连接的写缓冲水位线（字节，默认 32KiB / 64KiB，即 Netty 缺省），设到 {@code ChannelOption.WRITE_BUFFER_WATER_MARK}。
		 * 待写字节（已交给 Netty、还没写进套接字的请求帧）超过 high 时连接变为不可写，降到 low 以下恢复。
		 * {@code connectionsPerNode > 1} 时取连接优先选可写的那条；全部不可写时照常写（不阻塞调用方），
		 * 直到待写字节超过 {@link #outboundHardLimit(long)}。
		 */
		public Builder<T> writeBufferWaterMark(int low, int high) {
			this.writeBufferLow = low;
			this.writeBufferHigh = high;
			return this;
		}

		/**
		 * 每条连接待写字节的硬上限（字节，默认高水位 × {@value RpcClientConfig#DEFAULT_OUTBOUND_HARD_LIMIT_MULTIPLIER}，0 = 关闭）。
		 * 连接不可写且待写字节超过它时，新请求不写出，以 {@code RpcException}（消息含 "request not sent"）失败——
		 * 请求确定没有发出，属于可安全换节点重试的传输失败（failover 且 {@code retries > 0} 时换节点）。
		 * 防的是对端不读 / 网络拥塞时请求帧在本端直接内存里无界堆积。
		 */
		public Builder<T> outboundHardLimit(long outboundHardLimit) {
			if (outboundHardLimit < 0) {
				throw new IllegalArgumentException("outboundHardLimit must be >= 0 (0 = off): " + outboundHardLimit);
			}
			this.outboundHardLimit = outboundHardLimit;
			return this;
		}

		/**
		 * 离群摘除（默认开启，{@link OutlierDetectionConfig#defaults()}）：按提供者地址统计最近 10 秒的尝试与坏结果（超时、DEADLINE_EXCEEDED、
		 * 传输失败、SERVER_OVERLOADED；业务异常不算），坏结果占比或连续坏结果过阈值时把该地址从候选里摘掉一段时间，到期自动放回；
		 * 最多摘掉一半的提供者。规则见 {@link OutlierDetectionConfig}；关闭用 {@link OutlierDetectionConfig#disabled()}。
		 * 观测：{@code RpcClient.outlierEjections() / outlierRestores() / ejectedNodes()}。
		 */
		public Builder<T> outlierDetection(OutlierDetectionConfig outlierDetection) {
			if (outlierDetection == null) {
				throw new IllegalArgumentException("outlierDetection must not be null (use OutlierDetectionConfig.disabled() to turn it off)");
			}
			this.outlierDetection = outlierDetection;
			return this;
		}

		public T build() {
			return finisher.apply(new RpcClientConfig(registry, serializer, loadBalance, cluster,
					connectionsPerNode, defaultTimeout, emptyProtection, clientFilters, serializerOptions,
					shutdownTimeout, exceptionAllowlist, ioThreads, syncWaitGrace, flushConsolidation,
					writeBufferLow, writeBufferHigh, outboundHardLimit, outlierDetection));
		}
	}
}
