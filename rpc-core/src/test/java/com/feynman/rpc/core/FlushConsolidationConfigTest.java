package com.feynman.rpc.core;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.client.NettyClient;
import com.feynman.rpc.core.config.RpcClientConfig;
import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.internal.FlushConsolidation;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.server.NettyServer;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.channel.Channel;
import io.netty.handler.flush.FlushConsolidationHandler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RpcClientConfig.flushConsolidation} / {@code RpcServerConfig.flushConsolidation}：缺省开；配置经 RpcClient / RpcServer
 * 一路传到每条连接的 pipeline——开时 {@link FlushConsolidationHandler} 在首位（最靠近 transport），关时 pipeline 里没有它；
 * 两端各自独立（四种组合都能正常回显，同一构建内可以 A/B）。
 */
class FlushConsolidationConfigTest {

	public interface Echo {
		String echo(String s);
	}

	@Test
	void defaultsAreOnAndBuildersCanTurnThemOff() {
		RegistryConfig registry = InMemoryRegistryFactory.config("ns-" + UUID.randomUUID());
		assertTrue(RpcClientConfig.DEFAULT_FLUSH_CONSOLIDATION);
		assertTrue(RpcServerConfig.DEFAULT_FLUSH_CONSOLIDATION);
		assertTrue(RpcClientConfig.builder().registry(registry).build().flushConsolidation());
		assertTrue(RpcServerConfig.builder().build().flushConsolidation());
		assertFalse(RpcClientConfig.builder().registry(registry).flushConsolidation(false).build().flushConsolidation());
		assertFalse(RpcServerConfig.builder().flushConsolidation(false).build().flushConsolidation());
	}

	@Test
	void bothOn() throws Exception {
		assertPipelines(true, true);
	}

	@Test
	void bothOff() throws Exception {
		assertPipelines(false, false);
	}

	@Test
	void eachSideIsIndependent() throws Exception {
		assertPipelines(true, false);
		assertPipelines(false, true);
	}

	private static void assertPipelines(boolean clientOn, boolean serverOn) throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).flushConsolidation(serverOn).build();
		try {
			server.export(Echo.class, s -> s);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(2)
					.flushConsolidation(clientOn).registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				Echo echo = client.reference(Echo.class);
				for (int i = 0; i < 20; i++) {
					assertEquals("x" + i, echo.echo("x" + i));
				}
				await().atMost(10, TimeUnit.SECONDS).until(() -> client.activeConnections() == 2 && server.activeConnections() == 2);
				NettyServer serverTransport = field(server, "transport", NettyServer.class);
				NettyClient clientTransport = field(client, "transport", NettyClient.class);
				List<Channel> accepted = serverTransport.acceptedChannels();
				ServiceInstance provider = client.providers(ServiceKey.of(Echo.class)).get(0);
				List<Channel> connected = clientTransport.activeChannels(provider.address());
				assertEquals(2, accepted.size());
				assertEquals(2, connected.size());
				accepted.forEach(ch -> assertConsolidation(ch, serverOn, "server"));
				connected.forEach(ch -> assertConsolidation(ch, clientOn, "client"));
			} finally {
				client.close();
			}
		} finally {
			server.close();
		}
	}

	private static void assertConsolidation(Channel channel, boolean expected, String side) {
		if (expected) {
			assertInstanceOf(FlushConsolidationHandler.class, channel.pipeline().first(),
					side + ": FlushConsolidationHandler must be the first handler (closest to the transport): " + channel.pipeline().names());
			assertInstanceOf(FlushConsolidationHandler.class, channel.pipeline().get(FlushConsolidation.HANDLER_NAME));
		} else {
			assertNull(channel.pipeline().get(FlushConsolidationHandler.class),
					side + ": flushConsolidation(false) must leave the handler out: " + channel.pipeline().names());
		}
	}

	private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return type.cast(f.get(owner));
	}
}
