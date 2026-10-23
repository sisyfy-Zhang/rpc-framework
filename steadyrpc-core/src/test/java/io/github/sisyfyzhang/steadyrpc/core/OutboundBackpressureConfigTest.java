package io.github.sisyfyzhang.steadyrpc.core;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.client.NettyClient;
import io.github.sisyfyzhang.steadyrpc.core.config.RpcClientConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.RpcServerConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.server.NettyServer;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import io.netty.channel.Channel;
import io.netty.channel.WriteBufferWaterMark;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站背压的配置项：{@code writeBufferWaterMark}（两端，缺省 32KiB / 64KiB = Netty 缺省）、{@code outboundHardLimit}（两端，
 * 缺省 high × 16，0 = 关闭）、{@code RpcServerConfig.outboundBackpressure}（缺省开）。校验规则，以及配置经 RpcServer / RpcClient
 * 落到真实连接上：两端连接的 {@code WRITE_BUFFER_WATER_MARK}，服务端 pipeline 里有没有背压 handler。
 */
class OutboundBackpressureConfigTest {

	public interface Echo {
		String echo(String s);
	}

	/** server 包里 OutboundBackpressure.HANDLER_NAME 的值（该类包内可见）。 */
	private static final String BACKPRESSURE_HANDLER = "outboundBackpressure";
	private static final RegistryConfig REGISTRY = RegistryConfig.of("memory", "unused");

	@Test
	void defaultsAreNettysWaterMarksAndSixteenTimesHighAsTheHardLimit() {
		RpcClientConfig client = RpcClientConfig.builder().registry(REGISTRY).build();
		RpcServerConfig server = RpcServerConfig.builder().build();
		assertEquals(WriteBufferWaterMark.DEFAULT.low(), client.writeBufferLow());
		assertEquals(WriteBufferWaterMark.DEFAULT.high(), client.writeBufferHigh());
		assertEquals(WriteBufferWaterMark.DEFAULT.low(), server.writeBufferLow());
		assertEquals(WriteBufferWaterMark.DEFAULT.high(), server.writeBufferHigh());
		assertEquals(16L * 64 * 1024, client.outboundHardLimit());
		assertEquals(16L * 64 * 1024, server.outboundHardLimit());
		assertTrue(server.outboundBackpressure());
	}

	@Test
	void theDefaultHardLimitFollowsAConfiguredHighWaterMark() {
		assertEquals(16L * 1024 * 1024, RpcServerConfig.builder().writeBufferWaterMark(256 * 1024, 1024 * 1024).build().outboundHardLimit());
		assertEquals(16L * 1024 * 1024,
				RpcClientConfig.builder().registry(REGISTRY).writeBufferWaterMark(256 * 1024, 1024 * 1024).build().outboundHardLimit());
		RpcServerConfig explicit = RpcServerConfig.builder().writeBufferWaterMark(1024, 2048).outboundHardLimit(4096)
				.outboundBackpressure(false).build();
		assertEquals(1024, explicit.writeBufferLow());
		assertEquals(2048, explicit.writeBufferHigh());
		assertEquals(4096, explicit.outboundHardLimit());
		assertFalse(explicit.outboundBackpressure());
		assertEquals(0, RpcClientConfig.builder().registry(REGISTRY).outboundHardLimit(0).build().outboundHardLimit(), "0 = off");
	}

	@Test
	void invalidValuesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().writeBufferWaterMark(2048, 1024).build());
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().writeBufferWaterMark(-1, 1024).build());
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().writeBufferWaterMark(0, 0).build());
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().outboundHardLimit(-1));
		assertThrows(IllegalArgumentException.class, () -> RpcServerConfig.builder().outboundHardLimit(1024).build(),
				"a hard limit below the high water mark makes no sense");
		assertThrows(IllegalArgumentException.class,
				() -> RpcClientConfig.builder().registry(REGISTRY).writeBufferWaterMark(2048, 1024).build());
		assertThrows(IllegalArgumentException.class, () -> RpcClientConfig.builder().registry(REGISTRY).outboundHardLimit(-5));
		assertThrows(IllegalArgumentException.class,
				() -> RpcClientConfig.builder().registry(REGISTRY).writeBufferWaterMark(1024, 8192).outboundHardLimit(4096).build());
	}

	@Test
	void waterMarksAndTheBackpressureHandlerReachTheConnections() throws Exception {
		assertConnections(true, new WriteBufferWaterMark(16 * 1024, 48 * 1024), new WriteBufferWaterMark(8 * 1024, 24 * 1024));
	}

	@Test
	void backpressureOffLeavesTheHandlerOutButKeepsTheWaterMarks() throws Exception {
		assertConnections(false, new WriteBufferWaterMark(16 * 1024, 48 * 1024), new WriteBufferWaterMark(8 * 1024, 24 * 1024));
	}

	private static void assertConnections(boolean backpressure, WriteBufferWaterMark serverMark, WriteBufferWaterMark clientMark)
			throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).outboundBackpressure(backpressure)
				.writeBufferWaterMark(serverMark.low(), serverMark.high()).build();
		try {
			server.export(Echo.class, s -> s);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(2)
					.writeBufferWaterMark(clientMark.low(), clientMark.high()).registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				Echo echo = client.reference(Echo.class);
				for (int i = 0; i < 10; i++) {
					assertEquals("x" + i, echo.echo("x" + i));
				}
				await().atMost(10, TimeUnit.SECONDS).until(() -> client.activeConnections() == 2 && server.activeConnections() == 2);
				List<Channel> accepted = field(server, "transport", NettyServer.class).acceptedChannels();
				ServiceInstance provider = client.providers(ServiceKey.of(Echo.class)).get(0);
				List<Channel> connected = field(client, "transport", NettyClient.class).activeChannels(provider.address());
				for (Channel ch : accepted) {
					assertWaterMark(serverMark, ch.config().getWriteBufferWaterMark(), "server");
					assertEquals(backpressure, ch.pipeline().names().contains(BACKPRESSURE_HANDLER), "server pipeline: " + ch.pipeline().names());
				}
				for (Channel ch : connected) {
					assertWaterMark(clientMark, ch.config().getWriteBufferWaterMark(), "client");
					assertFalse(ch.pipeline().names().contains(BACKPRESSURE_HANDLER), "the client does not pause reading");
				}
				assertEquals(0, server.autoReadPauses());
				assertEquals(0, server.outboundOverloadRejections());
				assertEquals(0, client.sendBufferRejections());
			} finally {
				client.close();
			}
		} finally {
			server.close();
		}
	}

	private static void assertWaterMark(WriteBufferWaterMark expected, WriteBufferWaterMark actual, String side) {
		assertEquals(expected.low(), actual.low(), side + " low");
		assertEquals(expected.high(), actual.high(), side + " high");
	}

	private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return type.cast(f.get(owner));
	}
}
