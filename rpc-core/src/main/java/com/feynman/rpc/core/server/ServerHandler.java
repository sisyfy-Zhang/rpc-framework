package com.feynman.rpc.core.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.mediator.MethodMediator;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

public class ServerHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof RpcMessage)) {
			log.warn("Server ignored non-RpcMessage frame: {}", msg);
			return;
		}
		RpcMessage message = (RpcMessage) msg;

		switch (message.getMessageType()) {
			case REQUEST:
				RpcMessage reply = MethodMediator.newInstance().process(message);
				ctx.writeAndFlush(reply);
				return;
			case HEARTBEAT_PONG:
				return;
			case HEARTBEAT_PING:
				ctx.writeAndFlush(RpcMessage.heartbeatPong(message.getRequestId()));
				return;
			case NEGOTIATE_REQUEST:
				byte chosen = chooseSerializer(message.getPayload());
				ctx.writeAndFlush(new RpcMessage(0, MessageType.NEGOTIATE_RESPONSE, (byte) 0, Status.OK,
						new byte[] { chosen }));
				return;
			default:
				log.warn("Server got unexpected message type: {}", message.getMessageType());
		}
	}

	/** 从客户端的优先级列表里选出本服务端也支持的第一个编码；完全没有交集时退回本服务端自己的默认序列化方式。 */
	private static byte chooseSerializer(byte[] clientPreferredCodes) {
		if (clientPreferredCodes != null) {
			for (byte code : clientPreferredCodes) {
				try {
					Serializers.byCode(code);
					return code; // 本服务端也能解码这个编号
				} catch (IllegalStateException notSupported) {
					// 尝试客户端列表里的下一个优先级
				}
			}
		}
		String defaultName = RpcConfig.get(RpcConfig.SERIALIZER_KEY);
		if (defaultName == null) {
			defaultName = Serializer.class.getAnnotation(SPI.class).value();
		}
		Serializer fallback = ExtensionLoader.getExtensionLoader(Serializer.class).getExtension(defaultName);
		return fallback.code();
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

		if(evt instanceof IdleStateEvent) {
			IdleStateEvent event = (IdleStateEvent)evt;
			if(event.state() == IdleState.READER_IDLE) {
				log.info("Reader idle, closing connection");
				ctx.close();
			} else if(event.state() == IdleState.WRITER_IDLE) {
				ctx.writeAndFlush(RpcMessage.heartbeatPing(0));
			} else if(event.state() == IdleState.ALL_IDLE) {
				log.debug("Reader+writer idle");
			}
		}

	}
};
