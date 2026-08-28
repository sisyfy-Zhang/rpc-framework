package com.feynman.rpc.core.cluster;

import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.protocol.RpcMessage;

/**
 * 容错策略，包装节点选择（LoadBalance）+ 一次发送尝试。默认是 failfast（尝试一次，成败自负）；
 * 切到 failover 也只对 {@code @RemoteInvoke.retries()} 大于默认值 0 的调用真正重试——
 * 容错策略不会悄悄让没声明"允许重试"的调用被重试。
 */
@SPI("failfast")
public interface Cluster {

	CompletableFuture<RpcMessage> invoke(ClusterInvocation invocation);
}
