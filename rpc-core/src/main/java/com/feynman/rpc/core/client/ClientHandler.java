package com.feynman.rpc.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public class ClientHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		// 新连接建立后立刻发起一次序列化协商：把本端支持的序列化方式按优先级（默认实现最优先）
		// 发给对端，对端在它自己支持的集合里挑一个回给我们，缓存在这条连接上，后续请求就用它。
		ctx.writeAndFlush(new RpcMessage(0, MessageType.NEGOTIATE_REQUEST, (byte) 0, Status.OK, supportedCodesByPriority()));
		super.channelActive(ctx);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof RpcMessage)) {
			log.warn("Client ignored non-RpcMessage frame: {}", msg);
			return;
		}
		RpcMessage message = (RpcMessage) msg;

		switch (message.getMessageType()) {
			case HEARTBEAT_PING:
				ctx.writeAndFlush(RpcMessage.heartbeatPong(message.getRequestId()));
				return;
			case HEARTBEAT_PONG:
				return;
			case NEGOTIATE_RESPONSE:
				byte[] payload = message.getPayload();
				if (payload != null && payload.length > 0) {
					ctx.channel().attr(NegotiatedSerializer.KEY).set(payload[0]);
					log.debug("Serializer negotiated with {}: code={}", ctx.channel().remoteAddress(), payload[0]);
				}
				return;
			case RESPONSE:
				DefaultFuture.receive(message);
				return;
			default:
				log.warn("Client got unexpected message type: {}", message.getMessageType());
		}
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (!(evt instanceof IdleStateEvent)) {
			return;
		}
		IdleStateEvent event = (IdleStateEvent) evt;
		if (event.state() == IdleState.READER_IDLE) {
			// 太久没收到任何东西（包括服务端自己主动发的心跳）：这条连接大概率已经死了，
			// 关闭它——ConnectionPool 监听 closeFuture 会按退避策略自动重连，不需要在这里手动处理。
			log.info("Client reader idle, closing possibly-dead connection");
			ctx.close();
		} else if (event.state() == IdleState.WRITER_IDLE) {
			ctx.writeAndFlush(RpcMessage.heartbeatPing(0));
		}
	}

	private static byte[] supportedCodesByPriority() {
		ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
		// 配置里显式声明的序列化方式（如果有）最优先，其次才是 @SPI 注解上的硬编码默认值
		String preferredName = RpcConfig.get(RpcConfig.SERIALIZER_KEY);
		if (preferredName == null) {
			preferredName = Serializer.class.getAnnotation(SPI.class).value();
		}
		Set<String> names = loader.getSupportedExtensions();

		List<Byte> codes = new ArrayList<>();
		if (names.contains(preferredName)) {
			codes.add(loader.getExtension(preferredName).code());
		}
		for (String name : names) {
			if (!name.equals(preferredName)) {
				codes.add(loader.getExtension(name).code());
			}
		}

		byte[] result = new byte[codes.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = codes.get(i);
		}
		return result;
	}

}
