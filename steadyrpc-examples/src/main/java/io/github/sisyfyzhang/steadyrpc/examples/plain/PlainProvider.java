package io.github.sisyfyzhang.steadyrpc.examples.plain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService;
import io.github.sisyfyzhang.steadyrpc.examples.provider.InventoryServiceImpl;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.MicrometerServerFilter;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.PrometheusEndpoint;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.RpcServerMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 不用 Spring 的服务端：builder → export → start，进程退出时 close。README 的"纯 Java 快速开始"即本文件。
 *
 * <p>运行：先起 ZooKeeper（{@code EmbeddedZooKeeper} 或 docker），再运行本类的 main；参数为注册中心地址与端口，
 * 缺省 {@code localhost:2181} 与 {@code 9100}。可选 {@code --metrics-port <p>}：记录调用指标并在该端口暴露
 * Prometheus 抓取端点 {@code /metrics}（docker/ 里的 Prometheus 预置抓取 9466）。
 */
public final class PlainProvider {

	private PlainProvider() {
	}

	public static RpcServer start(String zookeeper, String host, int port) {
		RpcServer server = RpcServer.builder()
				.port(port)
				.host(host)                                    // 注册给消费端的地址；null 表示自动探测本机地址
				.registry(RegistryConfig.zookeeper(zookeeper))
				.serializer("protobuf")
				.shutdownTimeout(Duration.ofSeconds(10))       // 停机时等在途请求的上限
				.build();
		server.export(InventoryService.class, new InventoryServiceImpl());
		server.start();                                    // 绑定端口后立即返回，注册在后台进行
		return server;
	}

	/** 同 {@link #start}，另把调用指标记进 {@code registry}：服务端 Filter 记 Timer，在途 / 连接数绑定成 Gauge。 */
	public static RpcServer startWithMetrics(String zookeeper, String host, int port, MeterRegistry registry) {
		RpcServer server = RpcServer.builder()
				.port(port)
				.host(host)
				.registry(RegistryConfig.zookeeper(zookeeper))
				.serializer("protobuf")
				.shutdownTimeout(Duration.ofSeconds(10))
				.serverFilter(new MicrometerServerFilter(registry))   // 每次调用记 Timer rpc.server.calls
				.build();
		RpcServerMetrics.bind(server, registry);                   // Gauge rpc.server.inflight / rpc.server.connections
		server.export(InventoryService.class, new InventoryServiceImpl());
		server.start();
		return server;
	}

	public static void main(String[] args) throws Exception {
		List<String> rest = new ArrayList<>(List.of(args));
		Integer metricsPort = removeOption(rest, "--metrics-port");
		if (metricsPort != null) {
			mainWithMetrics(rest, metricsPort);
			return;
		}
		String zookeeper = zookeeper(rest);
		int port = port(rest);
		RpcServer server = start(zookeeper, null, port);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "plain-provider-shutdown")); // Ctrl+C / SIGTERM → 优雅下线
		server.registered().get(30, TimeUnit.SECONDS);    // 需要"已可被发现"时再等（例如 readiness）
		System.out.println("PlainProvider serving " + server.exportedServices() + " on port " + server.port());
	}

	private static void mainWithMetrics(List<String> rest, int metricsPort) throws Exception {
		MeterRegistry registry = PrometheusEndpoint.newRegistry();      // 需要 micrometer-registry-prometheus
		RpcServer server = startWithMetrics(zookeeper(rest), null, port(rest), registry);
		PrometheusEndpoint endpoint = new PrometheusEndpoint(metricsPort, registry);   // http://<本机>:<metricsPort>/metrics
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.close();
			endpoint.close();
		}, "plain-provider-shutdown"));
		server.registered().get(30, TimeUnit.SECONDS);
		System.out.println("PlainProvider serving " + server.exportedServices() + " on port " + server.port()
				+ ", metrics on port " + endpoint.port() + PrometheusEndpoint.PATH);
	}

	private static String zookeeper(List<String> positional) {
		return positional.size() > 0 ? positional.get(0) : "localhost:2181";
	}

	private static int port(List<String> positional) {
		return positional.size() > 1 ? Integer.parseInt(positional.get(1)) : 9100;
	}

	/** 取出并移除 {@code --name <int>}；没有时返回 null。 */
	private static Integer removeOption(List<String> args, String name) {
		int i = args.indexOf(name);
		if (i < 0) {
			return null;
		}
		if (i + 1 >= args.size()) {
			throw new IllegalArgumentException(name + " needs a port number");
		}
		int value = Integer.parseInt(args.get(i + 1));
		args.remove(i + 1);
		args.remove(i);
		return value;
	}
}
