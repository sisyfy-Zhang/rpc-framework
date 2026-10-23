package io.github.sisyfyzhang.steadyrpc.core.server;

import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sisyfyzhang.steadyrpc.core.internal.InFlightGate;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MessageType;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializers;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * 服务端连接上的入站处理。协商与心跳在 IO 线程上就地处理（几十纳秒的事）；请求交给业务执行器，
 * IO 线程不跑业务代码、不反序列化 body——opt-in 的 {@code ExecutorConfig.direct()} 除外：业务方法（含参数反序列化、Filter 链、
 * 结果序列化）就在这里的 IO 线程上执行，回包在读循环内写出，flush 由 FlushConsolidationHandler 推迟到本轮读完。
 * 方法表、执行器、在途闸门、兜底序列化方式都由所属 RpcServer 通过构造参数注入。
 *
 * <p>拒绝（都回 SERVER_OVERLOADED，业务方法没有执行，客户端可安全地换节点重试）：
 * <ul>
 *   <li>在途闸门已关（RpcServer 正在优雅停机的 DRAINING 阶段）：消息写明 {@code "server shutting down"}；</li>
 *   <li>业务执行器队列满 / 并发达到上限：消息写明 {@code "server overloaded"}；</li>
 *   <li>这条连接的待写字节超过出站硬上限（{@code RpcServerConfig.outboundHardLimit}，对端读得太慢）：消息写明
 *       {@code "server overloaded: outbound buffer"}（固定短常量），计入 {@code outboundOverloadRejections}，每条连接第一次另记一行 warn。
 *       连接不可写时 {@link OutboundBackpressure} 已经停读，落到这里的只是停读之前已经读进来、还没解码完的帧。</li>
 * </ul>
 * 心跳与协商不经过闸门：停机期间连接仍然健康，客户端不应因为心跳失败而误判连接已死、触发重连风暴。
 *
 * <p>在途计数：请求进入执行器之前 +1，回包交给 Netty 写出之后 -1（异步业务在 future 完成、回包写出时才 -1）。
 * 为什么"交给 Netty"就算完成而不等写完成回调：回包的写任务先于停机时的关连接任务进入同一个 channel 的事件循环，
 * 一定先被执行；而写完成回调在事件循环关闭后可能根本不会被通知，靠它减计数会让停机后的在途数永远不归零。
 *
 * <p>回包写失败（连接已断、编码器拒绝）不会静默：每次记 debug（requestId、状态、原因类名）并计入所属服务端的
 * {@code replyWriteFailures}，每条连接的第一次另记一行 warn（之后的同连接失败只记 debug，避免断连时刷屏）。
 * 客户端那一侧此时只能等到超时或连接关闭，服务端日志是唯一的线索。回包的 promise 自己携带 requestId / 状态 / 所属 handler
 * （{@link ReplyPromise}），监听器是一个共享的静态实例，不再每个回包另配一个捕获变量的 lambda。
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
	/** 所属服务端的回包写失败计数（全部连接共用）。 */
	private final LongAdder replyWriteFailures;
	/** 本连接是否已经为回包写失败记过 warn。只在事件循环上写；偶发重复一行 warn 无害，不需要更强的同步。 */
	private volatile boolean replyWriteFailureWarned;
	/** 出站硬上限（字节），0 = 不检查。 */
	private final long outboundHardLimit;
	/** 所属服务端因出站硬上限拒绝的请求数（全部连接共用）。 */
	private final LongAdder outboundOverloadRejections;
	/** 本连接是否已经为出站硬上限记过 warn。只在事件循环上读写。 */
	private boolean outboundOverloadWarned;
	/** 本连接是否已经为执行器过载拒绝记过 warn。只在事件循环上读写。 */
	private boolean executorRejectionWarned;
	/** 所属服务端因业务执行器过载（队列满 / 并发达到上限）拒绝的请求数（全部连接共用；不含停机拒绝）。 */
	private final LongAdder executorRejections;
	/** 所属服务端因正在停机（在途闸门已关、执行器已关闭）拒绝的请求数（全部连接共用）。 */
	private final LongAdder shutdownRejections;

	ServerHandler(ServiceDispatcher dispatcher, BusinessExecutor bizExecutor, InFlightGate gate, byte fallbackSerializerCode) {
		this(dispatcher, bizExecutor, gate, fallbackSerializerCode, new LongAdder());
	}

	ServerHandler(ServiceDispatcher dispatcher, BusinessExecutor bizExecutor, InFlightGate gate, byte fallbackSerializerCode,
			LongAdder replyWriteFailures) {
		this(dispatcher, bizExecutor, gate, fallbackSerializerCode, replyWriteFailures, 0, new LongAdder());
	}

	/**
	 * @param outboundHardLimit          这条连接待写字节的硬上限（字节），超过时新请求回 SERVER_OVERLOADED；0 = 不检查
	 * @param outboundOverloadRejections 所属服务端因此拒绝的请求计数
	 */
	ServerHandler(ServiceDispatcher dispatcher, BusinessExecutor bizExecutor, InFlightGate gate, byte fallbackSerializerCode,
			LongAdder replyWriteFailures, long outboundHardLimit, LongAdder outboundOverloadRejections) {
		this(dispatcher, bizExecutor, gate, fallbackSerializerCode, replyWriteFailures, outboundHardLimit, outboundOverloadRejections,
				new LongAdder(), new LongAdder());
	}

	/**
	 * @param executorRejections 所属服务端因业务执行器过载拒绝的请求计数（{@code RpcServer.executorRejections()}）
	 * @param shutdownRejections 所属服务端因正在停机拒绝的请求计数（{@code RpcServer.shutdownRejections()}）
	 */
	ServerHandler(ServiceDispatcher dispatcher, BusinessExecutor bizExecutor, InFlightGate gate, byte fallbackSerializerCode,
			LongAdder replyWriteFailures, long outboundHardLimit, LongAdder outboundOverloadRejections, LongAdder executorRejections,
			LongAdder shutdownRejections) {
		this.executorRejections = executorRejections;
		this.shutdownRejections = shutdownRejections;
		this.dispatcher = dispatcher;
		this.bizExecutor = bizExecutor;
		this.gate = gate;
		this.fallbackSerializerCode = fallbackSerializerCode;
		this.replyWriteFailures = replyWriteFailures;
		this.outboundHardLimit = outboundHardLimit;
		this.outboundOverloadRejections = outboundOverloadRejections;
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
		if (outboundHardLimit > 0) {
			long pendingBytes = OutboundBackpressure.pendingWriteBytes(ctx.channel());
			if (pendingBytes > outboundHardLimit) {
				rejectOutboundOverload(ctx, request, pendingBytes);
				return;
			}
		}
		if (!gate.tryEnter()) {
			shutdownRejections.increment();
			rejectNow(ctx, request, "server shutting down: request rejected while draining in-flight requests");
			return;
		}
		try {
			// direct 模式下 execute 就在本线程（IO 事件循环）上执行任务；其余模式投递到业务线程
			bizExecutor.execute(new DispatchTask(ctx, request));
		} catch (BusinessExecutor.Rejected e) {
			gate.exit();
			(e.shuttingDown() ? shutdownRejections : executorRejections).increment();
			if (!e.shuttingDown()) {
				logExecutorRejection(ctx, request, e);
			}
			rejectNow(ctx, request, e.getMessage());
		}
	}

	/**
	 * 出站硬上限：不交给执行器（业务不执行），回 SERVER_OVERLOADED；计数，每条连接第一次记 warn。回包里的原因是固定的短常量
	 * {@value #OUTBOUND_OVERLOAD_REASON}：被拒绝时这条连接本来就不可写，每个拒绝帧多带的字节都会加进积压（direct 执行器下一批帧
	 * 的拒绝帧尤其如此），细节（待写字节、硬上限）只进日志。
	 */
	private void rejectOutboundOverload(ChannelHandlerContext ctx, RpcMessage request, long pendingBytes) {
		outboundOverloadRejections.increment();
		if (!outboundOverloadWarned) {
			outboundOverloadWarned = true;
			log.warn("Rejecting requests from {} with SERVER_OVERLOADED: the outbound buffer of this connection holds {} bytes not yet"
					+ " written to the socket (> outboundHardLimit {}); the client is not reading responses. Further rejections on this"
					+ " connection are counted in outboundOverloadRejections without logging",
					ctx.channel().remoteAddress(), pendingBytes, outboundHardLimit);
		}
		rejectNow(ctx, request, OUTBOUND_OVERLOAD_REASON);
	}

	/**
	 * 执行器过载拒绝的日志：与回包写失败同一模式——每条连接第一次记一行 warn，之后只记 debug（计数照常进 {@code executorRejections}）。
	 * 过载时每秒可能有上万次拒绝，逐条 warn 会在 IO 线程上同步写日志、把过载放大（突增实验里 20s 写出 7 万行、17MB）。
	 */
	private void logExecutorRejection(ChannelHandlerContext ctx, RpcMessage request, BusinessExecutor.Rejected e) {
		if (!executorRejectionWarned) {
			executorRejectionWarned = true;
			log.warn("Rejected request {} from {}: {}; further executor rejections on this connection are counted in "
					+ "RpcServer.executorRejections() and logged at DEBUG", request.requestId(), ctx.channel().remoteAddress(), e.getMessage());
		} else if (log.isDebugEnabled()) {
			log.debug("Rejected request {} from {}: {}", request.requestId(), ctx.channel().remoteAddress(), e.getMessage());
		}
	}

	/** 本连接是否已经为执行器过载拒绝记过 warn（测试用）。 */
	boolean executorRejectionWarned() {
		return executorRejectionWarned;
	}

	/** 出站硬上限拒绝时回包里的原因（与执行器拒绝的 "server overloaded" 同一风格）。 */
	static final String OUTBOUND_OVERLOAD_REASON = "server overloaded: outbound buffer";

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
			// 编码器在那里分配整帧
			dispatcher.handle(request).whenComplete((reply, error) -> {
				try {
					if (reply != null) {
						reply(ctx, request, reply);
					} else {
						log.error("Request {} produced no reply", request.requestId(), error);
					}
				} finally {
					try {
						gate.exit();
					} finally {
						bizExecutor.completed();
					}
				}
			});
		}

		/** 任务不会再执行：释放请求，归还在途计数。 */
		void abandon() {
			try {
				request.release();
			} finally {
				try {
					gate.exit();
				} finally {
					bizExecutor.completed();
				}
			}
		}
	}

	/** 单向请求不回包（回包直接丢弃；携带 OutboundBody 的回包 release 是空操作）。写失败见类注释。 */
	private void reply(ChannelHandlerContext ctx, RpcMessage request, RpcMessage reply) {
		if (request.isOneway()) {
			reply.release();
			return;
		}
		ReplyPromise promise = new ReplyPromise(ctx.channel(), this, request.requestId(), reply.status());
		promise.addListener(REPLY_WRITTEN);
		ctx.writeAndFlush(reply, promise);
	}

	/** 所有回包共用的写完成监听器：写失败时按 promise 携带的 requestId / 状态报告。 */
	private static final ChannelFutureListener REPLY_WRITTEN = f -> {
		if (!f.isSuccess()) {
			ReplyPromise promise = (ReplyPromise) f;
			promise.handler.replyWriteFailed(f, promise.requestId, promise.status);
		}
	};

	/** 回包的 promise：带上写失败报告需要的字段，监听器因此可以是静态共享的。 */
	private static final class ReplyPromise extends DefaultChannelPromise {
		private final ServerHandler handler;
		private final long requestId;
		private final Status status;

		ReplyPromise(Channel channel, ServerHandler handler, long requestId, Status status) {
			super(channel);
			this.handler = handler;
			this.requestId = requestId;
			this.status = status;
		}
	}

	private void replyWriteFailed(ChannelFuture f, long requestId, Status status) {
		replyWriteFailures.increment();
		Throwable cause = f.cause();
		String causeName = cause == null ? "unknown" : cause.getClass().getName();
		if (!replyWriteFailureWarned) {
			replyWriteFailureWarned = true;
			log.warn("Failed to write reply for request {} (status {}) to {}: {}: {}; further reply write failures on this connection"
					+ " are logged at DEBUG", requestId, status, f.channel().remoteAddress(), causeName, cause == null ? "" : cause.getMessage());
		} else if (log.isDebugEnabled()) {
			log.debug("Failed to write reply for request {} (status {}) to {}: {}", requestId, status, f.channel().remoteAddress(), causeName);
		}
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
