package io.github.sisyfyzhang.steadyrpc.core;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.RpcClientConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.RpcServerConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code RpcClientConfig.ioThreads} / {@code RpcServerConfig.ioThreads}：配置真的决定了事件循环线程数。
 *
 * <p>Netty 的事件循环线程在第一次有任务（注册 channel）时才启动，连接按轮询分到各个事件循环上；所以让连接数多于配置的线程数，
 * 数一数本次新出现的 {@code rpc-client-io-*} / {@code rpc-server-worker-*} 线程：恰好等于配置值（多一个都说明配置没生效，
 * 缺省是 2 × 可用核数）。
 */
class IoThreadsConfigTest {

	public interface Echo {
		String echo(String s);
	}

	@Test
	void defaultsAreZeroMeaningNettyDefault() {
		RegistryConfig registry = InMemoryRegistryFactory.config("ns-" + UUID.randomUUID());
		assertEquals(0, RpcClientConfig.builder().registry(registry).build().ioThreads());
		assertEquals(0, RpcServerConfig.builder().build().ioThreads());
		assertEquals(RpcClientConfig.DEFAULT_IO_THREADS, RpcServerConfig.DEFAULT_IO_THREADS);
	}

	@Test
	void outOfRangeValuesAreRejected() {
		RegistryConfig registry = InMemoryRegistryFactory.config("ns-" + UUID.randomUUID());
		assertThrows(IllegalArgumentException.class, () -> RpcClientConfig.builder().registry(registry).ioThreads(-1).build());
		assertThrows(IllegalArgumentException.class,
				() -> RpcClientConfig.builder().registry(registry).ioThreads(RpcClientConfig.MAX_IO_THREADS + 1).build());
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().ioThreads(-1).build());
		assertEquals(RpcClientConfig.MAX_IO_THREADS,
				RpcServerConfig.builder().ioThreads(RpcClientConfig.MAX_IO_THREADS).build().ioThreads());
	}

	@Test
	void configuredCountsBoundTheEventLoopThreadsOnBothSides() {
		assertThreadCounts(2, 3, 6);
	}

	@Test
	void singleEventLoopCarriesAllConnections() {
		assertThreadCounts(1, 1, 4);
	}

	private static void assertThreadCounts(int clientIoThreads, int serverIoThreads, int connections) {
		Set<String> before = threadNames();
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).ioThreads(serverIoThreads).build();
		try {
			server.export(Echo.class, s -> s);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			assertEquals(serverIoThreads, server.config().ioThreads());
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(connections)
					.ioThreads(clientIoThreads).registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				assertEquals(clientIoThreads, client.config().ioThreads());
				Echo echo = client.reference(Echo.class);
				for (int i = 0; i < 2 * connections; i++) {
					assertEquals("x" + i, echo.echo("x" + i));
				}
				await().atMost(10, TimeUnit.SECONDS)
						.until(() -> client.activeConnections() == connections && server.activeConnections() == connections);
				Set<String> created = threadNames();
				created.removeAll(before);
				assertEquals(clientIoThreads, count(created, "rpc-client-io-"),
						"client event-loop threads (" + connections + " connections): " + created);
				assertEquals(serverIoThreads, count(created, "rpc-server-worker-"),
						"server event-loop threads (" + connections + " connections): " + created);
			} finally {
				client.close();
			}
		} finally {
			server.close();
		}
	}

	private static long count(Set<String> names, String prefix) {
		return names.stream().filter(n -> n.startsWith(prefix)).count();
	}

	private static Set<String> threadNames() {
		return Thread.getAllStackTraces().keySet().stream().map(Thread::getName).collect(Collectors.toSet());
	}
}
