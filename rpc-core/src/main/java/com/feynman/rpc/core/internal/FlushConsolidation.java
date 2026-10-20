package com.feynman.rpc.core.internal;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.flush.FlushConsolidationHandler;

/**
 * 客户端与服务端连接共用的 flush 合并配置（{@code RpcClientConfig.flushConsolidation} / {@code RpcServerConfig.flushConsolidation}）。
 * 框架内部类型。
 *
 * <p>{@link FlushConsolidationHandler} 放在 pipeline 首位（最靠近 transport）：读循环里产生的 flush 推迟到本轮读完
 * （{@code channelReadComplete}），一次读出的多个请求的回包合成一次 {@code write} 系统调用；事件循环外来的 flush
 * （{@code consolidateWhenNoReadInProgress = true}）改为向事件循环提交一个 flush 任务，排在它之前的写任务被同一次 flush 带走。
 * 不改协议字节、不加定时器。
 */
public final class FlushConsolidation {

	/** pipeline 里的 handler 名（测试据此检查配置是否生效）。 */
	public static final String HANDLER_NAME = "flushConsolidation";

	/**
	 * explicitFlushAfterFlushes：连续这么多次 flush 没有真正冲刷时强制冲刷一次，防止持续高负载下写出一直被推迟。
	 * 256 是 Netty 的缺省值。
	 */
	public static final int EXPLICIT_FLUSH_AFTER_FLUSHES = 256;

	private FlushConsolidation() {
	}

	/** 在 pipeline 首位加 FlushConsolidationHandler（在 initChannel 里、其余 handler 之前调用）。 */
	public static void addFirst(ChannelPipeline pipeline) {
		pipeline.addFirst(HANDLER_NAME, new FlushConsolidationHandler(EXPLICIT_FLUSH_AFTER_FLUSHES, true));
	}
}
