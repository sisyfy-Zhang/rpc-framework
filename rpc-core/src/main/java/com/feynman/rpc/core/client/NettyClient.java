package com.feynman.rpc.core.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcSerializationException;
import com.feynman.rpc.core.codec.AttachmentsCodec;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcFrameDecoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

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
		this.serializers = serializers;
		byte[] supportedCodes = serializers.codesByPriority();

		this.ioGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("rpc-client-io"));
		Bootstrap bootstrap = new Bootstrap()
				.group(ioGroup)
				.channel(NioSocketChannel.class)
				.option(ChannelOption.SO_KEEPALIVE, true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
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
	 * 覆盖等连接、写出、等响应的全过程。等连接期间已超时的请求不会再被写出。
	 *
	 * <p>编码后整帧超过 {@link ProtocolConstants#MAX_FRAME_LENGTH} 的请求不写出，以 {@link RpcSerializationException}
	 * 失败：这是确定性失败（换节点同样发不出去），RpcClient 把它放进结果通道、不重试；若交给编码器去拒绝，
	 * 它会表现为写失败，被 Cluster 当作传输失败换节点重试。
	 *
	 * @param writer 拿到连接后按该连接协商出的序列化器编码 body；抛出的异常以 {@link RpcSerializationException} 让本次请求失败
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
				write(channel, requestId, response, writer, attachments);
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
		ByteBuf body;
		try {
			body = writer.write(channel.alloc(), serializer);
		} catch (RuntimeException e) {
			pending.fail(requestId, e instanceof RpcException ? e
					: new RpcSerializationException("Failed to serialize request with " + serializer.name() + ": " + e, e));
			return;
		}
		RpcException tooLarge = checkFrameLength(attachments, body.readableBytes(), channel);
		if (tooLarge != null) {
			body.release();
			pending.fail(requestId, tooLarge);
			return;
		}
		RpcMessage message = RpcMessage.request(requestId, serializer.code(), attachments, body);
		// 记到这条连接上：连接断开时立即让它失败（ClientHandler.channelInactive）；请求以任何方式结束时移除
		Set<Long> onChannel = ChannelRequests.of(channel);
		onChannel.add(requestId);
		response.whenComplete((r, e) -> onChannel.remove(requestId));
		if (!channel.isActive()) {
			// 拿到连接与写出之间连接已断：channelInactive 可能已经跑过，不会再替我们失败这个请求
			onChannel.remove(requestId);
			message.release();
			pending.fail(requestId, new RpcException("Connection to " + channel.remoteAddress() + " closed before the request was sent"));
			return;
		}
		channel.writeAndFlush(message).addListener(f -> {
			if (!f.isSuccess()) {
				// 写失败立即让这次请求失败，而不是挂到超时
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
