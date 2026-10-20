package com.feynman.rpc.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.feynman.rpc.core.filter.Filter;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.serialize.Serializer;

/**
 * RpcServer 的不可变配置，理由同 {@link RpcClientConfig}。
 *
 * <ul>
 *   <li>{@code port} 为 0 表示由操作系统分配随机端口（测试与同机多实例），注册的是实际绑定的端口。</li>
 *   <li>{@code host} 是注册给消费端的地址，null 表示自动探测本机地址；多网卡 / 容器环境需要显式指定。</li>
 *   <li>{@code registry} 可为 null：只监听不注册，用于直连测试。</li>
 *   <li>{@code remoteStackTrace}（默认 true）：BIZ_EXCEPTION / SERVER_ERROR 回包是否携带服务端堆栈文本。
 *       对外暴露的服务可以关闭，避免把内部类名与行号泄露给调用方。</li>
 *   <li>{@code remoteStackTraceMaxBytes}（默认 8192）：回传堆栈的 UTF-8 字节上限，超出在字符边界截断并以
 *       {@value #TRUNCATION_MARKER} 结尾。</li>
 *   <li>{@code shutdownTimeout}（默认 10s）：优雅停机时等待在途请求完成的上限。</li>
 *   <li>{@code deregisterGrace}（默认 0）：优雅停机时摘注册之后、开始拒绝新请求之前的等待时长，见
 *       {@link Builder#deregisterGrace(Duration)}。</li>
 *   <li>{@code serializerOptions}：同 {@link RpcClientConfig}。</li>
 *   <li>{@code ioThreads}（默认 0）：IO 事件循环（worker）线程数，0 = Netty 缺省（2 × 可用核数），见
 *       {@link Builder#ioThreads(int)}。</li>
 *   <li>{@code flushConsolidation}（默认 true）：连接 pipeline 最靠近 transport 处是否加 Netty 的
 *       {@code FlushConsolidationHandler}（合并 flush），见 {@link Builder#flushConsolidation(boolean)}。</li>
 * </ul>
 */
public record RpcServerConfig(
		int port,
		String host,
		RegistryConfig registry,
		String serializer,
		ExecutorConfig executor,
		int weight,
		List<Filter> serverFilters,
		boolean remoteStackTrace,
		Map<String, String> serializerOptions,
		int remoteStackTraceMaxBytes,
		Duration shutdownTimeout,
		Duration deregisterGrace,
		int ioThreads,
		boolean flushConsolidation) {

	public static final int DEFAULT_PORT = 9000;
	public static final int DEFAULT_REMOTE_STACK_TRACE_MAX_BYTES = 8192;
	public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
	public static final Duration DEFAULT_DEREGISTER_GRACE = Duration.ZERO;
	/** 0 = 交给 Netty 决定，同 {@link RpcClientConfig#DEFAULT_IO_THREADS}。 */
	public static final int DEFAULT_IO_THREADS = RpcClientConfig.DEFAULT_IO_THREADS;
	/** 同 {@link RpcClientConfig#DEFAULT_FLUSH_CONSOLIDATION}。 */
	public static final boolean DEFAULT_FLUSH_CONSOLIDATION = RpcClientConfig.DEFAULT_FLUSH_CONSOLIDATION;
	/** 堆栈被截断时追加的标记（计入字节上限）。 */
	public static final String TRUNCATION_MARKER = "…(truncated)";
	/** 上限至少能放下标记本身和一行有意义的内容。 */
	public static final int MIN_REMOTE_STACK_TRACE_MAX_BYTES = 256;

	public RpcServerConfig {
		if (port < 0 || port > 65535) {
			throw new IllegalArgumentException("port out of range: " + port);
		}
		if (host != null && host.isBlank()) {
			host = null;
		}
		serializer = RpcClientConfig.orSpiDefault(serializer, Serializer.class);
		if (executor == null) {
			executor = ExecutorConfig.defaults();
		}
		if (weight < 1) {
			throw new IllegalArgumentException("weight must be >= 1: " + weight);
		}
		serverFilters = serverFilters == null ? List.of() : List.copyOf(serverFilters);
		serializerOptions = serializerOptions == null ? Map.of() : Map.copyOf(serializerOptions);
		if (remoteStackTraceMaxBytes < MIN_REMOTE_STACK_TRACE_MAX_BYTES) {
			throw new IllegalArgumentException("remoteStackTraceMaxBytes must be >= " + MIN_REMOTE_STACK_TRACE_MAX_BYTES
					+ ": " + remoteStackTraceMaxBytes + " (use remoteStackTrace(false) to turn stack traces off)");
		}
		if (shutdownTimeout == null) {
			shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
		}
		if (shutdownTimeout.isNegative()) {
			throw new IllegalArgumentException("shutdownTimeout must not be negative: " + shutdownTimeout);
		}
		if (deregisterGrace == null) {
			deregisterGrace = DEFAULT_DEREGISTER_GRACE;
		}
		if (deregisterGrace.isNegative()) {
			throw new IllegalArgumentException("deregisterGrace must not be negative: " + deregisterGrace);
		}
		RpcClientConfig.checkIoThreads(ioThreads);
	}

	public static Builder<RpcServerConfig> builder() {
		return new Builder<>(Function.identity());
	}

	/** 见 {@link RpcClientConfig.Builder}：同一个 builder 既产出配置也产出服务端。 */
	public static final class Builder<T> {

		private final Function<RpcServerConfig, T> finisher;
		private int port = DEFAULT_PORT;
		private String host;
		private RegistryConfig registry;
		private String serializer;
		private ExecutorConfig executor;
		private int weight = 1;
		private final List<Filter> serverFilters = new ArrayList<>();
		private boolean remoteStackTrace = true;
		private final Map<String, String> serializerOptions = new LinkedHashMap<>();
		private int remoteStackTraceMaxBytes = DEFAULT_REMOTE_STACK_TRACE_MAX_BYTES;
		private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
		private Duration deregisterGrace = DEFAULT_DEREGISTER_GRACE;
		private int ioThreads = DEFAULT_IO_THREADS;
		private boolean flushConsolidation = DEFAULT_FLUSH_CONSOLIDATION;

		public Builder(Function<RpcServerConfig, T> finisher) {
			this.finisher = finisher;
		}

		public Builder<T> port(int port) {
			this.port = port;
			return this;
		}

		public Builder<T> host(String host) {
			this.host = host;
			return this;
		}

		public Builder<T> registry(RegistryConfig registry) {
			this.registry = registry;
			return this;
		}

		/** 协商无交集时的兜底序列化方式，不设置时为 Serializer 的 SPI 缺省名 {@code protobuf}。 */
		public Builder<T> serializer(String serializer) {
			this.serializer = serializer;
			return this;
		}

		public Builder<T> executor(ExecutorConfig executor) {
			this.executor = executor;
			return this;
		}

		/** 本服务端所有服务的默认注册权重；单个服务可用 {@link ServiceOptions#weight(int)} 覆盖。 */
		public Builder<T> weight(int weight) {
			this.weight = weight;
			return this;
		}

		/**
		 * 追加服务端 Filter。执行顺序按 {@link Filter#order()} 升序（越小越先），同 order 按追加顺序；
		 * 内置的 DeadlineServerFilter 由 RpcServer 自动加入。
		 */
		public Builder<T> serverFilter(Filter... filters) {
			serverFilters.addAll(Arrays.asList(filters));
			return this;
		}

		/** 异常回包是否携带服务端堆栈（默认 true）。 */
		public Builder<T> remoteStackTrace(boolean remoteStackTrace) {
			this.remoteStackTrace = remoteStackTrace;
			return this;
		}

		/** 序列化器配置项，见 {@link RpcClientConfig}。 */
		public Builder<T> serializerOption(String key, String value) {
			serializerOptions.put(key, value);
			return this;
		}

		/**
		 * 回传堆栈的字节上限（默认 8192）。为什么要上限：深调用链（Spring AOP、反射、响应式框架）的堆栈动辄几十 KB，
		 * 每个业务异常都完整回传会让错误回包比正常回包大两个数量级，在故障风暴时放大带宽与 GC 压力；排障通常只需要
		 * 栈顶几十帧。完整堆栈仍在服务端日志里。
		 */
		public Builder<T> remoteStackTraceMaxBytes(int remoteStackTraceMaxBytes) {
			this.remoteStackTraceMaxBytes = remoteStackTraceMaxBytes;
			return this;
		}

		/**
		 * 优雅停机等待在途请求完成的上限（默认 10s）。close 的顺序是 摘注册 → 拒新请求 → 等在途 → 关端口；
		 * 这个上限防止一个卡死的业务方法让进程永远退不出去（Kubernetes 的 terminationGracePeriod 默认 30s，
		 * 超了会被 SIGKILL，那时连摘注册都来不及）。
		 */
		public Builder<T> shutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * 摘注册之后、进入 DRAINING（拒绝新请求）之前的等待时长，默认 0；生产环境建议 1s 左右。
		 *
		 * <p>为什么需要：摘注册只是删掉注册中心里的节点，消费端要等注册中心把变更推送过来（ZooKeeper 的 watch 通知、
		 * Nacos 的推送 / 轮询）才会停止选择本节点。这段传播延迟里到达的请求，若服务端已经 DRAINING，就会收到
		 * SERVER_OVERLOADED——配了重试的调用会换节点，retries=0 的调用直接失败。等一个宽限期再拒绝，
		 * 让绝大多数消费端在推送到达后自然不再发来请求，DRAINING 只兜底极少数推送更慢的消费端。
		 * 默认 0 是为了让测试与单机场景的停机不平白变慢；它会直接加到停机总时长上
		 * （close 最长阻塞 ≈ deregisterGrace + shutdownTimeout + 几秒）。
		 */
		public Builder<T> deregisterGrace(Duration deregisterGrace) {
			this.deregisterGrace = deregisterGrace;
			return this;
		}

		/**
		 * IO 事件循环（{@code rpc-server-worker-*}，读写与编解码）的线程数，默认 0 = Netty 缺省（2 × 可用核数）；
		 * 接受连接的 boss 线程固定 1 个。业务方法仍在 {@link #executor(ExecutorConfig)} 上执行，与它无关。
		 */
		public Builder<T> ioThreads(int ioThreads) {
			this.ioThreads = ioThreads;
			return this;
		}

		/**
		 * 是否合并 flush（默认 true）：每条连接的 pipeline 首位加 Netty 的 {@code FlushConsolidationHandler(256, true)}。
		 * 业务线程回包时的 flush 改为向事件循环提交一个 flush 任务，在它之前排队的回包被同一次 flush 带走；
		 * 读循环里产生的 flush 推迟到本轮读完（{@code channelReadComplete}），一次读出的多个请求的回包合成一次 {@code write} 系统调用。
		 * 不改协议字节、不加定时器。关掉它只用于对照实验（同一构建内 A/B），见 docs/probes/flush-and-executor.md。
		 */
		public Builder<T> flushConsolidation(boolean flushConsolidation) {
			this.flushConsolidation = flushConsolidation;
			return this;
		}

		public T build() {
			return finisher.apply(new RpcServerConfig(port, host, registry, serializer, executor, weight, serverFilters,
					remoteStackTrace, serializerOptions, remoteStackTraceMaxBytes, shutdownTimeout, deregisterGrace, ioThreads,
					flushConsolidation));
		}
	}
}
