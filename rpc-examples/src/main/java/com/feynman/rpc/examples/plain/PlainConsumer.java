package com.feynman.rpc.examples.plain;

import java.time.Duration;
import java.util.List;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.examples.api.InsufficientStockException;
import com.feynman.rpc.examples.api.InventoryService;
import com.feynman.rpc.examples.api.User;

/**
 * 不用 Spring 的消费端：builder → reference → 像本地接口一样调用 → close。README 的"纯 Java 快速开始"即本文件。
 * 参数为注册中心地址，缺省 {@code localhost:2181}。
 */
public final class PlainConsumer {

	private PlainConsumer() {
	}

	public static RpcClient connect(String zookeeper) {
		return RpcClient.builder()
				.registry(RegistryConfig.zookeeper(zookeeper))
				.serializer("protobuf")
				.loadBalance("p2c")
				.build();
	}

	/** 对 {@link InventoryService} 发起一组示例调用，返回每一步的结果描述。 */
	public static List<String> demo(RpcClient client) {
		InventoryService inventory = client.reference(InventoryService.class,
				ReferenceOptions.defaults().timeout(Duration.ofSeconds(3)));
		int left = inventory.reserve(2001L, 4, "plain-1");          // 多参数：long + Integer + String
		List<User> buyers = inventory.buyers(2001L);                // 泛型返回：元素就是 User
		String rejected;
		try {
			inventory.reserve(2001L, 100, "plain-2");
			rejected = "unexpected success";
		} catch (InsufficientStockException e) {                      // 业务异常按类型还原
			rejected = "InsufficientStockException: " + e.getMessage();
		}
		return List.of("left=" + left, "buyers=" + buyers, rejected);
	}

	public static void main(String[] args) {
		String zookeeper = args.length > 0 ? args[0] : "localhost:2181";
		try (RpcClient client = connect(zookeeper)) {
			demo(client).forEach(System.out::println);
		}
	}
}
