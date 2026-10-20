package com.feynman.rpc.starter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.feynman.rpc.core.config.ExecutorConfig;
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
 *             shutdown-timeout: 10s, exception-allowlist: [com.acme.common.], io-threads: 0 }
 *   server: { port: 9000, host: 10.0.0.5, weight: 1, shutdown-timeout: 10s, deregister-grace: 1s, io-threads: 0,
 *             remote-stack-trace: true, remote-stack-trace-max-bytes: 8192,
 *             executor: { type: fixed, threads: 16, queue-capacity: 1000 } }   # 或 { type: virtual, max-concurrent: 10000 }
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
		builder.exceptionAllowlist(client.exceptionAllowlist.toArray(new String[0]));
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
		Executor executor = server.executor;
		if (executor.type == ExecutorType.VIRTUAL) {
			builder.executor(executor.maxConcurrent != null
					? ExecutorConfig.virtualThreads(executor.maxConcurrent)
					: ExecutorConfig.virtualThreads());
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

		/** 执行模型：fixed（固定线程池 + 有界队列，默认）或 virtual（每请求一个虚拟线程 + 并发上限）。 */
		private ExecutorType type = ExecutorType.FIXED;

		/** 固定池线程数（仅 type=fixed）；不设则为 CPU 核数 × 2。 */
		private Integer threads;

		/** 固定池排队上限（仅 type=fixed，默认 1000，0 表示不排队）；满了的请求回 SERVER_OVERLOADED。 */
		private Integer queueCapacity;

		/** 虚拟线程同时执行上限（仅 type=virtual，默认 10000）；超出的请求回 SERVER_OVERLOADED。 */
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

	/** 业务执行模型，对应 core 的 ExecutorConfig.fixed(...) / ExecutorConfig.virtualThreads(...)。 */
	public enum ExecutorType {
		/** 固定线程池 + 有界队列。 */
		FIXED,
		/** 每请求一个虚拟线程，信号量限制同时执行数。 */
		VIRTUAL
	}
}
