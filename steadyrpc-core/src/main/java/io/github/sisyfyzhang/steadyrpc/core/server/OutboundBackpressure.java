package io.github.sisyfyzhang.steadyrpc.core.server;

import java.util.concurrent.atomic.LongAdder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;

/**
 * 服务端出站背压（缺陷 F1，{@code RpcServerConfig.outboundBackpressure}）：连接变为不可写时暂停读它
 * （{@code setAutoRead(false)}），恢复可写时再读。每条连接一个实例，只在该连接的事件循环上运行。框架内部类型。
 *
 * <p><b>为什么是读侧背压</b>：业务执行器的有界队列 / 并发上限管不到回包积压——在途计数在回包交给 Netty 时就已归还。
 * 一个只发请求不读响应的客户端（或拥塞的网络）会让回包在 {@code ChannelOutboundBuffer}（直接内存）里无界堆积。
 * 停读之后不再接新请求，已在途的照常完成，积压被"在途数 × 回包大小"封顶；对端开始读、待写字节降到低水位以下时恢复。
 *
 * <p><b>为什么按当前状态对账，而不是按事件方向切换</b>：pipeline 首位的 FlushConsolidationHandler 在收到"变为不可写"时
 * 先冲刷积压的写；套接字若一次收下，这次冲刷会<b>嵌套</b>触发一次"恢复可写"，它比外层的"不可写"事件先到达本 handler。
 * 每次事件都看 {@code channel.isWritable()} 的当前值，嵌套与乱序都不会让读停在错误的状态。
 *
 * <p><b>为什么单独一个 handler 而不是写进 ServerHandler</b>：关掉背压（对照实验）时它根本不在 pipeline 里，A/B 的差别
 * 只有这一个 handler；它可以用 EmbeddedChannel 单独测；ServerHandler 只负责请求分发。硬上限的拒绝要回 SERVER_OVERLOADED，
 * 需要分发器构造回包，留在 ServerHandler 里（{@link #pendingWriteBytes(Channel)} 两边共用）。
 *
 * <p>与读空闲的交互：暂停读期间 IdleStateHandler 的读空闲计时照常走，持续暂停超过读空闲阈值时 ServerHandler 关闭连接——
 * 对"长期不读的客户端"这是兜底。心跳不受影响：服务端的 pong 只回应已读到的 ping（暂停期间读不到 ping，也就不回），
 * 服务端写空闲时主动发的 ping 照常排进写缓冲区。
 */
final class OutboundBackpressure extends ChannelInboundHandlerAdapter {

	/** pipeline 里的 handler 名（测试据此检查配置是否生效）。 */
	static final String HANDLER_NAME = "outboundBackpressure";

	/** 所属服务端的暂停读次数（全部连接共用）。 */
	private final LongAdder pauses;
	/** 本 handler 是否暂停了读。只在事件循环上读写。 */
	private boolean paused;

	OutboundBackpressure(LongAdder pauses) {
		this.pauses = pauses;
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
		reconcile(ctx.channel());
		ctx.fireChannelWritabilityChanged();
	}

	private void reconcile(Channel channel) {
		if (!channel.isWritable()) {
			if (!paused) {
				paused = true;
				channel.config().setAutoRead(false);
				pauses.increment();
			}
		} else if (paused) {
			paused = false;
			// 重新登记读兴趣；暂停期间对端写来的请求留在内核接收缓冲区里，恢复后照常读出
			channel.config().setAutoRead(true);
		}
	}

	/** 本 handler 当前是否暂停了读（测试用）。 */
	boolean paused() {
		return paused;
	}

	/**
	 * 连接此刻的待写字节：已交给 Netty、还没写进套接字的出站数据（含未冲刷的），与水位线判定同一个数。
	 * 连接已关闭（出站缓冲区已释放）时为 0。任何线程可调用（读一个 volatile 字段）。
	 */
	static long pendingWriteBytes(Channel channel) {
		ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
		return buffer == null ? 0 : buffer.totalPendingWriteBytes();
	}
}
