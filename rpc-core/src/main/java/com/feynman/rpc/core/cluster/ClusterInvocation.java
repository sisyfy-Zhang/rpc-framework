package com.feynman.rpc.core.cluster;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.feynman.rpc.core.protocol.RpcMessage;

/** {@link Cluster} 策略发起一次调用（以及重试时重新发起）所需要的信息。 */
public interface ClusterInvocation {

	/** 所有当前存活的服务地址及权重，供 LoadBalance 使用。 */
	Map<String, Integer> addressWeights();

	/** 向指定地址发送一次请求；不负责选址也不负责重试。 */
	CompletableFuture<RpcMessage> attempt(String address);

	/** 首次之外允许的额外重试次数（0 = 只试一次）。来自 {@code @RemoteInvoke.retries()}。 */
	int maxRetries();

	/** 供错误信息/日志使用。 */
	String describe();
}
