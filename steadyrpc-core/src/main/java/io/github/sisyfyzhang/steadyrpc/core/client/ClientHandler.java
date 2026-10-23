package io.github.sisyfyzhang.steadyrpc.core.client;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sisyfyzhang.steadyrpc.core.RpcException;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MessageType;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * 客户端连接上的入站处理：协商、心跳、把响应交给所属客户端的在途表。在途表和本端支持的序列化列表
 * 由构造参数传入，不再去读全局静态状态——同一 JVM 的两个客户端各自的响应只会落进各自的在途表。
 *
 * <p>引用计数：RESPONSE 的所有权交给在途表（再交给等待者）；其余消息在这里处理完即释放。
 */
public class ClientHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

	private final PendingRequests pending;
	private final byte[] supportedCodesByPriority;

	ClientHandler(PendingRequests pending, byte[] supportedCodesByPriority) {
		this.pending = pending;
		this.supportedCodesByPriority = supportedCodesByPriority.clone();
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		// 新连接建立后立刻发起一次序列化协商：把本端支持的序列化方式按优先级（首选实现最优先）
		// 发给对端，对端在它自己支持的集合里挑一个回给我们，缓存在这条连接上，后续请求就用它。
		ctx.writeAndFlush(RpcMessage.negotiateRequest(supportedCodesByPriority));
		super.channelActive(ctx);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof RpcMessage message)) {
			log.warn("Client ignored non-RpcMessage frame: {}", msg);
			ReferenceCountUtil.release(msg);
			return;
		}
		if (message.messageType() == MessageType.RESPONSE) {
			pending.complete(message);
			return;
		}
		try {
			switch (message.messageType()) {
				case HEARTBEAT_PING -> ctx.writeAndFlush(RpcMessage.heartbeatPong(message.requestId()));
				case HEARTBEAT_PONG -> {
				}
				case NEGOTIATE_RESPONSE -> {
					ByteBuf body = message.body();
					if (body.isReadable()) {
						byte code = body.getByte(body.readerIndex());
						ctx.channel().attr(NegotiatedSerializer.KEY).set(code);
						log.debug("Serializer negotiated with {}: code={}", ctx.channel().remoteAddress(), code);
					}
				}
				default -> log.warn("Client got unexpected message type: {}", message.messageType());
			}
		} finally {
			message.release();
		}
	}

	/**
	 * 连接断开：这条连接上已写出、还没收到响应的请求立即以 RpcException 失败（见 {@link ChannelRequests}），
	 * 不让它们挂到超时；连接池随后按退避重连。
	 */
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		Set<Long> ids = ctx.channel().attr(ChannelRequests.KEY).get();
		if (ids != null && !ids.isEmpty()) {
			RpcException cause = new RpcException(
					"Connection to " + ctx.channel().remoteAddress() + " closed before the response arrived");
			for (Long id : List.copyOf(ids)) {
				pending.fail(id, cause);
			}
			ids.clear();
		}
		super.channelInactive(ctx);
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (!(evt instanceof IdleStateEvent event)) {
			return;
		}
		if (event.state() == IdleState.READER_IDLE) {
			// 太久没收到任何东西（包括服务端自己主动发的心跳）：这条连接大概率已经死了，
			// 关闭它——ConnectionPool 监听 closeFuture 会按退避策略自动重连，不需要在这里手动处理。
			log.info("Client reader idle, closing possibly-dead connection");
			ctx.close();
		} else if (event.state() == IdleState.WRITER_IDLE) {
			ctx.writeAndFlush(RpcMessage.heartbeatPing());
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		// 解码失败意味着帧边界不可信，这条连接上的后续字节都无法解析：关闭，由连接池重连
		if (cause instanceof DecoderException) {
			log.warn("Closing connection to {} after protocol error: {}", ctx.channel().remoteAddress(), cause.getMessage());
		} else {
			log.debug("Closing connection to {} after error", ctx.channel().remoteAddress(), cause);
		}
		ctx.close();
	}
}
