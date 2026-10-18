package com.feynman.rpc.it;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.registry.ServiceInstance;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-1：同一 JVM 起两个 RpcServer（不同端口、各暴露一个不同接口）+ 一个 RpcClient，按接口路由正确；
 * 全部 close 后注册节点消失、名字以 {@code rpc-} 开头的线程在 5 秒内全部结束。
 *
 * <p>旧实现做不到这件事：所有 provider 注册在同一个 /rpc 路径下（客户端会把 InventoryService 的调用发给只暴露了
 * GreetingService 的进程），且连接表、在途表、方法表都是进程级静态状态、线程永不关闭。
 */
class MultiServerRoutingTest {

	public interface GreetingService {
		String greet(String name);
	}

	public interface InventoryService {
		Integer stock(String sku);
	}

	static final class GreetingServiceImpl implements GreetingService {
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public String greet(String name) {
			calls.incrementAndGet();
			return "hello " + name + " from server-A";
		}
	}

	static final class InventoryServiceImpl implements InventoryService {
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public Integer stock(String sku) {
			calls.incrementAndGet();
			return sku.length() * 10;
		}
	}

	@Test
	void twoServersTwoInterfacesOneClient_routesByInterface_andLeavesNothingBehindAfterClose() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			ServiceKey greetingKey = ServiceKey.of(GreetingService.class);
			ServiceKey inventoryKey = ServiceKey.of(InventoryService.class);
			GreetingServiceImpl greetingImpl = new GreetingServiceImpl();
			InventoryServiceImpl inventoryImpl = new InventoryServiceImpl();

			RpcServer serverA = RpcServer.builder()
					.port(0).host("127.0.0.1").registry(zk.registry()).executor(ExecutorConfig.fixed(2)).build();
			RpcServer serverB = RpcServer.builder()
					.port(0).host("127.0.0.1").registry(zk.registry()).executor(ExecutorConfig.fixed(2)).build();
			serverA.export(GreetingService.class, greetingImpl);
			serverB.export(InventoryService.class, inventoryImpl);
			serverA.start();
			serverA.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			serverB.start();
			serverB.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			String addressA = "127.0.0.1:" + serverA.port();
			String addressB = "127.0.0.1:" + serverB.port();
			System.out.println("[A0-1] serverA=" + addressA + " exports " + serverA.exportedServices()
					+ ", serverB=" + addressB + " exports " + serverB.exportedServices());

			// 按接口注册：每个接口目录下只有暴露了它的那个进程
			assertEquals(List.of(addressA), zk.providers(greetingKey));
			assertEquals(List.of(addressB), zk.providers(inventoryKey));
			String metadata = zk.nodeData(greetingKey, addressA);
			System.out.println("[A0-1] " + ZkFixture.providersPath(greetingKey) + "/" + addressA + " -> " + metadata);
			assertTrue(metadata.matches("weight=1&proto=2&ts=\\d+&pid=\\d+"), metadata);

			RpcClient client = RpcClient.builder()
					.registry(zk.registry())
					.defaultTimeout(Duration.ofSeconds(5))
					.build();
			GreetingService greeting = client.reference(GreetingService.class);
			InventoryService inventory = client.reference(InventoryService.class);

			for (int i = 0; i < 5; i++) {
				assertEquals("hello alice from server-A", greeting.greet("alice"));
				assertEquals(50, inventory.stock("sku-1"));
			}
			// 每个实现只收到发给自己接口的调用
			assertEquals(5, greetingImpl.calls.get());
			assertEquals(5, inventoryImpl.calls.get());
			assertEquals(List.of(addressA), client.providers(greetingKey).stream().map(ServiceInstance::address).toList());
			assertEquals(List.of(addressB), client.providers(inventoryKey).stream().map(ServiceInstance::address).toList());
			assertEquals(0, client.pendingRequests());

			Set<String> threadsBeforeClose = ZkFixture.rpcThreads();
			System.out.println("[A0-1] rpc- threads before close (" + threadsBeforeClose.size() + "): " + threadsBeforeClose);
			// 断言非空：确认这个快照真的能看到框架线程，否则"关闭后为空"的断言没有意义
			for (String prefix : List.of("rpc-client-io-", "rpc-server-boss-", "rpc-server-worker-", "rpc-server-biz-", "rpc-server-registry-", "rpc-registry-zk-")) {
				assertTrue(threadsBeforeClose.stream().anyMatch(name -> name.startsWith(prefix)),
						"expected a live thread named " + prefix + "* before close, got " + threadsBeforeClose);
			}

			long closeStart = System.nanoTime();
			serverA.close();
			serverB.close();
			client.close();
			System.out.println("[A0-1] close() of 2 servers + 1 client took " + (System.nanoTime() - closeStart) / 1_000_000 + "ms");

			// 注册节点消失
			await().atMost(5, TimeUnit.SECONDS).until(() -> zk.providers(greetingKey).isEmpty() && zk.providers(inventoryKey).isEmpty());
			// 框架线程全部结束
			long threadsStart = System.nanoTime();
			await().atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50)).until(() -> ZkFixture.rpcThreads().isEmpty());
			System.out.println("[A0-1] rpc- threads after close: " + ZkFixture.rpcThreads() + " (drained "
					+ (System.nanoTime() - threadsStart) / 1_000_000 + "ms after close returned)");
			assertEquals(0, client.pendingRequests());
		}
	}
}
