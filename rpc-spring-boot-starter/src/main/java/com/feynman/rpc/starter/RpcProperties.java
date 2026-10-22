package com.feynman.rpc.starter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.config.OutlierDetectionConfig;
import com.feynman.rpc.core.config.RpcClientConfig;
import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.registry.RegistryConfig;

/**
 * 绑定 rpc.* 配置，并转换成 core 的不可变配置对象。这是 properties 进入框架的唯一通道：
 * 旧实现把绑定结果写进 System 属性再由 core 读回，同 JVM 两个应用上下文会互相覆盖。
 *
 * <p>除注册中心外，未设置的项保持 null，由 core 的 builder 给出缺省值——默认值只在 core 定义一份，
 * Boot 用户和纯 Java 用户得到的行为一致。属性与 builder 方法一一对应（见 README 的配置项表）。
 *
 * <pre>
 * rpc:
 *   registry: { type: zookeeper, address: localhost:2181 }
 *   serializer: protobuf
 *   serializer-options: { protobuf.allowed-packages: "com.acme.api.,com.acme.dto." }
 *   client: { load-balance: p2c, cluster: failover, default-timeout: 3s, empty-protection: true,
 *             shutdown-timeout: 10s, exception-allowlist: [com.acme.common.], io-threads: 0, sync-wait-grace: 1s,
 *             flush-consolidation: true, write-buffer-low: 32KB, write-buffer-high: 64KB, outbound-hard-limit: 1MB,
 *             outlier-detection: { enabled: true, min-requests: 20, failure-ratio: 0.5, consecutive-failures: 5,
 *                                  base-ejection: 10s, max-ejection: 60s, max-ejection-percent: 50 } }
 *   server: { port: 9000, host: 10.0.0.5, weight: 1, shutdown-timeout: 10s, deregister-grace: 1s, io-threads: 0,
 *             remote-stack-trace: true, remote-stack-trace-max-bytes: 8192, flush-consolidation: true,
 *             write-buffer-low: 32KB, write-buffer-high: 64KB, outbound-backpressure: true, outbound-hard-limit: 1MB,
 *             executor: { type: fixed, threads: 16, queue-capacity: 1000 } }   # 或 { type: virtual, max-concurrent: 10000 }
 *                                                                          # 或 { type: direct, max-concurrent: 10000 }（见 ExecutorConfig.direct 的警示）
 * </pre>
 */
@ConfigurationProperties(prefix = "rpc")
public class RpcProperties {

	private final Registry registry = new Registry();

	/**
	 * 序列化 SPI 扩展名：客户端的首选方式、服务端协商无交集时的兜底方式。不设置时为 protobuf（Serializer 的 SPI 缺省名，
	 * 需要 rpc-serialize-protobuf）；可选 fastjson2（rpc-serialize-fastjson2）。classpath 上要有对应模块。
	 */
	private String serializer;

	/**
	 * 交给各序列化实现的配置项，客户端与服务端共用。键以实现的 SPI 名为前缀，例如
	 * protobuf.allowed-packages=com.acme.api.,com.acme.dto.：Protostuff 在多态位置（Object / 接口 / 抽象类字段）
	 * 按类名加载时允许的包前缀白名单。
	 */
	private Map<String, String> serializerOptions = new LinkedHashMap<>();

	private final Client client = new Client();

	private final Server server = new Server();

	public Registry getRegistry() {
		return registry;
	}

	public String getSerializer() {
		return serializer;
	}

	public void setSerializer(String serializer) {
		this.serializer = serializer;
	}

	public Map<String, String> getSerializerOptions() {
		return serializerOptions;
	}

	public void setSerializerOptions(Map<String, String> serializerOptions) {
		this.serializerOptions = serializerOptions;
	}

	public Client getClient() {
		return client;
	}

	public Server getServer() {
		return server;
	}

	public RegistryConfig toRegistryConfig() {
		return new RegistryConfig(registry.type, registry.address, registry.properties);
	}

	/** 只由 properties 得到的客户端配置（不含 Filter bean 与 customizer）。 */
	public RpcClientConfig toClientConfig() {
		RpcClientConfig.Builder<RpcClientConfig> builder = RpcClientConfig.builder();
		applyTo(builder);
		return builder.build();
	}

	/** 只由 properties 得到的服务端配置（不含 Filter bean 与 customizer）。 */
	public RpcServerConfig toServerConfig() {
		RpcServerConfig.Builder<RpcServerConfig> builder = RpcServerConfig.builder();
		applyTo(builder);
		return builder.build();
	}

	/** 把已设置的 rpc.* / rpc.client.* 写进 builder；未设置的项不碰，保留 core 的缺省值。 */
	public void applyTo(RpcClientConfig.Builder<?> builder) {
		builder.registry(toRegistryConfig())
				.serializer(serializer)
				.loadBalance(client.loadBalance)
				.cluster(client.cluster);
		serializerOptions.forEach(builder::serializerOption);
		if (client.connectionsPerNode != null) {
			builder.connectionsPerNode(client.connectionsPerNode);
		}
		if (client.defaultTimeout != null) {
			builder.defaultTimeout(client.defaultTimeout);
		}
		if (client.emptyProtection != null) {
			builder.emptyProtection(client.emptyProtection);
		}
		if (client.shutdownTimeout != null) {
			builder.shutdownTimeout(client.shutdownTimeout);
		}
		if (client.ioThreads != null) {
			builder.ioThreads(client.ioThreads);
		}
		if (client.syncWaitGrace != null) {
			builder.syncWaitGrace(client.syncWaitGrace);
		}
		if (client.flushConsolidation != null) {
			builder.flushConsolidation(client.flushConsolidation);
		}
		if (client.writeBufferLow != null || client.writeBufferHigh != null) {
			builder.writeBufferWaterMark(bytes("rpc.client.write-buffer-low", client.writeBufferLow, RpcClientConfig.DEFAULT_WRITE_BUFFER_LOW),
					bytes("rpc.client.write-buffer-high", client.writeBufferHigh, RpcClientConfig.DEFAULT_WRITE_BUFFER_HIGH));
		}
		if (client.outboundHardLimit != null) {
			builder.outboundHardLimit(client.outboundHardLimit.toBytes());
		}
		builder.exceptionAllowlist(client.exceptionAllowlist.toArray(new String[0]));
		OutlierDetection od = client.outlierDetection;
		if (od.anySet()) {
			OutlierDetectionConfig.Builder outlier = OutlierDetectionConfig.builder();
			if (od.enabled != null) {
				outlier.enabled(od.enabled);
			}
			if (od.minRequests != null) {
				outlier.minRequests(od.minRequests);
			}
			if (od.failureRatio != null) {
				outlier.failureRatio(od.failureRatio);
			}
			if (od.consecutiveFailures != null) {
				outlier.consecutiveFailures(od.consecutiveFailures);
			}
			if (od.baseEjection != null) {
				outlier.baseEjection(od.baseEjection);
			}
			if (od.maxEjection != null) {
				outlier.maxEjection(od.maxEjection);
			}
			if (od.maxEjectionPercent != null) {
				outlier.maxEjectionPercent(od.maxEjectionPercent);
			}
			builder.outlierDetection(outlier.build());
		}
	}

	/** DataSize → int 字节数；未设置时取 core 的缺省值（水位线两项只设了一项时，另一项保持缺省）。 */
	private static int bytes(String property, DataSize value, int defaultBytes) {
		if (value == null) {
			return defaultBytes;
		}
		long bytes = value.toBytes();
		if (bytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(property + " must be at most " + Integer.MAX_VALUE + " bytes: " + value);
		}
		return (int) bytes;
	}

	/** 把已设置的 rpc.* / rpc.server.* 写进 builder；未设置的项不碰，保留 core 的缺省值。 */
	public void applyTo(RpcServerConfig.Builder<?> builder) {
		builder.registry(toRegistryConfig())
				.serializer(serializer)
				.host(server.host);
		serializerOptions.forEach(builder::serializerOption);
		if (server.port != null) {
			builder.port(server.port);
		}
		if (server.weight != null) {
			builder.weight(server.weight);
		}
		if (server.shutdownTimeout != null) {
			builder.shutdownTimeout(server.shutdownTimeout);
		}
		if (server.deregisterGrace != null) {
			builder.deregisterGrace(server.deregisterGrace);
		}
		if (server.remoteStackTrace != null) {
			builder.remoteStackTrace(server.remoteStackTrace);
		}
		if (server.remoteStackTraceMaxBytes != null) {
			builder.remoteStackTraceMaxBytes(server.remoteStackTraceMaxBytes);
		}
		if (server.ioThreads != null) {
			builder.ioThreads(server.ioThreads);
		}
		if (server.flushConsolidation != null) {
			builder.flushConsolidation(server.flushConsolidation);
		}
		if (server.writeBufferLow != null || server.writeBufferHigh != null) {
			builder.writeBufferWaterMark(bytes("rpc.server.write-buffer-low", server.writeBufferLow, RpcServerConfig.DEFAULT_WRITE_BUFFER_LOW),
					bytes("rpc.server.write-buffer-high", server.writeBufferHigh, RpcServerConfig.DEFAULT_WRITE_BUFFER_HIGH));
		}
		if (server.outboundBackpressure != null) {
			builder.outboundBackpressure(server.outboundBackpressure);
		}
		if (server.outboundHardLimit != null) {
			builder.outboundHardLimit(server.outboundHardLimit.toBytes());
		}
		Executor executor = server.executor;
		if (executor.type == ExecutorType.VIRTUAL) {
			builder.executor(executor.maxConcurrent != null
					? ExecutorConfig.virtualThreads(executor.maxConcurrent)
					: ExecutorConfig.virtualThreads());
		} else if (executor.type == ExecutorType.DIRECT) {
			builder.executor(executor.maxConcurrent != null
					? ExecutorConfig.direct(executor.maxConcurrent)
					: ExecutorConfig.direct());
		} else if (executor.threads != null || executor.queueCapacity != null) {
			int threads = executor.threads != null ? executor.threads
					: ((ExecutorConfig.Fixed) ExecutorConfig.defaults()).threads();
			int queueCapacity = executor.queueCapacity != null ? executor.queueCapacity : ExecutorConfig.DEFAULT_QUEUE_CAPACITY;
			builder.executor(ExecutorConfig.fixed(threads, queueCapacity));
		}
	}

	/** rpc.registry.*：客户端与服务端共用同一个注册中心配置。 */
	public static class Registry {

		/** RegistryFactory 的 SPI 扩展名：zookeeper 或 nacos。 */
		private String type = RegistryConfig.ZOOKEEPER;

		/** 注册中心地址。 */
		private String address = "localhost:2181";

		/** 实现相关的附加属性（如 ZK 的 session-timeout-ms、Nacos 的 namespace）。 */
		private Map<String, String> properties = new LinkedHashMap<>();

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getAddress() {
			return address;
		}

		public void setAddress(String address) {
			this.address = address;
		}

		public Map<String, String> getProperties() {
			return properties;
		}

		public void setProperties(Map<String, String> properties) {
			this.properties = properties;
		}
	}

	/** rpc.client.*。 */
	public static class Client {

		/** 负载均衡 SPI 扩展名：roundrobin（默认）、p2c（在途数少者优先，慢节点场景更好）。 */
		private String loadBalance;

		/**
		 * 容错 SPI 扩展名：failfast（默认）、failover、failback。只有传输失败（连不上、连接重置、
		 * 服务端过载拒绝）才会重试，且仅当引用声明了 retries 大于 0。
		 */
		private String cluster;

		/** 到每个提供者节点的连接数（默认 2）。 */
		private Integer connectionsPerNode;

		/**
		 * 默认调用超时（默认 20s），单个引用可用 @RpcReference(timeout=...) 覆盖。它是整次调用（含所有重试）
		 * 共享的预算，耗尽后不再重试。
		 */
		private Duration defaultTimeout;

		/** 推空保护：注册中心推送空列表时保留旧地址并告警（默认 true），防止注册中心抖动把全部连接摘光。 */
		private Boolean emptyProtection;

		/** RpcClient 关闭时等待在途调用完成的上限（默认 10s），超时后剩余调用以 RpcException 失败。 */
		private Duration shutdownTimeout;

		/**
		 * 额外允许按类名还原成同类型的远端异常包前缀（以 . 结尾表示包及子包）。默认已允许 java.、
		 * javax.、com.feynman.rpc.core.、引用接口所在包及子包、方法 throws 声明的类型；白名单外的类名
		 * 不加载，降级为 RpcRemoteException（保留原类名与消息）。
		 */
		private List<String> exceptionAllowlist = new ArrayList<>();

		/** 客户端 IO 事件循环线程数（默认 0 = Netty 缺省，2 × 可用核数）；只影响本客户端。 */
		private Integer ioThreads;

		/**
		 * 同步调用等待的第二道上界（默认 1s）：最多等"deadline 剩余时间 + 该值"，超过即抛 RpcDeadlineExceededException
		 * 并让该次请求失败。只在 deadline 定时器没有按时触发时起作用；异步方法不受约束。
		 */
		private Duration syncWaitGrace;

		/**
		 * 是否合并 flush（默认 true）：连接 pipeline 首位加 Netty 的 FlushConsolidationHandler。关掉只用于对照实验。
		 */
		private Boolean flushConsolidation;

		/** 每条连接写缓冲的低水位（默认 32KB，即 Netty 缺省）：待写字节降到它以下时连接恢复可写。纯数字按字节。 */
		private DataSize writeBufferLow;

		/**
		 * 每条连接写缓冲的高水位（默认 64KB，即 Netty 缺省）：待写字节超过它时连接变为不可写，同一节点有多条连接时优先选可写的。
		 */
		private DataSize writeBufferHigh;

		/**
		 * 每条连接待写字节的硬上限（默认高水位 × 16，0 = 关闭）：连接不可写且超过它时，新请求不写出、以可重试的传输失败结束。
		 */
		private DataSize outboundHardLimit;

		/** 离群摘除（rpc.client.outlier-detection.*，缺省开启）。 */
		private final OutlierDetection outlierDetection = new OutlierDetection();

		public OutlierDetection getOutlierDetection() {
			return outlierDetection;
		}

		public DataSize getWriteBufferLow() {
			return writeBufferLow;
		}

		public void setWriteBufferLow(DataSize writeBufferLow) {
			this.writeBufferLow = writeBufferLow;
		}

		public DataSize getWriteBufferHigh() {
			return writeBufferHigh;
		}

		public void setWriteBufferHigh(DataSize writeBufferHigh) {
			this.writeBufferHigh = writeBufferHigh;
		}

		public DataSize getOutboundHardLimit() {
			return outboundHardLimit;
		}

		public void setOutboundHardLimit(DataSize outboundHardLimit) {
			this.outboundHardLimit = outboundHardLimit;
		}

		public Boolean getFlushConsolidation() {
			return flushConsolidation;
		}

		public void setFlushConsolidation(Boolean flushConsolidation) {
			this.flushConsolidation = flushConsolidation;
		}

		public Duration getSyncWaitGrace() {
			return syncWaitGrace;
		}

		public void setSyncWaitGrace(Duration syncWaitGrace) {
			this.syncWaitGrace = syncWaitGrace;
		}

		public Integer getIoThreads() {
			return ioThreads;
		}

		public void setIoThreads(Integer ioThreads) {
			this.ioThreads = ioThreads;
		}

		public String getLoadBalance() {
			return loadBalance;
		}

		public void setLoadBalance(String loadBalance) {
			this.loadBalance = loadBalance;
		}

		public String getCluster() {
			return cluster;
		}

		public void setCluster(String cluster) {
			this.cluster = cluster;
		}

		public Integer getConnectionsPerNode() {
			return connectionsPerNode;
		}

		public void setConnectionsPerNode(Integer connectionsPerNode) {
			this.connectionsPerNode = connectionsPerNode;
		}

		public Duration getDefaultTimeout() {
			return defaultTimeout;
		}

		public void setDefaultTimeout(Duration defaultTimeout) {
			this.defaultTimeout = defaultTimeout;
		}

		public Boolean getEmptyProtection() {
			return emptyProtection;
		}

		public void setEmptyProtection(Boolean emptyProtection) {
			this.emptyProtection = emptyProtection;
		}

		public Duration getShutdownTimeout() {
			return shutdownTimeout;
		}

		public void setShutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
		}

		public List<String> getExceptionAllowlist() {
			return exceptionAllowlist;
		}

		public void setExceptionAllowlist(List<String> exceptionAllowlist) {
			this.exceptionAllowlist = exceptionAllowlist;
		}
	}

	/**
	 * rpc.client.outlier-detection.*：按提供者的离群摘除（缺省开启）。最近 10 秒内坏结果（超时、DEADLINE_EXCEEDED、传输失败、
	 * SERVER_OVERLOADED；业务异常不算）占比或连续坏结果过阈值时，把该提供者从负载均衡候选里摘掉一段时间，到期自动放回。
	 * 未设置的项取 core 的缺省值（OutlierDetectionConfig）。
	 */
	public static class OutlierDetection {

		/** 是否开启离群摘除（默认 true）。 */
		private Boolean enabled;

		/** 坏结果占比条件生效所需的最近 10 秒内最少尝试数（默认 20）。 */
		private Integer minRequests;

		/** 坏结果占比阈值，(0, 1]（默认 0.5）。 */
		private Double failureRatio;

		/** 连续坏结果阈值（默认 5，0 = 不用这个条件）。 */
		private Integer consecutiveFailures;

		/** 第一次摘除的时长（默认 10s）；第 k 次摘除为 k 倍，上限 max-ejection。 */
		private Duration baseEjection;

		/** 摘除时长上限（默认 60s）。 */
		private Duration maxEjection;

		/** 最多摘除的提供者比例（百分数，默认 50）：摘掉之后健康提供者至少保留 ceil(n × (100 − p) / 100) 个、且至少 1 个。 */
		private Integer maxEjectionPercent;

		boolean anySet() {
			return enabled != null || minRequests != null || failureRatio != null || consecutiveFailures != null || baseEjection != null
					|| maxEjection != null || maxEjectionPercent != null;
		}

		public Boolean getEnabled() {
			return enabled;
		}

		public void setEnabled(Boolean enabled) {
			this.enabled = enabled;
		}

		public Integer getMinRequests() {
			return minRequests;
		}

		public void setMinRequests(Integer minRequests) {
			this.minRequests = minRequests;
		}

		public Double getFailureRatio() {
			return failureRatio;
		}

		public void setFailureRatio(Double failureRatio) {
			this.failureRatio = failureRatio;
		}

		public Integer getConsecutiveFailures() {
			return consecutiveFailures;
		}

		public void setConsecutiveFailures(Integer consecutiveFailures) {
			this.consecutiveFailures = consecutiveFailures;
		}

		public Duration getBaseEjection() {
			return baseEjection;
		}

		public void setBaseEjection(Duration baseEjection) {
			this.baseEjection = baseEjection;
		}

		public Duration getMaxEjection() {
			return maxEjection;
		}

		public void setMaxEjection(Duration maxEjection) {
			this.maxEjection = maxEjection;
		}

		public Integer getMaxEjectionPercent() {
			return maxEjectionPercent;
		}

		public void setMaxEjectionPercent(Integer maxEjectionPercent) {
			this.maxEjectionPercent = maxEjectionPercent;
		}
	}

	/** rpc.server.*：只有容器里存在 @RpcService bean 时服务端才会真正启动。 */
	public static class Server {

		/** 监听端口（默认 9000）；0 表示由系统分配随机端口，注册的是实际绑定的端口。 */
		private Integer port;

		/** 注册给消费端的地址；不设则自动探测本机地址（多网卡 / 容器环境建议显式设置）。 */
		private String host;

		/** 本服务端所有服务的默认注册权重（默认 1），单个服务可用 @RpcService(weight=...) 覆盖。 */
		private Integer weight;

		/** IO 事件循环（worker）线程数（默认 0 = Netty 缺省，2 × 可用核数）；业务方法在 executor 上执行，与它无关。 */
		private Integer ioThreads;

		/**
		 * 是否合并 flush（默认 true）：连接 pipeline 首位加 Netty 的 FlushConsolidationHandler，业务线程并发回包时多个回包合成一次写出。
		 * 关掉只用于对照实验。
		 */
		private Boolean flushConsolidation;

		/** 每条已接受连接写缓冲的低水位（默认 32KB，即 Netty 缺省）：待写字节降到它以下时恢复可写（背压开时恢复读）。纯数字按字节。 */
		private DataSize writeBufferLow;

		/** 每条已接受连接写缓冲的高水位（默认 64KB，即 Netty 缺省）：回包待写字节超过它时连接变为不可写（背压开时暂停读）。 */
		private DataSize writeBufferHigh;

		/**
		 * 出站背压（默认 true）：连接不可写时暂停读它，恢复可写再读，客户端不读回包时回包积压被封顶。
		 * 暂停读超过 30s 的连接被读空闲关闭。关掉只用于对照实验。
		 */
		private Boolean outboundBackpressure;

		/**
		 * 每条连接待写字节的硬上限（默认高水位 × 16，0 = 关闭）：超过时新请求直接回 SERVER_OVERLOADED（业务不执行）。
		 * 只在 outbound-backpressure 开着时生效。
		 */
		private DataSize outboundHardLimit;

		public DataSize getWriteBufferLow() {
			return writeBufferLow;
		}

		public void setWriteBufferLow(DataSize writeBufferLow) {
			this.writeBufferLow = writeBufferLow;
		}

		public DataSize getWriteBufferHigh() {
			return writeBufferHigh;
		}

		public void setWriteBufferHigh(DataSize writeBufferHigh) {
			this.writeBufferHigh = writeBufferHigh;
		}

		public Boolean getOutboundBackpressure() {
			return outboundBackpressure;
		}

		public void setOutboundBackpressure(Boolean outboundBackpressure) {
			this.outboundBackpressure = outboundBackpressure;
		}

		public DataSize getOutboundHardLimit() {
			return outboundHardLimit;
		}

		public void setOutboundHardLimit(DataSize outboundHardLimit) {
			this.outboundHardLimit = outboundHardLimit;
		}

		public Boolean getFlushConsolidation() {
			return flushConsolidation;
		}

		public void setFlushConsolidation(Boolean flushConsolidation) {
			this.flushConsolidation = flushConsolidation;
		}

		public Integer getIoThreads() {
			return ioThreads;
		}

		public void setIoThreads(Integer ioThreads) {
			this.ioThreads = ioThreads;
		}

		/** 优雅停机等待在途请求完成的上限（默认 10s），超时后中断仍在执行的业务并关闭连接。 */
		private Duration shutdownTimeout;

		/**
		 * 摘注册之后、开始拒绝新请求之前的宽限期（默认 0，生产建议 1s）：让注册中心把下线推送传播到消费端，
		 * 推送延迟内到达的请求照常处理而不是收到 SERVER_OVERLOADED。直接加在停机总时长上。
		 */
		private Duration deregisterGrace;

		/** 异常回包是否携带服务端堆栈（默认 true）。对外暴露的服务可关闭，避免泄露内部类名与行号。 */
		private Boolean remoteStackTrace;

		/** 异常回包携带的堆栈 UTF-8 字节上限（默认 8192，最小 256），超出截断并以 "…(truncated)" 结尾。 */
		private Integer remoteStackTraceMaxBytes;

		private final Executor executor = new Executor();

		public Integer getPort() {
			return port;
		}

		public void setPort(Integer port) {
			this.port = port;
		}

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public Integer getWeight() {
			return weight;
		}

		public void setWeight(Integer weight) {
			this.weight = weight;
		}

		public Duration getShutdownTimeout() {
			return shutdownTimeout;
		}

		public void setShutdownTimeout(Duration shutdownTimeout) {
			this.shutdownTimeout = shutdownTimeout;
		}

		public Duration getDeregisterGrace() {
			return deregisterGrace;
		}

		public void setDeregisterGrace(Duration deregisterGrace) {
			this.deregisterGrace = deregisterGrace;
		}

		public Boolean getRemoteStackTrace() {
			return remoteStackTrace;
		}

		public void setRemoteStackTrace(Boolean remoteStackTrace) {
			this.remoteStackTrace = remoteStackTrace;
		}

		public Integer getRemoteStackTraceMaxBytes() {
			return remoteStackTraceMaxBytes;
		}

		public void setRemoteStackTraceMaxBytes(Integer remoteStackTraceMaxBytes) {
			this.remoteStackTraceMaxBytes = remoteStackTraceMaxBytes;
		}

		public Executor getExecutor() {
			return executor;
		}
	}

	/** rpc.server.executor.*：业务执行模型。 */
	public static class Executor {

		/**
		 * 执行模型：fixed（固定线程池 + 有界队列，默认）、virtual（每请求一个虚拟线程 + 并发上限）或 direct（opt-in：业务方法在
		 * IO 线程上执行。业务方法阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法；与 gRPC directExecutor 同类）。
		 */
		private ExecutorType type = ExecutorType.FIXED;

		/** 固定池线程数（仅 type=fixed）；不设则为 CPU 核数 × 2。 */
		private Integer threads;

		/** 固定池排队上限（仅 type=fixed，默认 1000，0 表示不排队）；满了的请求回 SERVER_OVERLOADED。 */
		private Integer queueCapacity;

		/** 虚拟线程同时执行上限（type=virtual）/ 在途请求上限（type=direct），默认 10000；超出的请求回 SERVER_OVERLOADED。 */
		private Integer maxConcurrent;

		public ExecutorType getType() {
			return type;
		}

		public void setType(ExecutorType type) {
			this.type = type;
		}

		public Integer getThreads() {
			return threads;
		}

		public void setThreads(Integer threads) {
			this.threads = threads;
		}

		public Integer getQueueCapacity() {
			return queueCapacity;
		}

		public void setQueueCapacity(Integer queueCapacity) {
			this.queueCapacity = queueCapacity;
		}

		public Integer getMaxConcurrent() {
			return maxConcurrent;
		}

		public void setMaxConcurrent(Integer maxConcurrent) {
			this.maxConcurrent = maxConcurrent;
		}
	}

	/** 业务执行模型，对应 core 的 ExecutorConfig.fixed(...) / ExecutorConfig.virtualThreads(...) / ExecutorConfig.direct(...)。 */
	public enum ExecutorType {
		/** 固定线程池 + 有界队列。 */
		FIXED,
		/** 每请求一个虚拟线程，信号量限制同时执行数。 */
		VIRTUAL,
		/** opt-in：业务方法在 IO 线程上执行（阻塞会卡住同一事件循环上的所有连接，仅适用于纯 CPU、微秒级的方法）。 */
		DIRECT
	}
}
