package com.feynman.rpc.core.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcSerializationException;
import com.feynman.rpc.core.codec.AttachmentsCodec;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcFrameDecoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.internal.FlushConsolidation;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.OutboundBody;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * 一个客户端实例的传输层：IO 线程组、Bootstrap、连接池、在途请求表。为什么它们是实例字段而不是进程级静态状态：
 * 静态的连接表与在途表会让类一加载就连注册中心、起线程，且永远关不掉，同一 JVM 也起不了第二个配置不同的客户端；
 * 现在它们随 RpcClient 创建、随 {@link #close()} 全部释放。
 *
 * <p>只认识地址和协议帧，不认识服务、Filter、Cluster——那些在 RpcClient 里组装。
 * 框架内部类型，不是给业务直接使用的 API。
 */
public final class NettyClient implements AutoCloseable {

	/** 建连超时：远短于 Netty 默认的 30s，宕掉的节点应该尽快让位给 Cluster 的重试。 */
	private static final int CONNECT_TIMEOUT_MS = 3000;

	private final EventLoopGroup ioGroup;
	private final PendingRequests pending = new PendingRequests();
	private final ConnectionManager connections;
	private final Serializers serializers;

	/**
	 * @param loadStats 所属客户端的每地址在途统计：下线节点的连接据此判断何时排空（见 ConnectionManager）
	 */
	public NettyClient(Serializers serializers, int connectionsPerNode, LoadStats loadStats) {
		this(serializers, connectionsPerNode, loadStats, 0, null, true);
	}

	/** @param ioThreads IO 事件循环线程数，0 = Netty 缺省（2 × 可用核数），见 {@code RpcClientConfig.ioThreads} */
	public NettyClient(Serializers serializers, int connectionsPerNode, LoadStats loadStats, int ioThreads) {
		this(serializers, connectionsPerNode, loadStats, ioThreads, null, true);
	}

	/**
	 * @param ioThreads          IO 事件循环线程数，0 = Netty 缺省（2 × 可用核数），见 {@code RpcClientConfig.ioThreads}
	 * @param flushConsolidation 每条连接的 pipeline 首位是否加 FlushConsolidationHandler（{@link FlushConsolidation}），
	 *                           见 {@code RpcClientConfig.flushConsolidation}
	 */
	public NettyClient(Serializers serializers, int connectionsPerNode, LoadStats loadStats, int ioThreads, boolean flushConsolidation) {
		this(serializers, connectionsPerNode, loadStats, ioThreads, null, flushConsolidation);
	}

	/** @param allocator 非 null 时作为连接的 {@link ChannelOption#ALLOCATOR}（测试用它记录分配发生在哪些线程上） */
	NettyClient(Serializers serializers, int connectionsPerNode, LoadStats loadStats, ByteBufAllocator allocator) {
		this(serializers, connectionsPerNode, loadStats, 0, allocator, true);
	}

	private NettyClient(Serializers serializers, int connectionsPerNode, LoadStats loadStats, int ioThreads, ByteBufAllocator allocator,
			boolean flushConsolidation) {
		this.serializers = serializers;
		byte[] supportedCodes = serializers.codesByPriority();

		this.ioGroup = new NioEventLoopGroup(ioThreads, new DefaultThreadFactory("rpc-client-io"));
		Bootstrap bootstrap = new Bootstrap()
				.group(ioGroup)
				.channel(NioSocketChannel.class)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);
		if (allocator != null) {
			bootstrap.option(ChannelOption.ALLOCATOR, allocator);
		}
		bootstrap.handler(new ChannelInitializer<SocketChannel>() {
			@Override
			protected void initChannel(SocketChannel ch) {
				if (flushConsolidation) {
					// 最靠近 transport：读循环里的 flush 推迟到 channelReadComplete，事件循环外来的 flush 合成一个 flush 任务
					FlushConsolidation.addFirst(ch.pipeline());
				}
				ch.pipeline().addLast(new RpcFrameDecoder());
				ch.pipeline().addLast(new RpcMessageDecoder());
				// 40s 没收到任何东西（服务端每 20s 会主动 ping 一次）就认为连接可能已经死了，关闭它
				// 交给 ConnectionPool 的重连逻辑去处理；20s 没写过东西就主动发一次心跳。
				ch.pipeline().addLast(new IdleStateHandler(40, 20, 0, TimeUnit.SECONDS));
				ch.pipeline().addLast(new RpcEncoder());
				ch.pipeline().addLast(new ClientHandler(pending, supportedCodes));
			}
		});
		this.connections = new ConnectionManager(bootstrap, connectionsPerNode, loadStats);
	}

	/** 让连接池与"当前需要的提供者集合"一致：新地址建池，不再需要的地址排空后关闭。 */
	public void updateConnections(Collection<ServiceInstance> instances) {
		Set<String> addresses = new HashSet<>();
		for (ServiceInstance instance : instances) {
			connections.ensurePool(instance.address(), instance.host(), instance.port());
			addresses.add(instance.address());
		}
		connections.retainOnly(addresses);
	}

	/**
	 * 向 {@code address} 发送一次请求。
	 *
	 * <p>请求一开始就登记进在途表，{@code timeoutNanos}（本次调用 deadline 的剩余时间）是它唯一的定时器，
	 * 覆盖等连接、写出、等响应的全过程。等连接期间、或在事件循环上排队等写出期间已超时的请求不会再被写出。
	 *
	 * <p><b>调用方线程不碰池化分配器</b>：{@code writer} 在调用方线程上只做序列化，帧的缓冲区由编码器在事件循环上分配
	 * （见 {@link OutboundBody}）。这里也因此没有"已分配、还没交给 Netty"的缓冲区——中途任何异常（包括 Error）都不会泄漏，
	 * 并且都让本次请求立即失败，而不是被 CompletableFuture 回调吞掉、挂到超时。
	 *
	 * <p>编码后整帧超过 {@link ProtocolConstants#MAX_FRAME_LENGTH} 的请求不写出，以 {@link RpcSerializationException}
	 * 失败：这是确定性失败（换节点同样发不出去），RpcClient 把它放进结果通道、不重试；若交给编码器去拒绝，
	 * 它会表现为写失败，被 Cluster 当作传输失败换节点重试。帧长按备好的字节数在调用方线程上算，与编码器同一判定。
	 *
	 * @param writer 拿到连接后按该连接协商出的序列化器备好 body；抛出的异常以 {@link RpcSerializationException} 让本次请求失败
	 * @return 收到响应帧时完成（调用方负责 release 该消息）；传输失败或超时时异常完成
	 */
	public CompletableFuture<RpcMessage> send(String address, RequestWriter writer, Map<String, String> attachments,
			long timeoutNanos) {
		long requestId = pending.nextRequestId();
		CompletableFuture<RpcMessage> response = pending.register(requestId, timeoutNanos, TimeUnit.NANOSECONDS);
		connections.acquire(address).whenComplete((channel, error) -> {
			if (error != null) {
				pending.fail(requestId, error);
			} else if (pending.isWaiting(requestId)) {
				try {
					write(channel, requestId, response, writer, attachments);
				} catch (Throwable t) {
					// 这个回调里抛出的异常会被 CompletableFuture 吞掉：必须在这里让请求失败，否则它只能挂到超时
					pending.fail(requestId, new RpcException("Failed to send request to " + channel.remoteAddress() + ": " + t, t));
				}
			}
		});
		return response;
	}

	private void write(Channel channel, long requestId, CompletableFuture<RpcMessage> response, RequestWriter writer,
			Map<String, String> attachments) {
		// 优先用这条连接已经协商好的序列化方式；协商还没完成（连接刚建立）就用本端首选实现——
		// 服务端总是按请求头里的 serializer 解码并用同一种方式回复，所以这个兜底是安全的。
		Byte negotiatedCode = channel.attr(NegotiatedSerializer.KEY).get();
		Serializer serializer = negotiatedCode != null ? serializers.find(negotiatedCode) : null;
		if (serializer == null) {
			serializer = serializers.preferred();
		}
		OutboundBody body;
		try {
			body = writer.write(serializer);
		} catch (Throwable e) {
			// 包括 Error（如对象图过深的 StackOverflowError）：序列化失败是确定性的，进结果通道、不重试
			pending.fail(requestId, e instanceof RpcException ? e
					: new RpcSerializationException("Failed to serialize request with " + serializer.name() + ": " + e, e));
			return;
		}
		RpcException tooLarge = checkFrameLength(attachments, body.length(), channel);
		if (tooLarge != null) {
			pending.fail(requestId, tooLarge);
			return;
		}
		RpcMessage message = RpcMessage.request(requestId, serializer.code(), attachments, body);
		// 记到这条连接上：连接断开时立即让它失败（ClientHandler.channelInactive）；请求以任何方式结束时移除
		Set<Long> onChannel = ChannelRequests.of(channel);
		onChannel.add(requestId);
		response.whenComplete((r, e) -> onChannel.remove(requestId));
		EventLoop loop = channel.eventLoop();
		if (loop.inEventLoop()) {
			writeOnEventLoop(channel, requestId, message, onChannel);
			return;
		}
		try {
			loop.execute(() -> writeOnEventLoop(channel, requestId, message, onChannel));
		} catch (RejectedExecutionException e) {
			// 事件循环已关闭（客户端正在关闭）
			onChannel.remove(requestId);
			pending.fail(requestId, new RpcException("Connection to " + channel.remoteAddress() + " closed before the request was sent", e));
		}
	}

	/**
	 * 在连接的事件循环上写出：编码器（同一线程）分配帧缓冲区。排队期间已超时 / 已失败的请求不再写出——
	 * 超时的调用方早已放弃，再编码、再占发送缓冲区只会在过载时把内存越堆越高。
	 */
	private void writeOnEventLoop(Channel channel, long requestId, RpcMessage message, Set<Long> onChannel) {
		if (!pending.isWaiting(requestId)) {
			onChannel.remove(requestId);
			return;
		}
		if (!channel.isActive()) {
			// 拿到连接与写出之间连接已断：channelInactive 可能已经跑过，不会再替我们失败这个请求
			onChannel.remove(requestId);
			pending.fail(requestId, new RpcException("Connection to " + channel.remoteAddress() + " closed before the request was sent"));
			return;
		}
		channel.writeAndFlush(message).addListener(f -> {
			if (!f.isSuccess()) {
				// 写失败（含编码失败）立即让这次请求失败，而不是挂到超时
				pending.fail(requestId, new RpcException("Failed to send request to " + channel.remoteAddress(), f.cause()));
			}
		});
	}

	/** 整帧超限（或 attachments 本身不合法）时返回让本次请求失败的异常，否则 null。阈值与编码器相同。 */
	private static RpcException checkFrameLength(Map<String, String> attachments, int bodyLength, Channel channel) {
		int attachmentsLength;
		try {
			attachmentsLength = AttachmentsCodec.encodedLength(attachments, ProtocolConstants.DEFAULT_MAX_ATTACHMENTS_LENGTH);
		} catch (IllegalArgumentException e) {
			// RpcClient 发送前已校验过 attachments，这里只防绕过它的调用方
			return new RpcException("Invalid request attachments: " + e.getMessage(), e);
		}
		if (!ProtocolConstants.exceedsMaxFrame(attachmentsLength, bodyLength)) {
			return null;
		}
		long frame = (long) ProtocolConstants.HEADER_LENGTH + attachmentsLength + bodyLength;
		return new RpcSerializationException("Request frame too large: " + frame + " bytes (body " + bodyLength
				+ ", attachments " + attachmentsLength + ") exceeds MAX_FRAME_LENGTH " + ProtocolConstants.MAX_FRAME_LENGTH
				+ "; not sent to " + channel.remoteAddress());
	}

	/**
	 * 当前线程是否是本客户端的某个 IO 事件循环线程。同步代理据此拒绝在事件循环上阻塞等待（见 ReferenceProxy）。
	 * 先看线程类型：事件循环线程都是 {@link FastThreadLocalThread}（{@link DefaultThreadFactory} 创建），业务 / 虚拟线程一次
	 * instanceof 判断就返回；是的话再逐个问本组的事件循环（别的 Netty 组——例如同进程服务端的 worker——不算）。
	 */
	public boolean inEventLoop() {
		if (!(Thread.currentThread() instanceof FastThreadLocalThread)) {
			return false;
		}
		for (EventExecutor executor : ioGroup) {
			if (executor.inEventLoop()) {
				return true;
			}
		}
		return false;
	}

	/** 当前在途（已发出未收到响应）的请求数，用于泄漏断言。 */
	public int pendingRequests() {
		return pending.size();
	}

	/** 当前持有连接池的地址集合（不含排空中的下线节点），用于观测推空保护等行为。 */
	public Set<String> connectedAddresses() {
		return connections.addresses();
	}

	/** 到某地址当前已激活的连接数（含排空中的）。 */
	public int activeConnections(String address) {
		List<Channel> channels = connections.activeChannels(address);
		return channels.size();
	}

	/** 到某地址当前已激活的连接（含排空中的）；观测与测试用（例如检查 pipeline 配置）。 */
	public List<Channel> activeChannels(String address) {
		return connections.activeChannels(address);
	}

	/** 全部地址已激活的连接总数（含排空中的）。 */
	public int activeConnections() {
		return connections.activeConnectionCount();
	}

	/** 让所有尚未收到响应的请求以 {@code cause} 失败。 */
	public void failPending(Throwable cause) {
		pending.failAll(cause);
	}

	/** 关闭全部连接（含排空中的），不再重连。 */
	public void closeConnections() {
		connections.closeAll();
	}

	/** 关闭 IO 线程组。连接已主动关闭，没有需要"安静期"等待的任务，quietPeriod 取 0 让线程尽快退出。 */
	public void shutdownIo() {
		ioGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
	}

	/** 依次 {@link #failPending}、{@link #closeConnections}、{@link #shutdownIo}。 */
	@Override
	public void close() {
		failPending(new RpcException("RpcClient closed"));
		closeConnections();
		shutdownIo();
	}
}
