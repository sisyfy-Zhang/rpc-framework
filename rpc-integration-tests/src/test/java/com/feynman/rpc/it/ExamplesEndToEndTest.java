package com.feynman.rpc.it;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.examples.api.InsufficientStockException;
import com.feynman.rpc.examples.api.InventoryService;
import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.examples.api.UserFacade;
import com.feynman.rpc.examples.api.UserService;
import com.feynman.rpc.examples.consumer.ConsumerApplication;
import com.feynman.rpc.examples.consumer.OrderGateway;
import com.feynman.rpc.examples.consumer.UserGateway;
import com.feynman.rpc.examples.provider.ProviderApplication;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 rpc-examples 里的两个 Boot 应用，经进程内 TestingServer 跑通一次完整的注解驱动调用（不依赖本机已起的 ZooKeeper
 * 或手工启动的 provider）。接入文档里的代码就是这两个应用的代码（见 DocsSnippetsTest）。
 * 注册中心地址、端口以命令行参数传入（优先级高于 provider.yml / consumer.yml），不经 System 属性。
 */
class ExamplesEndToEndTest {

	@Test
	void providerAndConsumerExamplesWorkAgainstEmbeddedZookeeper() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			String registryArg = "--rpc.registry.address=" + zk.connectString();

			// run() 必须正常返回：旧实现的服务端在 refresh 里阻塞，Boot 应用永远停在启动阶段
			ConfigurableApplicationContext provider = assertTimeoutPreemptively(Duration.ofSeconds(30),
					() -> ProviderApplication.builder().run(registryArg, "--rpc.server.port=0", "--rpc.server.host=127.0.0.1"));
			try {
				RpcServer server = provider.getBean(RpcServer.class);
				assertTrue(server.isRunning());
				// SmartLifecycle 不等注册（注册中心故障不该拖住启动）；需要"已可被发现"时等 registered()
				server.registered().orTimeout(10, TimeUnit.SECONDS).join();
				assertEquals(List.of(ServiceKey.of(InventoryService.class), ServiceKey.of(UserFacade.class), ServiceKey.of(UserService.class)),
						server.exportedServices().stream().sorted((a, b) -> a.toString().compareTo(b.toString())).toList());
				assertEquals(List.of("127.0.0.1:" + server.port()), zk.providers(ServiceKey.of(UserFacade.class)));

				// consumer 的 DemoRunner（ApplicationRunner）在 run() 返回前就发起了一次远程调用，失败会让启动失败
				try (ConfigurableApplicationContext consumer = assertTimeoutPreemptively(Duration.ofSeconds(30),
						() -> ConsumerApplication.builder().run(registryArg, "--examples.consumer.demo-call=true"))) {
					User saved = consumer.getBean(UserGateway.class).register(7, "alice");
					assertEquals(7, saved.getId());
					assertEquals("alice", saved.getName());
					// 纯消费端应用不绑定端口
					assertFalse(consumer.getBean(RpcServer.class).isRunning());

					// 协议 v2：多参数（long + Integer + String）、泛型返回、业务异常同类型还原
					OrderGateway orders = consumer.getBean(OrderGateway.class);
					assertEquals(7, orders.placeOrder(1001L, 3, "order-1"));
					assertEquals(2, orders.placeOrder(1001L, 5, "order-2"));
					assertEquals(-1, orders.placeOrder(1001L, 5, "order-3"), "insufficient stock is caught by type");
					InsufficientStockException e = assertThrows(InsufficientStockException.class,
							() -> orders.inventory().reserve(1001L, 9, "order-4"));
					assertEquals("sku 1001 has 2 left, order order-4 wants 9", e.getMessage());
					List<User> buyers = orders.buyers(1001L);
					assertEquals(List.of("order-1", "order-2"), buyers.stream().map(User::getName).toList());
					assertEquals(List.of("order-1", "order-2"),
							orders.inventory().buyersAsync(1001L).get(5, TimeUnit.SECONDS).stream().map(User::getName).toList());
				}
			} finally {
				provider.close();
			}

			await().atMost(5, TimeUnit.SECONDS).until(() -> zk.providers(ServiceKey.of(UserFacade.class)).isEmpty()
					&& zk.providers(ServiceKey.of(UserService.class)).isEmpty()
					&& zk.providers(ServiceKey.of(InventoryService.class)).isEmpty());
			await().atMost(5, TimeUnit.SECONDS).until(() -> ZkFixture.rpcThreads().isEmpty());
		}
	}
}
