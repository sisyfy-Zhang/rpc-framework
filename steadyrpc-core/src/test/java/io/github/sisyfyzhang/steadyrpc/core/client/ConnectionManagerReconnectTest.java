package io.github.sisyfyzhang.steadyrpc.core.client;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证连接池的核心承诺：连接意外断开后会自动被连接池替换（带上限的退避重连），
 * 而不是那个节点就此失联，非要等某个无关的注册中心事件才会重建所有连接；
 * 以及 {@link ConnectionManager#acquire} 会等待正在建立的连接，而不是交出一条还不能写的 channel。
 */
class ConnectionManagerReconnectTest {

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private EventLoopGroup clientGroup;
	private Channel serverChannel;
	private ConnectionManager manager;

	@AfterEach
	void tearDown() {
		if (manager != null) manager.closeAll();
		if (serverChannel != null) serverChannel.close();
		if (bossGroup != null) bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		if (workerGroup != null) workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		if (clientGroup != null) clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
	}

	@Test
	void poolReconnectsAfterConnectionDrops() throws Exception {
		int port = startServer();
		manager = newManager();
		String address = "localhost:" + port;
		manager.ensurePool(address, "localhost", port);

		// acquire() 在连接建立完成前调用也必须拿到一条已激活的连接
		Channel original = manager.acquire(address).get(5, TimeUnit.SECONDS);
		assertTrue(original.isActive());

		// 模拟意外断连（网络抖动、对端重启等）
		original.close().sync();

		// 两个 slot 都重新处于激活状态，且被关掉的那条已被替换
		await().atMost(5, TimeUnit.SECONDS).until(() -> {
			var active = manager.activeChannels(address);
			return active.size() == 2 && !active.contains(original);
		});
		assertNotSame(original, manager.acquire(address).get(5, TimeUnit.SECONDS));
	}

	@Test
	void retainOnlyClosesPoolsThatAreNoLongerNeeded() throws Exception {
		int port = startServer();
		manager = newManager();
		String address = "localhost:" + port;
		manager.ensurePool(address, "localhost", port);
		Channel channel = manager.acquire(address).get(5, TimeUnit.SECONDS);

		manager.retainOnly(Set.of());

		assertTrue(manager.activeChannels(address).isEmpty());
		assertTrue(manager.acquire(address).isCompletedExceptionally());
		await().atMost(5, TimeUnit.SECONDS).until(() -> !channel.isOpen());
	}

	private ConnectionManager newManager() {
		clientGroup = new NioEventLoopGroup(1);
		Bootstrap clientBootstrap = new Bootstrap();
		clientBootstrap.group(clientGroup)
				.channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
					}
				});
		return new ConnectionManager(clientBootstrap, 2);
	}

	private int startServer() throws InterruptedException {
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup(1);
		ServerBootstrap serverBootstrap = new ServerBootstrap();
		serverBootstrap.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
					}
				});
		serverChannel = serverBootstrap.bind(0).sync().channel();
		return ((InetSocketAddress) serverChannel.localAddress()).getPort();
	}
}
