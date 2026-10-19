package com.feynman.rpc.core.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.internal.InFlightGate;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializers;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * 服务端连接上的入站处理。协商与心跳在 IO 线程上就地处理（几十纳秒的事）；请求交给业务执行器，
 * IO 线程不跑业务代码、不反序列化 body。方法表、执行器、在途闸门、兜底序列化方式都由所属 RpcServer 通过构造参数注入。
 *
 * <p>拒绝（都回 SERVER_OVERLOADED，业务方法没有执行，客户端可安全地换节点重试）：
 * <ul>
 *   <li>在途闸门已关（RpcServer 正在优雅停机的 DRAINING 阶段）：消息写明 {@code "server shutting down"}；</li>
 *   <li>业务执行器队列满 / 并发达到上限：消息写明 {@code "server overloaded"}。</li>
 * </ul>
 * 心跳与协商不经过闸门：停机期间连接仍然健康，客户端不应因为心跳失败而误判连接已死、触发重连风暴。
 *
 * <p>在途计数：请求进入执行器之前 +1，回包交给 Netty 写出之后 -1（异步业务在 future 完成、回包写出时才 -1）。
 * 为什么"交给 Netty"就算完成而不等写完成回调：回包的写任务先于停机时的关连接任务进入同一个 channel 的事件循环，
 * 一定先被执行；而写完成回调在事件循环关闭后可能根本不会被通知，靠它减计数会让停机后的在途数永远不归零。
 *
 * <p>引用计数：REQUEST 的所有权交给业务线程上的分发器（由它释放）；投递失败、被拒绝、attachments 非法时在这里释放；
 * 其余消息处理完即释放。
 */
public class ServerHandler extends ChannelInboundHandlerAdapter {

	private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);

	private final ServiceDispatcher dispatcher;
	private final BusinessExecutor bizExecutor;
	private final InFlightGate gate;
	private final byte fallbackSerializerCode;

	ServerHandler(ServiceDispatcher dispatcher, BusinessExecutor bizExecutor, InFlightGate gate, byte fallbackSerializerCode) {
		this.dispatcher = dispatcher;
		this.bizExecutor = bizExecutor;
		this.gate = gate;
		this.fallbackSerializerCode = fallbackSerializerCode;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof RpcMessage message)) {
			log.warn("Server ignored non-RpcMessage frame: {}", msg);
			ReferenceCountUtil.release(msg);
			return;
		}
		if (message.messageType() == MessageType.REQUEST) {
			dispatch(ctx, message);
			return;
		}
		try {
			switch (message.messageType()) {
				case HEARTBEAT_PONG -> {
				}
				case HEARTBEAT_PING -> ctx.writeAndFlush(RpcMessage.heartbeatPong(message.requestId()));
				case NEGOTIATE_REQUEST -> ctx.writeAndFlush(RpcMessage.negotiateResponse(chooseSerializer(message.body())));
				default -> log.warn("Server got unexpected message type: {}", message.messageType());
			}
		} finally {
			message.release();
		}
	}

	private void dispatch(ChannelHandlerContext ctx, RpcMessage request) {
		if (request.malformedAttachments() != null) {
			// 帧边界可信、只是 attachments 不合法：带着 requestId 回 BAD_REQUEST，连接继续可用
			try {
				reply(ctx, request, dispatcher.badAttachmentsReply(request));
			} finally {
				request.release();
			}
			return;
		}
		if (!gate.tryEnter()) {
			rejectNow(ctx, request, "server shutting down: request rejected while draining in-flight requests");
			return;
		}
		try {
			bizExecutor.execute(new DispatchTask(ctx, request));
		} catch (BusinessExecutor.Rejected e) {
			gate.exit();
			if (!e.shuttingDown()) {
				log.warn("Rejected request {} from {}: {}", request.requestId(), ctx.channel().remoteAddress(), e.getMessage());
			}
			rejectNow(ctx, request, e.getMessage());
		}
	}

	private void rejectNow(ChannelHandlerContext ctx, RpcMessage request, String reason) {
		try {
			reply(ctx, request, dispatcher.frameworkErrorReply(request, Status.SERVER_OVERLOADED, reason));
		} finally {
			request.release();
		}
	}

	/**
	 * 投递到业务执行器的任务。做成具名类而不是 lambda：服务端关闭时 {@code shutdownNow()} 交回的未执行任务
	 * 可以识别出来并释放它持有的请求、归还在途计数（见 NettyServer.close），不让排队中的请求缓冲区泄漏。
	 */
	final class DispatchTask implements Runnable {
		private final ChannelHandlerContext ctx;
		private final RpcMessage request;

		DispatchTask(ChannelHandlerContext ctx, RpcMessage request) {
			this.ctx = ctx;
			this.request = request;
		}

		@Override
		public void run() {
			// handle 不抛异常；返回的 future 在业务完成时完成——同步业务就在本线程，异步业务在完成它的线程上。
			// 回包只携带备好的字节，本线程（业务线程，可能是虚拟线程）不分配缓冲区：writeAndFlush 把写出交给事件循环，
			// 编码器在那里分配整帧（docs/pitfalls.md 第 32 条）
			dispatcher.handle(request).whenComplete((reply, error) -> {
				try {
					if (reply != null) {
						reply(ctx, request, reply);
					} else {
						log.error("Request {} produced no reply", request.requestId(), error);
					}
				} finally {
					gate.exit();
				}
			});
		}

		/** 任务不会再执行：释放请求，归还在途计数。 */
		void abandon() {
			try {
				request.release();
			} finally {
				gate.exit();
			}
		}
	}

	/** 单向请求不回包（回包直接丢弃；携带 OutboundBody 的回包 release 是空操作）。 */
	private static void reply(ChannelHandlerContext ctx, RpcMessage request, RpcMessage reply) {
		if (request.isOneway()) {
			reply.release();
			return;
		}
		ctx.writeAndFlush(reply);
	}

	/** 从客户端的优先级列表里选出本服务端也支持的第一个编码；完全没有交集时退回本服务端配置的序列化方式。 */
	private byte chooseSerializer(ByteBuf clientPreferredCodes) {
		Serializers serializers = dispatcher.serializers();
		for (int i = clientPreferredCodes.readerIndex(); i < clientPreferredCodes.writerIndex(); i++) {
			byte code = clientPreferredCodes.getByte(i);
			if (serializers.find(code) != null) {
				return code;
			}
		}
		return fallbackSerializerCode;
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof IdleStateEvent event) {
			if (event.state() == IdleState.READER_IDLE) {
				log.info("Reader idle, closing connection");
				ctx.close();
			} else if (event.state() == IdleState.WRITER_IDLE) {
				ctx.writeAndFlush(RpcMessage.heartbeatPing());
			}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		// 帧头不合法（magic / version / 长度）意味着之后的字节无法再对齐帧边界：关闭连接
		if (cause instanceof DecoderException) {
			log.warn("Closing connection from {} after protocol error: {}", ctx.channel().remoteAddress(), cause.getMessage());
		} else {
			log.debug("Closing connection from {} after error", ctx.channel().remoteAddress(), cause);
		}
		ctx.close();
	}
}
