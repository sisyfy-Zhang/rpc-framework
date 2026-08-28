package com.feynman.rpc.consumer;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.client.ClientHandler;
import com.feynman.rpc.core.client.DefaultFuture;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.mediator.BeanMethod;
import com.feynman.rpc.core.mediator.MethodMediator;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.RpcRequest;
import com.feynman.rpc.core.protocol.RpcResponse;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.server.ServerHandler;
import com.feynman.rpc.serialize.fastjson2.Fastjson2Serializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 在真实 TCP socket 上验证整条协议栈（RpcEncoder/RpcMessageDecoder -> MethodMediator ->
 * ServerHandler -> ClientHandler -> DefaultFuture），刻意绕开 TCPClient/ChannelManager/Registry——
 * 这个测试不依赖 ZooKeeper，所以不受本机外部 ZK 连接不稳定的影响，保持快速、确定性可重复。
 */
class DirectSocketRpcTest {

	public interface Echo {
		String echo(String s);
	}

	public static class EchoImpl implements Echo {
		@Override
		public String echo(String s) {
			return "echo:" + s;
		}
	}

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private EventLoopGroup clientGroup;
	private Channel serverChannel;
	private Channel clientChannel;

	@AfterEach
	void tearDown() {
		if (clientChannel != null) clientChannel.close();
		if (serverChannel != null) serverChannel.close();
		if (bossGroup != null) bossGroup.shutdownGracefully();
		if (workerGroup != null) workerGroup.shutdownGracefully();
		if (clientGroup != null) clientGroup.shutdownGracefully();
	}

	@Test
	void requestIsDispatchedAndResponseRoundTrips() throws Exception {
		String command = Echo.class.getName() + ".echo";
		Method method = Echo.class.getMethod("echo", String.class);
		BeanMethod beanMethod = new BeanMethod();
		beanMethod.setBean(new EchoImpl());
		beanMethod.setMethod(method);
		MethodMediator.registerMethod(command, beanMethod);

		bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup();
		ServerBootstrap serverBootstrap = new ServerBootstrap();
		serverBootstrap.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						wirePipeline(ch);
						ch.pipeline().addLast(new ServerHandler());
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
						wirePipeline(ch);
						ch.pipeline().addLast(new ClientHandler());
					}
				});
		ChannelFuture connectFuture = clientBootstrap.connect("localhost", port).sync();
		clientChannel = connectFuture.channel();

		Fastjson2Serializer serializer = new Fastjson2Serializer();
		byte[] argBytes = serializer.serialize("world");
		byte[] payload = serializer.serialize(new RpcRequest(command, argBytes));

		long requestId = DefaultFuture.nextRequestId();
		DefaultFuture future = new DefaultFuture(requestId, 5, TimeUnit.SECONDS);
		RpcMessage request = new RpcMessage(requestId, MessageType.REQUEST, serializer.code(), Status.OK, payload);

		clientChannel.writeAndFlush(request);

		RpcMessage response = future.future().get(5, TimeUnit.SECONDS);
		assertNotNull(response, "no response received within timeout");
		assertEquals(MessageType.RESPONSE, response.getMessageType());
		assertEquals(Status.OK, response.getStatus());

		RpcResponse body = serializer.deserialize(response.getPayload(), RpcResponse.class);
		String result = serializer.deserialize(body.getResult(), String.class);
		assertEquals("echo:world", result);
	}

	@Test
	void unknownCommandReturnsNotFound() throws Exception {
		bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup();
		ServerBootstrap serverBootstrap = new ServerBootstrap();
		serverBootstrap.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						wirePipeline(ch);
						ch.pipeline().addLast(new ServerHandler());
					}
				});
		serverChannel = serverBootstrap.bind(0).sync().channel();
		int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

		clientGroup = new NioEventLoopGroup();
		Bootstrap clientBootstrap = new Bootstrap();
		clientBootstrap.group(clientGroup)
				.channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						wirePipeline(ch);
						ch.pipeline().addLast(new ClientHandler());
					}
				});
		clientChannel = clientBootstrap.connect("localhost", port).sync().channel();

		Fastjson2Serializer serializer = new Fastjson2Serializer();
		byte[] payload = serializer.serialize(new RpcRequest("no.such.Command", new byte[0]));

		long requestId = DefaultFuture.nextRequestId();
		DefaultFuture future = new DefaultFuture(requestId, 5, TimeUnit.SECONDS);
		clientChannel.writeAndFlush(new RpcMessage(requestId, MessageType.REQUEST, serializer.code(), Status.OK, payload));

		RpcMessage response = future.future().get(5, TimeUnit.SECONDS);
		assertNotNull(response);
		assertEquals(Status.NOT_FOUND, response.getStatus());
	}

	private static void wirePipeline(SocketChannel ch) {
		ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH,
				ProtocolConstants.LENGTH_FIELD_OFFSET, ProtocolConstants.LENGTH_FIELD_LENGTH, 0, 0));
		ch.pipeline().addLast(new RpcMessageDecoder());
		ch.pipeline().addLast(new RpcEncoder());
	}
}
