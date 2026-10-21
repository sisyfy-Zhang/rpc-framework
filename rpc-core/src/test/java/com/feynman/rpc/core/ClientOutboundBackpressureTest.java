package com.feynman.rpc.core;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.client.NettyClient;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端一侧的出站背压：
 * <ul>
 *   <li>{@code connectionsPerNode > 1} 时取连接优先选可写的那条（不可写用 Netty 的用户自定义可写位模拟），并计数；</li>
 *   <li>连接不可写且待写字节超过 {@code outboundHardLimit} 时请求不写出，以"未发出"的 RpcException 失败（failfast 下直接给调用方），
 *       failover 且 {@code retries > 0} 时换节点成功——对端是一个接受连接但从不读的"黑洞"，积压用一段未冲刷的写构造。</li>
 * </ul>
 */
class ClientOutboundBackpressureTest {

	public interface Echo {
		String echo(String s);
	}

	@Test
	void prefersAWritableConnectionOfTheSameNode() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).build();
		try {
			server.export(Echo.class, s -> s);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(2)
					.registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				Echo echo = client.reference(Echo.class);
				assertEquals("warm", echo.echo("warm"));
				ServiceInstance provider = client.providers(ServiceKey.of(Echo.class)).get(0);
				NettyClient transport = field(client, "transport", NettyClient.class);
				await().atMost(10, TimeUnit.SECONDS).until(() -> transport.activeChannels(provider.address()).size() == 2);
				List<Channel> channels = transport.activeChannels(provider.address());
				Channel blocked = channels.get(0);
				RequestCounter blockedRequests = new RequestCounter();
				RequestCounter otherRequests = new RequestCounter();
				blocked.pipeline().addLast(blockedRequests);
				channels.get(1).pipeline().addLast(otherRequests);

				blocked.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
				await().atMost(5, TimeUnit.SECONDS).until(() -> !blocked.isWritable());
				for (int i = 0; i < 20; i++) {
					assertEquals("x" + i, echo.echo("x" + i));
				}
				assertEquals(0, blockedRequests.requests.get(), "no request goes to the unwritable connection");
				assertEquals(20, otherRequests.requests.get());
				assertTrue(client.unwritableConnectionSwitches() >= 1, "switches " + client.unwritableConnectionSwitches());
				assertEquals(0, client.sendBufferRejections(), "unwritable but below the hard limit: nothing is rejected");

				blocked.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
				await().atMost(5, TimeUnit.SECONDS).until(blocked::isWritable);
				for (int i = 0; i < 20; i++) {
					assertEquals("y" + i, echo.echo("y" + i));
				}
				assertTrue(blockedRequests.requests.get() > 0, "writable again: back in the rotation");
			} finally {
				client.close();
			}
		} finally {
			server.close();
		}
	}

	@Test
	void pastTheHardLimitARequestIsNotSentAndFailsOverToAnotherNode() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		ServiceKey key = ServiceKey.of(Echo.class);
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).build();
		Registry fakeRegistration = new InMemoryRegistryFactory().create(InMemoryRegistryFactory.config(ns));
		try (BlackHole blackHole = new BlackHole()) {
			server.export(Echo.class, s -> s);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			ServiceInstance hole = new ServiceInstance(key, "127.0.0.1", blackHole.port(), Map.of());
			fakeRegistration.register(hole);
			RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).connectionsPerNode(1).cluster("failover")
					.loadBalance("roundrobin").writeBufferWaterMark(1024, 2048).outboundHardLimit(4096)
					.registry(InMemoryRegistryFactory.config(ns)).build();
			try {
				Echo failover = client.reference(Echo.class, ReferenceOptions.defaults().retries(1).timeout(Duration.ofSeconds(5)));
				Echo once = client.reference(Echo.class, ReferenceOptions.defaults().retries(0).timeout(Duration.ofSeconds(5)));
				await().atMost(10, TimeUnit.SECONDS).until(() -> client.providers(key).size() == 2
						&& client.activeConnections(hole.address()) == 1 && client.activeConnections() == 2);
				Channel toHole = field(client, "transport", NettyClient.class).activeChannels(hole.address()).get(0);
				// 积压：一段未冲刷的 8KiB 写（对端是黑洞，这些字节将来被冲刷出去也无害），连接因此不可写且超过硬上限 4KiB
				toHole.eventLoop().submit(() -> toHole.write(Unpooled.wrappedBuffer(new byte[8192]))).sync();
				assertFalse(toHole.isWritable());

				for (int i = 0; i < 10; i++) {
					assertEquals("f" + i, failover.echo("f" + i), "failover moves the not-sent request to the healthy node");
				}
				long rejectedByFailover = client.sendBufferRejections();
				assertTrue(rejectedByFailover >= 1, "round robin sent some attempts to the backed-up node first");

				AtomicInteger notSent = new AtomicInteger();
				for (int i = 0; i < 6; i++) {
					try {
						assertEquals("o" + i, once.echo("o" + i));
					} catch (RpcException e) {
						assertSame(RpcException.class, e.getClass(), "a plain transport failure, not a deadline / serialization error");
						assertTrue(e.getMessage().contains("request not sent"), e.getMessage());
						notSent.incrementAndGet();
					}
				}
				assertTrue(notSent.get() >= 1, "without retries the caller sees the not-sent failure");
				assertEquals(rejectedByFailover + notSent.get(), client.sendBufferRejections());
				assertEquals(0, client.pendingRequests(), "rejected requests leave no pending entry behind");
			} finally {
				client.close();
			}
		} finally {
			fakeRegistration.close();
			server.close();
		}
	}

	/** 数经过这条连接写出的 REQUEST（放在 pipeline 尾部：调用方的写从这里进入）。 */
	static final class RequestCounter extends ChannelOutboundHandlerAdapter {
		final AtomicInteger requests = new AtomicInteger();

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			if (msg instanceof RpcMessage m && m.messageType() == MessageType.REQUEST) {
				requests.incrementAndGet();
			}
			ctx.write(msg, promise);
		}
	}

	/** 接受连接但从不读的对端。 */
	static final class BlackHole implements AutoCloseable {
		private final ServerSocket socket;
		private final List<Socket> accepted = new CopyOnWriteArrayList<>();
		private final Thread acceptor;

		BlackHole() throws IOException {
			socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			acceptor = new Thread(() -> {
				while (!socket.isClosed()) {
					try {
						accepted.add(socket.accept());
					} catch (IOException e) {
						return;
					}
				}
			}, "test-blackhole-acceptor");
			acceptor.setDaemon(true);
			acceptor.start();
		}

		int port() {
			return socket.getLocalPort();
		}

		@Override
		public void close() throws Exception {
			socket.close();
			for (Socket s : accepted) {
				s.close();
			}
			acceptor.join(2000);
		}
	}

	private static <T> T field(Object owner, String name, Class<T> type) throws ReflectiveOperationException {
		Field f = owner.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return type.cast(f.get(owner));
	}
}
