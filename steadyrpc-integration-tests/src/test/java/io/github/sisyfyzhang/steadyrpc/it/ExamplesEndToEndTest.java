package io.github.sisyfyzhang.steadyrpc.it;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.examples.api.InsufficientStockException;
import io.github.sisyfyzhang.steadyrpc.examples.api.InventoryService;
import io.github.sisyfyzhang.steadyrpc.examples.api.User;
import io.github.sisyfyzhang.steadyrpc.examples.api.UserFacade;
import io.github.sisyfyzhang.steadyrpc.examples.api.UserService;
import io.github.sisyfyzhang.steadyrpc.examples.consumer.CallShapesRunner;
import io.github.sisyfyzhang.steadyrpc.examples.consumer.ConsumerApplication;
import io.github.sisyfyzhang.steadyrpc.examples.consumer.OrderGateway;
import io.github.sisyfyzhang.steadyrpc.examples.consumer.UserGateway;
import io.github.sisyfyzhang.steadyrpc.examples.provider.ProviderApplication;
import io.github.sisyfyzhang.steadyrpc.examples.provider.TenantFilter;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 steadyrpc-examples 里的两个 Boot 应用，经进程内 TestingServer 跑通一次完整的注解驱动调用（不依赖本机已起的 ZooKeeper
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

				// provider.yml 的 Actuator 配置：health 组件 rpc 可按路径读取（show-components）且已接进 readiness 组
				HealthEndpoint health = provider.getBean(HealthEndpoint.class);
				assertEquals(Status.UP, health.healthForPath("rpc").getStatus());
				assertEquals(Status.UP, health.healthForPath("readiness", "rpc").getStatus());

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

					// 接入文档第 7 节的验证清单：CallShapesRunner 启动时已对 SKU 3001 跑过一遍（失败会让启动失败），这里换个 SKU 断言结果
					assertEquals(List.of("left=7",
							"InsufficientStockException: sku 4001 has 7 left, order order-2 wants 100",
							"async buyers=[User{id=1, name=order-1}]",
							"buyers(tenant=acme)=1"),
							consumer.getBean(CallShapesRunner.class).demo(4001L));
					// attachment 往返：consumer 经 RpcContext 设置，provider 的 TenantFilter 读到；只随紧接着的下一次调用发出
					TenantFilter tenants = provider.getBean(TenantFilter.class);
					assertEquals("acme", tenants.lastTenant());
					orders.buyers(4001L);
					assertNull(tenants.lastTenant(), "attachment must not leak into the following call");
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
