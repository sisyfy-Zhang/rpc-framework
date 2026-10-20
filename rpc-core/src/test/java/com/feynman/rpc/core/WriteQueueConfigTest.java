package com.feynman.rpc.core;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.client.NettyClient;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.config.RpcClientConfig;
import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.internal.ConnectionWriteQueue;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.server.NettyServer;
import com.feynman.rpc.core.server.ServerHandler;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RpcClientConfig.writeQueue} / {@code RpcServerConfig.writeQueue}：缺省开；配置经 RpcClient / RpcServer 传到每条连接
 * （客户端连接上的写队列属性、服务端 ServerHandler 的写队列）；两端各自独立，四种组合下并发调用都正常回显。
 */
class WriteQueueConfigTest {

	public interface Echo {
		String echo(String s);
	}

	public interface AsyncEcho {
		CompletableFuture<String> echo(String s);
	}

	@Test
	void defaultsAreOnAndBuildersCanTurnThemOff() {
		RegistryConfig registry = InMemoryRegistryFactory.config("ns-" + UUID.randomUUID());
		assertTrue(RpcClientConfig.DEFAULT_WRITE_QUEUE);
		assertTrue(RpcServerConfig.DEFAULT_WRITE_QUEUE);
		assertTrue(RpcClientConfig.builder().registry(registry).build().writeQueue());
		assertTrue(RpcServerConfig.builder().build().writeQueue());
		assertFalse(RpcClientConfig.builder().registry(registry).writeQueue(false).build().writeQueue());
		assertFalse(RpcServerConfig.builder().writeQueue(false).build().writeQueue());
	}

	@Test
	void bothOn() throws Exception {
		assertWiring(true, true, ExecutorConfig.defaults());
	}

	@Test
	void bothOff() throws Exception {
		assertWiring(false, false, ExecutorConfig.defaults());
	}

	@Test
	void eachSideIsIndependent() throws Exception {
		assertWiring(true, false, ExecutorConfig.defaults());
		assertWiring(false, true, ExecutorConfig.defaults());
	}

	@Test
	void directAndVirtualExecutorsWorkWithTheQueue() throws Exception {
		assertWiring(true, true, ExecutorConfig.direct());
		assertWiring(true, true, ExecutorConfig.virtualThreads());
	}

	private static void assertWiring(boolean clientOn, boolean serverOn, ExecutorConfig executor) throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).executor(executor).writeQueue(serverOn).build();
		try {
			server.export(Echo.class, s -> s);
			server.export(AsyncEcho.class, s -> CompletableFuture.supplyAsync(() -> s));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(2)
					.writeQueue(clientOn).registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				Echo echo = client.reference(Echo.class);
				AsyncEcho asyncEcho = client.reference(AsyncEcho.class);
				List<CompletableFuture<String>> calls = new ArrayList<>();
				for (int i = 0; i < 200; i++) {
					String v = "x" + i;
					calls.add(i % 2 == 0 ? CompletableFuture.supplyAsync(() -> echo.echo(v)) : asyncEcho.echo(v));
				}
				for (int i = 0; i < calls.size(); i++) {
					assertEquals("x" + i, calls.get(i).get(10, TimeUnit.SECONDS));
				}
				await().atMost(10, TimeUnit.SECONDS).until(() -> client.activeConnections() == 2 && server.activeConnections() == 2);
				NettyServer serverTransport = field(server, "transport", NettyServer.class);
				NettyClient clientTransport = field(client, "transport", NettyClient.class);
				ServiceInstance provider = client.providers(ServiceKey.of(Echo.class)).get(0);
				AttributeKey<ConnectionWriteQueue> key = AttributeKey.valueOf("rpc-client-write-queue");
				for (Channel ch : clientTransport.activeChannels(provider.address())) {
					if (clientOn) {
						assertNotNull(ch.attr(key).get(), "client connection has a write queue");
					} else {
						assertNull(ch.attr(key).get(), "writeQueue(false) on the client leaves it out");
					}
				}
				for (Channel ch : serverTransport.acceptedChannels()) {
					ServerHandler handler = ch.pipeline().get(ServerHandler.class);
					Object queue = field(handler, "writeQueue", Object.class);
					if (serverOn) {
						assertNotNull(queue, "server connection has a write queue");
					} else {
						assertNull(queue, "writeQueue(false) on the server leaves it out");
					}
				}
				assertEquals(0, serverTransport.replyWriteFailures());
			} finally {
				client.close();
			}
		} finally {
			server.close();
		}
	}

	private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return type.cast(f.get(owner));
	}
}
