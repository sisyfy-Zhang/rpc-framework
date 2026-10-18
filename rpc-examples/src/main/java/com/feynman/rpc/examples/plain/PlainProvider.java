package com.feynman.rpc.examples.plain;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.examples.api.InventoryService;
import com.feynman.rpc.examples.provider.InventoryServiceImpl;

/**
 * 不用 Spring 的服务端：builder → export → start，进程退出时 close。README 的"纯 Java 快速开始"即本文件。
 *
 * <p>运行：先起 ZooKeeper（{@code EmbeddedZooKeeper} 或 docker），再运行本类的 main；参数为注册中心地址与端口，
 * 缺省 {@code localhost:2181} 与 {@code 9100}。
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

	public static void main(String[] args) throws Exception {
		String zookeeper = args.length > 0 ? args[0] : "localhost:2181";
		int port = args.length > 1 ? Integer.parseInt(args[1]) : 9100;
		RpcServer server = start(zookeeper, null, port);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close, "plain-provider-shutdown")); // Ctrl+C / SIGTERM → 优雅下线
		server.registered().get(30, TimeUnit.SECONDS);    // 需要"已可被发现"时再等（例如 readiness）
		System.out.println("PlainProvider serving " + server.exportedServices() + " on port " + server.port());
	}
}
