package com.feynman.rpc.core.cluster;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.client.RpcException;
import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.protocol.RpcMessage;

/**
 * 适合调用方宁愿立刻失败也不想阻塞等重试，但框架仍应尽力把请求送达的场景：失败时调用方的
 * future 立即失败（和 failfast 一样），同时在后台安排一次尽力而为的重试，结果只记日志，
 * 不会再回传给原调用方。
 */
public class FailbackCluster implements Cluster {

	private static final Logger log = LoggerFactory.getLogger(FailbackCluster.class);

	private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "rpc-failback-retry");
		t.setDaemon(true);
		return t;
	});

	@Override
	public CompletableFuture<RpcMessage> invoke(ClusterInvocation invocation) {
		Map<String, Integer> addressWeights = invocation.addressWeights();
		LoadBalance loadBalance = RpcConfig.resolve(ExtensionLoader.getExtensionLoader(LoadBalance.class), RpcConfig.LOADBALANCE_KEY);
		String address = loadBalance.select(addressWeights);

		if (address == null) {
			CompletableFuture<RpcMessage> failed = new CompletableFuture<>();
			failed.completeExceptionally(new RpcException("No available provider for: " + invocation.describe()));
			return failed;
		}

		CompletableFuture<RpcMessage> attemptFuture = invocation.attempt(address);
		attemptFuture.whenComplete((resp, err) -> {
			if (err != null) {
				log.warn("Failback: call failed, scheduling one background retry: {}", invocation.describe());
				scheduleBackgroundRetry(invocation, loadBalance);
			}
		});
		return attemptFuture;
	}

	private void scheduleBackgroundRetry(ClusterInvocation invocation, LoadBalance loadBalance) {
		RETRY_SCHEDULER.schedule(() -> {
			String retryAddress = loadBalance.select(invocation.addressWeights());
			if (retryAddress == null) {
				log.warn("Failback: background retry skipped, no provider available: {}", invocation.describe());
				return;
			}
			invocation.attempt(retryAddress).whenComplete((resp, err) -> {
				if (err != null) {
					log.warn("Failback: background retry also failed: {}", invocation.describe());
				} else {
					log.info("Failback: background retry succeeded: {}", invocation.describe());
				}
			});
		}, 1, TimeUnit.SECONDS);
	}
}
