package com.feynman.rpc.consumer;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.api.annotation.RemoteInvoke;
import com.feynman.rpc.core.client.ChannelManager;
import com.feynman.rpc.core.client.TCPClient;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.mediator.BeanMethod;
import com.feynman.rpc.core.mediator.MethodMediator;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.proxy.InvokeProxy;
import com.feynman.rpc.core.server.ServerHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实的 {@link InvokeProxy} + {@link TCPClient} 链路验证异步优先派发——刻意绕开
 * Registry/ZooKeeper（手动把 {@link ChannelManager} 接到本地起的一个测试服务端），
 * 和 {@code DirectSocketRpcTest} 一样保持快速、确定性可重复。
 */
class AsyncInvocationTest {

	public interface AsyncEcho {
		CompletableFuture<String> echo(String s);
	}

	public static class AsyncEchoImpl implements AsyncEcho {
		@Override
		public CompletableFuture<String> echo(String s) {
			return CompletableFuture.supplyAsync(() -> {
				try {
					Thread.sleep(1200); // 模拟耗时的服务端处理，时长远超一次性的 Netty/JIT 预热噪声
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "echo:" + s;
			});
		}
	}

	public static class ConsumerBean {
		@RemoteInvoke
		private AsyncEcho echo;
	}

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;

	@AfterEach
	void tearDown() {
		if (serverChannel != null) serverChannel.close();
		if (bossGroup != null) bossGroup.shutdownGracefully();
		if (workerGroup != null) workerGroup.shutdownGracefully();
		ChannelManager.clearAll();
	}

	@Test
	void asyncMethodReturnsWithoutBlockingCallerThread() throws Exception {
		String command = AsyncEcho.class.getName() + ".echo";
		Method method = AsyncEchoImpl.class.getMethod("echo", String.class);
		BeanMethod beanMethod = new BeanMethod();
		beanMethod.setBean(new AsyncEchoImpl());
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
						ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH,
								ProtocolConstants.LENGTH_FIELD_OFFSET, ProtocolConstants.LENGTH_FIELD_LENGTH, 0, 0));
						ch.pipeline().addLast(new RpcMessageDecoder());
						ch.pipeline().addLast(new RpcEncoder());
						ch.pipeline().addLast(new ServerHandler());
					}
				});
		ChannelFuture bindFuture = serverBootstrap.bind(0).sync();
		serverChannel = bindFuture.channel();
		int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

		// 直接把 ChannelManager 接到本地起的测试服务端，绕开 Registry/ZK
		String address = "localhost:" + port;
		ChannelManager.ensurePool(address, TCPClient.b, "localhost", port);
		ChannelManager.reconcile(java.util.Map.of(address, 1));

		ConsumerBean consumerBean = new ConsumerBean();
		new InvokeProxy().postProcessBeforeInitialization(consumerBean, "consumerBean");

		AtomicInteger callerThreadBlockedMillis = new AtomicInteger();
		long start = System.nanoTime();
		CompletableFuture<String> future = consumerBean.echo.echo("world");
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
		callerThreadBlockedMillis.set((int) elapsedMillis);

		// 调用本身必须在服务端模拟的 1200ms 延迟结束前就返回
		// （留了足够余量应对首次网络调用的一次性 Netty/JIT 类加载开销）
		assertTrue(elapsedMillis < 600, "caller thread was blocked for " + elapsedMillis + "ms, expected async return");

		String result = future.get(5, TimeUnit.SECONDS);
		assertEquals("echo:world", result);
	}
}
