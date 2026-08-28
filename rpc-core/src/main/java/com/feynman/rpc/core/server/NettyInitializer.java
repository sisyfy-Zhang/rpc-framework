package com.feynman.rpc.core.server;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.common.Constants;
import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.registry.Registry;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * 服务端 bean。通过 {@link com.feynman.rpc.core.config.RpcProviderConfiguration} 显式注册，
 * 而不是用 {@code @Component} + 包扫描——避免消费端的组件扫描误捡到这个类、意外绑定一份自己的
 * Netty 服务端。
 *
 * <p>绑定端口和向注册中心注册被刻意拆成两个独立步骤，各自处理失败：注册中心的短暂问题
 * （ZK/Nacos 抖动）只按退避策略重试并记日志，绝不会因此把已经绑定好、健康的监听端口拆掉——
 * 两者耦合在同一个 try/finally 里会导致注册失败悄悄拖垮整个进程，即便端口本身完全正常，
 * 所以刻意让"端口绑定成功"和"注册中心注册成功"各自独立失败、独立恢复。
 */
public class NettyInitializer implements ApplicationListener<ContextRefreshedEvent>{

	private static final Logger log = LoggerFactory.getLogger(NettyInitializer.class);
	private static final AtomicBoolean STARTED = new AtomicBoolean(false);
	private static final int MAX_REGISTER_ATTEMPTS = 5;
	private static final long INITIAL_REGISTER_BACKOFF_MS = 500;
	private static final long MAX_REGISTER_BACKOFF_MS = 10_000;


	public void start() {

		// 1) 从 JVM 参数读取（STS Run Config -> VM arguments）
        final int port = Integer.parseInt(System.getProperty("rpc.port", "9000"));
        final int weight = Integer.parseInt(System.getProperty("rpc.weight", "1"));

		EventLoopGroup bossGroup = new NioEventLoopGroup();
		EventLoopGroup workerGroup = new NioEventLoopGroup();

		// 业务线程组（线程数可调）
	    // 一般配置：CPU核数 * 2 作为起点；你也可以先写固定值 8/16
	    EventExecutorGroup bizGroup = new DefaultEventExecutorGroup(
	            Runtime.getRuntime().availableProcessors() * 2
	    );

		ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(bossGroup, workerGroup);
		bootstrap.option(ChannelOption.SO_BACKLOG, 128)
		         .childOption(ChannelOption.SO_KEEPALIVE, true)
		         .channel(NioServerSocketChannel.class)
		         .childHandler(new ChannelInitializer<SocketChannel>() {

						@Override
						protected void initChannel(SocketChannel ch) throws Exception {
							ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH,
									ProtocolConstants.LENGTH_FIELD_OFFSET, ProtocolConstants.LENGTH_FIELD_LENGTH, 0, 0));
							ch.pipeline().addLast(new RpcMessageDecoder());
							ch.pipeline().addLast(new IdleStateHandler(30, 20, 10, TimeUnit.SECONDS));
							ch.pipeline().addLast(new RpcEncoder());
							ch.pipeline().addLast(bizGroup, new ServerHandler());
						}

			         });

		// 1) 绑定端口——只有这一步失败才需要立刻释放资源，注册中心的问题不该连累它
		Channel channel;
		try {
			channel = bootstrap.bind(port).sync().channel();
			log.info("BIND OK port={}, weight={}", port, weight);
		} catch (Exception e) {
			log.error("NettyInitializer BIND FAIL. port={}, weight={}", port, weight, e);
			bizGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
			return;
		}

		// 2) 注册到 registry——失败只重试+记录日志，端口继续对外提供服务
		String path = buildRegisterPath(port, weight);
		Registry registry = RpcConfig.resolve(ExtensionLoader.getExtensionLoader(Registry.class), RpcConfig.REGISTRY_KEY);
		registerWithRetry(registry, path);

		// 3) 优雅停机：JVM 退出前主动摘除注册节点，不依赖 ZK/Nacos 会话超时才被动摘除
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				log.info("Shutting down: unregistering {}", path);
				registry.unregister(path);
			} catch (Exception e) {
				log.error("Failed to unregister {} during shutdown", path, e);
			}
			channel.close();
		}, "rpc-provider-shutdown"));

		try {
			channel.closeFuture().sync();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			bizGroup.shutdownGracefully();
			bossGroup.shutdownGracefully();
			workerGroup.shutdownGracefully();
		}
	}

	private String buildRegisterPath(int port, int weight) {
		try {
			InetAddress netAddress = InetAddress.getLocalHost();
			String base = Constants.SERVER_PATH;
			if (base.endsWith("/")) {
				base = base.substring(0, base.length() - 1);
			}
			String node = netAddress.getHostAddress() + ":" + port + "#" + weight;
			return base + "/" + node;
		} catch (Exception e) {
			throw new IllegalStateException("Failed to resolve local address for registration", e);
		}
	}

	private void registerWithRetry(Registry registry, String path) {
		long backoffMs = INITIAL_REGISTER_BACKOFF_MS;
		for (int attempt = 1; attempt <= MAX_REGISTER_ATTEMPTS; attempt++) {
			try {
				registry.register(path);
				log.info("REGISTRY REGISTER OK path={}", path);
				return;
			} catch (Exception e) {
				if (attempt == MAX_REGISTER_ATTEMPTS) {
					log.error("REGISTRY REGISTER giving up after {} attempts, path={}"
							+ " — the port is still bound and serving, but won't be discoverable until this is fixed",
							attempt, path, e);
					return;
				}
				log.warn("REGISTRY REGISTER attempt {} failed (retrying in {}ms): {}", attempt, backoffMs, e.getMessage());
				try {
					Thread.sleep(backoffMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				backoffMs = Math.min(backoffMs * 2, MAX_REGISTER_BACKOFF_MS);
			}
		}
	}


	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		// 防止多次触发（父子容器/重复 refresh）
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        this.start();
	}
}
