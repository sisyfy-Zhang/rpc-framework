package com.feynman.rpc.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.metrics.micrometer.MicrometerServerFilter;
import com.feynman.rpc.metrics.micrometer.RpcServerMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 压测服务端：导出 {@link BenchServiceImpl}，其余全部用 RpcServer 的缺省配置（执行器、连接参数），
 * 压的就是"开箱即用"的框架。{@code registry} 为 null 时不注册（客户端用 {@code --direct}）。
 */
final class BenchServer implements AutoCloseable {

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
		var builder = RpcServer.builder().port(port).serializer(serializer);
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
