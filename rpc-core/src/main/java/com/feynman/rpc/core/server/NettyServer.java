package com.feynman.rpc.core.server;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcFrameDecoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.internal.InFlightGate;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * 一个服务端实例的传输层：boss / worker IO 线程组、业务执行器、监听端口、全部已接受的连接、在途闸门。线程名分别以
 * {@code rpc-server-boss-} / {@code rpc-server-worker-} / {@code rpc-server-biz-} 开头，
 * 线程快照里一眼能看出是谁的线程、关闭后是否有残留。框架内部类型。
 *
 * <p>停机分三步，由 RpcServer 编排（中间夹着它自己的步骤）：{@link #startDraining()} 关闸（新请求回
 * SERVER_OVERLOADED "server shutting down"，心跳照常）→ {@link #awaitDrained(Duration)} 等在途归零 →
 * {@link #close(boolean)} 关监听端口与全部连接、关 IO 线程组、关业务执行器。
 */
public final class NettyServer implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(NettyServer.class);
	/** 排空超时后，被中断的业务线程最多再等多久退出；不能让一个吞掉中断的业务方法拖住 close。 */
	private static final long FORCED_TERMINATION_WAIT_MS = 1000;

	private final EventLoopGroup bossGroup;
	private final EventLoopGroup workerGroup;
	private final BusinessExecutor bizExecutor;
	private final ServiceDispatcher dispatcher;
	private final byte fallbackSerializerCode;
	private final InFlightGate gate = new InFlightGate();
	/**
	 * 全部已接受的连接。关闭监听 channel 不会关闭已接受的子连接；停机时要显式关掉它们，客户端才能立刻感知
	 * （它们在途的请求随连接关闭立即失败，而不是挂到超时）。
	 */
	private final ChannelGroup connections = new DefaultChannelGroup("rpc-server-connections", GlobalEventExecutor.INSTANCE);
	private volatile Channel serverChannel;

	public NettyServer(ServiceDispatcher dispatcher, ExecutorConfig executorConfig, byte fallbackSerializerCode) {
		this.dispatcher = dispatcher;
		this.fallbackSerializerCode = fallbackSerializerCode;
		this.bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("rpc-server-boss"));
		this.workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("rpc-server-worker"));
		this.bizExecutor = new BusinessExecutor(executorConfig);
	}

	/** 同步绑定端口；失败时抛出，调用方负责 {@link #close(boolean)}。 */
	public InetSocketAddress bind(int port) {
		ServerBootstrap bootstrap = new ServerBootstrap()
				.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, 128)
				.childOption(ChannelOption.SO_KEEPALIVE, true)
				.childHandler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						connections.add(ch); // 关闭的连接会被 ChannelGroup 自动移除
						ch.pipeline().addLast(new RpcFrameDecoder());
						ch.pipeline().addLast(new RpcMessageDecoder());
						ch.pipeline().addLast(new IdleStateHandler(30, 20, 0, TimeUnit.SECONDS));
						ch.pipeline().addLast(new RpcEncoder());
						ch.pipeline().addLast(new ServerHandler(dispatcher, bizExecutor, gate, fallbackSerializerCode));
					}
				});
		try {
			Channel channel = bootstrap.bind(port).sync().channel();
			this.serverChannel = channel;
			return (InetSocketAddress) channel.localAddress();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RpcException("Interrupted while binding port " + port, e);
		} catch (Exception e) {
			throw new RpcException("Failed to bind port " + port, e);
		}
	}

	/** 关闸：之后到达的请求回 SERVER_OVERLOADED（"server shutting down"），已在途的不受影响。 */
	public void startDraining() {
		gate.close();
	}

	/**
	 * 等在途请求（含排队中的、以及业务返回了未完成 future 的）全部回包。
	 *
	 * @return 是否在 {@code timeout} 内排空
	 */
	public boolean awaitDrained(Duration timeout) {
		try {
			return gate.awaitIdle(timeout.toNanos(), TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return gate.inFlight() == 0;
		}
	}

	/** 已进入（排队或执行中）、尚未回包的请求数。 */
	public int inFlight() {
		return gate.inFlight();
	}

	/** 正在业务线程上执行的任务数（不含排队、不含已返回 future 等待完成的请求）。 */
	public int runningBusinessTasks() {
		return bizExecutor.running();
	}

	/** 当前已接受且未关闭的客户端连接数（关闭的连接由 ChannelGroup 自动移除）。 */
	public int activeConnections() {
		return connections.size();
	}

	/** 排队中的任务数。 */
	public int queuedBusinessTasks() {
		return bizExecutor.queued();
	}

	@Override
	public void close() {
		close(true);
	}

	/**
	 * 关监听端口与全部连接 → 关 IO 线程组 → 关业务执行器。
	 *
	 * @param drained 在途是否已排空。没排空（等待超时）时中断仍在执行的业务线程、丢弃排队中的请求，
	 *                不再等它们自然结束——停机超时之后继续等就失去了"超时"的意义
	 */
	public void close(boolean drained) {
		gate.close();
		Channel channel = serverChannel;
		if (channel != null) {
			channel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
		}
		connections.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
		bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
		workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
		if (drained) {
			bizExecutor.shutdown();
		} else {
			abandon(bizExecutor.shutdownNow());
		}
		bossGroup.terminationFuture().awaitUninterruptibly(5, TimeUnit.SECONDS);
		workerGroup.terminationFuture().awaitUninterruptibly(5, TimeUnit.SECONDS);
		try {
			if (!bizExecutor.awaitTermination(drained ? 5000 : FORCED_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)) {
				abandon(bizExecutor.shutdownNow());
				if (!bizExecutor.awaitTermination(FORCED_TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)) {
					log.warn("Business threads did not stop after interruption; a business method is ignoring interrupts");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			abandon(bizExecutor.shutdownNow());
		}
	}

	/** 排队中、不会再执行的请求：释放它们持有的缓冲区、归还在途计数。 */
	private static void abandon(List<Runnable> neverRun) {
		for (Runnable task : neverRun) {
			if (task instanceof ServerHandler.DispatchTask dispatch) {
				dispatch.abandon();
			}
		}
	}
}
