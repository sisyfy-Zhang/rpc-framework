package com.feynman.rpc.core.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.feynman.rpc.core.cluster.Cluster;
import com.feynman.rpc.core.cluster.ClusterInvocation;
import com.feynman.rpc.core.codec.RpcEncoder;
import com.feynman.rpc.core.codec.RpcMessageDecoder;
import com.feynman.rpc.core.common.Constants;
import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.ProtocolConstants;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.RpcRequest;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

public class TCPClient {

	private static final Logger log = LoggerFactory.getLogger(TCPClient.class);

	static public final Bootstrap b = new Bootstrap();

	static {
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		b.group(workerGroup)
		 .channel(NioSocketChannel.class)
		 .option(ChannelOption.SO_KEEPALIVE, true)
		 .handler(new ChannelInitializer<SocketChannel>() {

				@Override
				protected void initChannel(SocketChannel ch) throws Exception {
					ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(ProtocolConstants.MAX_FRAME_LENGTH,
							ProtocolConstants.LENGTH_FIELD_OFFSET, ProtocolConstants.LENGTH_FIELD_LENGTH, 0, 0));
					ch.pipeline().addLast(new RpcMessageDecoder());
					// 40s 没收到任何东西（服务端每 20s 会主动 ping 一次）就认为连接可能已经死了，关闭它
					// 交给 ConnectionPool 的重连逻辑去处理；20s 没写过东西就主动发一次心跳。
					ch.pipeline().addLast(new IdleStateHandler(40, 20, 0, TimeUnit.SECONDS));
					ch.pipeline().addLast(new RpcEncoder());
					ch.pipeline().addLast(new ClientHandler());
				}

		     });

		try {
			Registry registry = RpcConfig.resolve(ExtensionLoader.getExtensionLoader(Registry.class), RpcConfig.REGISTRY_KEY);
			// subscribe() 会立即推送一次当前列表，之后每次变化再推送，
			// 一次调用就够了，不需要"先读一次 + 再另外挂 watcher"两步分开做。
			registry.subscribe(Constants.SERVER_PATH, TCPClient::applyServerList);
		} catch (Exception e) {
			log.error("Failed to subscribe to registry at startup", e);
		}
	}

	private static void applyServerList(List<String> serverPathes) {
		Map<String, Integer> addressWeights = new HashMap<>();

		for (String serverPath : serverPathes) {
			String firstServer = serverPath.split("#")[0];
			String host = firstServer.split(":")[0];
			int port = Integer.parseInt(firstServer.split(":")[1]);
			int weight = Integer.parseInt(serverPath.split("#")[1]);

			ChannelManager.ensurePool(firstServer, b, host, port); // 已存在的地址是 no-op，不会重连
			addressWeights.put(firstServer, weight);
		}

		// 只对"确实不在了"的地址摘除连接池，而不是每次注册中心一有风吹草动就全部推倒重连
		ChannelManager.reconcile(addressWeights);
		log.info("registry children size={}, known address size={}", serverPathes.size(), addressWeights.size());
	}

	//发数据：command 形如 "接口全限定名.方法名"，arg 是唯一的方法参数（0 参方法传 null）。
	//异步优先：真正的节点选择+发送在 sendOnce()；这里只负责把它包进 Cluster 的重试/降级策略。
	public static CompletableFuture<RpcMessage> sendAsync(String command, Object arg) {
		return sendAsync(command, arg, 20_000, 0);
	}

	public static CompletableFuture<RpcMessage> sendAsync(String command, Object arg, long timeoutMillis, int maxRetries) {
		Cluster cluster = RpcConfig.resolve(ExtensionLoader.getExtensionLoader(Cluster.class), RpcConfig.CLUSTER_KEY);
		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return ChannelManager.addressWeights();
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				return sendOnce(address, command, arg, timeoutMillis);
			}

			@Override
			public int maxRetries() {
				return maxRetries;
			}

			@Override
			public String describe() {
				return command;
			}
		};
		return cluster.invoke(invocation);
	}

	// 同步调用：只是 sendAsync() 之上阻塞等待的语法糖，不是另一套独立实现。
	public static RpcMessage send(String command, Object arg) {
		return send(command, arg, 20_000, 0);
	}

	public static RpcMessage send(String command, Object arg, long timeoutMillis, int maxRetries) {
		try {
			return sendAsync(command, arg, timeoutMillis, maxRetries).join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (DefaultFuture.isTimeout(cause)) {
				throw new RpcException("RPC call timed out: " + command, cause);
			}
			throw new RpcException("RPC call failed: " + command, cause);
		}
	}

	/** 对 {@code address} 发起一次尝试；是否/如何重试由 {@code Cluster} 策略决定。 */
	private static CompletableFuture<RpcMessage> sendOnce(String address, String command, Object arg, long timeoutMillis) {
		Channel channel = ChannelManager.getChannel(address);
		if (channel == null) {
			CompletableFuture<RpcMessage> failed = new CompletableFuture<>();
			failed.completeExceptionally(new RpcException("No active connection for: " + address));
			return failed;
		}

		// 优先用这条连接已经协商好的序列化方式；协商还没完成（连接刚建立）就退回默认实现——
		// 服务端总是照单收单地用请求里的 serializeType 解码+回复，所以这个兜底是安全的，只是错过了一次优化机会。
		Byte negotiatedCode = channel.attr(NegotiatedSerializer.KEY).get();
		Serializer serializer = negotiatedCode != null
				? Serializers.byCode(negotiatedCode)
				: RpcConfig.resolve(ExtensionLoader.getExtensionLoader(Serializer.class), RpcConfig.SERIALIZER_KEY);
		byte[] argBytes = arg == null ? new byte[0] : serializer.serialize(arg);
		RpcRequest request = new RpcRequest(command, argBytes);
		byte[] payload = serializer.serialize(request);

		long requestId = DefaultFuture.nextRequestId();
		RpcMessage message = new RpcMessage(requestId, MessageType.REQUEST, serializer.code(), Status.OK, payload);

		DefaultFuture df = new DefaultFuture(requestId, timeoutMillis, TimeUnit.MILLISECONDS);
		LoadStats.begin(address);
		df.future().whenComplete((r, e) -> LoadStats.end(address));

		channel.writeAndFlush(message);
		return df.future();
	}
}
