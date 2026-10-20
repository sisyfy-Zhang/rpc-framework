package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.metrics.micrometer.MicrometerServerFilter;
import com.feynman.rpc.metrics.micrometer.RpcServerMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 压测服务端：导出 {@link BenchServiceImpl}，其余全部用 RpcServer 的缺省配置（执行器、连接参数），
 * 压的就是"开箱即用"的框架。{@code server --executor} / {@code --flush-consolidation} 只用于对照实验（非缺省配置，结果另放目录、标明）。{@code registry} 为 null 时不注册（客户端用 {@code --direct}）。
 */
final class BenchServer implements AutoCloseable {

	static final String EXECUTOR_FIXED = "fixed";
	static final String EXECUTOR_VIRTUAL = "virtual";
	static final String EXECUTOR_DIRECT = "direct";

	private final RpcServer server;
	private final Map<String, String> environment;

	private BenchServer(RpcServer server, Map<String, String> environment) {
		this.server = server;
		this.environment = environment;
	}

	/**
	 * 绑定端口并（若配置了注册中心）等到注册完成再返回——"ready"应当意味着可被发现。
	 *
	 * @param host 注册时公布的地址；null 时由框架探测本机地址
	 */
	static BenchServer start(int port, String host, RegistryConfig registry, String serializer) throws Exception {
		return start(port, host, registry, serializer, null);
	}

	/**
	 * @param metrics 非 null 时把 {@link MicrometerServerFilter} 加进服务端链并绑定在途 / 连接数 Gauge（{@code --metrics-port}）；
	 *                服务端环境里的 {@code metrics_enabled} 如实记录是否开启——指标 Filter 在热路径上，开没开要进报告
	 */
	static BenchServer start(int port, String host, RegistryConfig registry, String serializer, MeterRegistry metrics) throws Exception {
		return start(port, host, registry, serializer, metrics, 0);
	}

	/**
	 * @param ioThreads {@code RpcServerConfig.ioThreads}（{@code server --io-threads}）；0 = Netty 缺省（2 × 可用核数）。
	 *                  实际值与业务执行器一起记进服务端环境（{@code server_io_threads} / {@code server_executor}），对比报告据此写明两端配置
	 */
	static BenchServer start(int port, String host, RegistryConfig registry, String serializer, MeterRegistry metrics, int ioThreads)
			throws Exception {
		return start(port, host, registry, serializer, metrics, ioThreads, ExecutorConfig.defaults(), true);
	}

	/**
	 * @param executor           业务执行器（{@code server --executor}）；缺省 {@code ExecutorConfig.defaults()}。direct 是 opt-in 的非缺省配置，
	 *                           服务端环境的 {@code server_executor_kind} 记 {@value #EXECUTOR_DIRECT}，压测端据此核对卡片的 {@code target.server_executor}
	 * @param flushConsolidation {@code RpcServerConfig.flushConsolidation}（{@code server --flush-consolidation}），记进 {@code server_flush_consolidation}
	 */
	static BenchServer start(int port, String host, RegistryConfig registry, String serializer, MeterRegistry metrics, int ioThreads,
			ExecutorConfig executor, boolean flushConsolidation) throws Exception {
		var builder = RpcServer.builder().port(port).serializer(serializer).ioThreads(ioThreads).executor(executor)
				.flushConsolidation(flushConsolidation);
		if (metrics != null) {
			builder.serverFilter(new MicrometerServerFilter(metrics));
		}
		if (host != null) {
			builder.host(host);
		}
		if (registry != null) {
			builder.registry(registry);
		}
		RpcServer server = builder.build();
		try {
			// 环境在启动时采集一次（含外部命令，可能要几百毫秒），之后 environment() 直接返回，不占压测时间
			Map<String, String> environment = new LinkedHashMap<>(EnvironmentProbe.collectAsStrings());
			environment.put("metrics_enabled", String.valueOf(metrics != null));
			environment.put("transport", "rpc");
			environment.put("server_io_threads", server.config().ioThreads() == 0 ? HarnessOptions.nettyDefaultIoThreadsText()
					: String.valueOf(server.config().ioThreads()));
			environment.put("server_executor", describe(server.config().executor()));
			environment.put("server_executor_kind", kind(server.config().executor()));
			environment.put("server_flush_consolidation", server.config().flushConsolidation() ? "on" : "off");
			server.export(BenchService.class, new BenchServiceImpl(environment));
			if (metrics != null) {
				RpcServerMetrics.bind(server, metrics);
			}
			server.start();
			server.registered().get(30, TimeUnit.SECONDS);
			return new BenchServer(server, java.util.Collections.unmodifiableMap(environment));
		} catch (Exception e) {
			server.close();
			throw e;
		}
	}

	int port() {
		return server.port();
	}

	/** 业务执行器的一句话描述（缺省：固定池 2 × 可用核数、有界队列 1000；业务方法不在 IO 线程上执行）。 */
	static String describe(ExecutorConfig executor) {
		return switch (executor) {
			case ExecutorConfig.Fixed f -> "fixed pool (" + f.threads() + " threads, queue " + f.queueCapacity()
					+ "): business methods run off the IO threads";
			case ExecutorConfig.VirtualThreads v -> "virtual thread per request (max " + v.maxConcurrent() + ")";
			case ExecutorConfig.Direct d -> "direct (NOT the default): business methods run on the IO threads (max in-flight "
					+ d.maxConcurrent() + ")";
		};
	}

	/** 卡片 {@code target.server_executor} 的取值：{@value #EXECUTOR_FIXED} / {@value #EXECUTOR_VIRTUAL} / {@value #EXECUTOR_DIRECT}。 */
	static String kind(ExecutorConfig executor) {
		return switch (executor) {
			case ExecutorConfig.Fixed f -> EXECUTOR_FIXED;
			case ExecutorConfig.VirtualThreads v -> EXECUTOR_VIRTUAL;
			case ExecutorConfig.Direct d -> EXECUTOR_DIRECT;
		};
	}

	RpcServer rpcServer() {
		return server;
	}

	/** 启动时采集的服务端环境（{@code BenchService.environment()} 返回的同一份），HTTP 对照端点复用它。 */
	Map<String, String> environment() {
		return environment;
	}

	@Override
	public void close() {
		server.close();
	}
}
