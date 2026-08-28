package com.feynman.rpc.core.client;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证连接池的核心承诺：连接意外断开后会自动被连接池替换（带上限的退避重连），
 * 而不是那个节点就此失联，非要等某个无关的注册中心事件才会重建所有连接。
 */
class ChannelManagerReconnectTest {

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private EventLoopGroup clientGroup;
	private Channel serverChannel;

	@AfterEach
	void tearDown() {
		if (serverChannel != null) serverChannel.close();
		if (bossGroup != null) bossGroup.shutdownGracefully();
		if (workerGroup != null) workerGroup.shutdownGracefully();
		if (clientGroup != null) clientGroup.shutdownGracefully();
		ChannelManager.clearAll();
	}

	@Test
	void poolReconnectsAfterConnectionDrops() throws Exception {
		bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup();
		ServerBootstrap serverBootstrap = new ServerBootstrap();
		serverBootstrap.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
					}
				});
		ChannelFuture bindFuture = serverBootstrap.bind(0).sync();
		serverChannel = bindFuture.channel();
		int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

		clientGroup = new NioEventLoopGroup();
		Bootstrap clientBootstrap = new Bootstrap();
		clientBootstrap.group(clientGroup)
				.channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
					}
				});

		String address = "localhost:" + port;
		ChannelManager.ensurePool(address, clientBootstrap, "localhost", port);
		ChannelManager.reconcile(Map.of(address, 1));

		Channel original = waitForActiveChannel(address);
		assertNotNull(original, "pool never produced an active channel");

		// 模拟意外断连（网络抖动、对端重启等）
		original.close().sync();

		Channel replacement = waitForDifferentActiveChannel(address, original);
		assertNotNull(replacement, "pool did not reconnect after the channel closed");
		assertNotSame(original, replacement);
		assertTrue(replacement.isActive());
	}

	private Channel waitForActiveChannel(String address) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
		while (System.currentTimeMillis() < deadline) {
			Channel channel = ChannelManager.getChannel(address);
			if (channel != null && channel.isActive()) {
				return channel;
			}
			Thread.sleep(20);
		}
		return null;
	}

	private Channel waitForDifferentActiveChannel(String address, Channel previous) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
		while (System.currentTimeMillis() < deadline) {
			Channel channel = ChannelManager.getChannel(address);
			if (channel != null && channel.isActive() && channel != previous) {
				return channel;
			}
			Thread.sleep(20);
		}
		return null;
	}
}
