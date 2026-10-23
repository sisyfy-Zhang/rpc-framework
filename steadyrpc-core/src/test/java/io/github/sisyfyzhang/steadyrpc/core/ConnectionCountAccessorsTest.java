package io.github.sisyfyzhang.steadyrpc.core;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读的连接总数访问器（{@link RpcClient#activeConnections()}、{@link RpcServer#activeConnections()}）：指标模块的
 * 连接数 Gauge 读的就是它们。未启动为 0；建连后两端读数一致，客户端总数等于逐地址读数之和；close 之后回到 0。
 */
class ConnectionCountAccessorsTest {

	public interface Echo {
		String echo(String s);
	}

	@Test
	void totalsTrackOpenConnectionsOnBothSidesAndDropToZeroAfterClose() {
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).build();
		try {
			assertEquals(0, server.activeConnections(), "not started: no transport yet");
			server.export(Echo.class, s -> s);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			assertEquals(0, server.activeConnections());

			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(2)
					.registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				Echo echo = client.reference(Echo.class);
				for (int i = 0; i < 8; i++) {
					assertEquals("x" + i, echo.echo("x" + i));
				}
				ServiceInstance provider = client.providers(ServiceKey.of(Echo.class)).get(0);
				assertTrue(client.activeConnections() >= 1);
				assertEquals(client.activeConnections(provider.address()), client.activeConnections(),
						"one provider: the total equals the per-address count");
				await().atMost(5, TimeUnit.SECONDS).until(() -> server.activeConnections() == client.activeConnections());
			} finally {
				client.close();
			}
			assertEquals(0, client.activeConnections(), "closed client holds no connections");
			await().atMost(5, TimeUnit.SECONDS).until(() -> server.activeConnections() == 0);
		} finally {
			server.close();
		}
		assertEquals(0, server.activeConnections(), "closed server holds no connections");
	}
}
