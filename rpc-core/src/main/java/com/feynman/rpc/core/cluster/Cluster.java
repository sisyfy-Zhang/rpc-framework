package com.feynman.rpc.core.cluster;

import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.filter.Result;

/**
 * 容错策略，包装节点选择（LoadBalance）+ 一次发送尝试。默认是 failfast（尝试一次，成败自负）；
 * 切到 failover 也只对 {@code ReferenceOptions.retries()} 大于默认值 0 的引用真正重试——
 * 容错策略不会悄悄让没声明"允许重试"的调用被重试。
 *
 * <p>只有返回的 future 异常完成才算"这次尝试失败、该节点此刻不可用"：传输失败（连接拒绝 / 重置 / 写失败）与
 * 服务端拒绝（SERVER_OVERLOADED：过载或正在停机，业务一定没执行）。服务端执行后明确返回的错误、以及本次调用的
 * deadline 已耗尽，都装在 {@link Result} 里，不触发重试（见 Result 的说明与 D-009）。
 *
 * <p>实现可以持有资源（failback 的重试调度线程），所以每个 RpcClient 各自 new 一份并在关闭时 {@link #close()}。
 */
@SPI("failfast")
public interface Cluster extends AutoCloseable {

	CompletableFuture<Result> invoke(ClusterInvocation invocation);

	@Override
	default void close() {
	}
}
