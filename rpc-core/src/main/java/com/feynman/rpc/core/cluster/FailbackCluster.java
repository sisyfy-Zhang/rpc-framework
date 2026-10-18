package com.feynman.rpc.core.cluster;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 适合调用方宁愿立刻失败也不想阻塞等重试，但框架仍应尽力把请求送达的场景：失败时调用方的
 * future 立即失败（和 failfast 一样），同时在后台安排一次尽力而为的重试，结果只记日志，
 * 不会再回传给原调用方。
 *
 * <p>重试调度线程归这个 Cluster 实例（即所属 RpcClient）所有、首次需要时才创建、{@link #close()} 时关闭——
 * 旧实现是一个永不关闭的静态线程，客户端关了它还在。
 */
public class FailbackCluster implements Cluster {

	private static final Logger log = LoggerFactory.getLogger(FailbackCluster.class);
	private static final long RETRY_DELAY_MS = 1000;

	private ScheduledExecutorService retryScheduler;
	private boolean closed;

	@Override
	public CompletableFuture<Result> invoke(ClusterInvocation invocation) {
		ServiceInstance instance = invocation.select(invocation.candidates());
		if (instance == null) {
			return CompletableFuture.failedFuture(new RpcException("No available provider for: " + invocation.describe()));
		}

		CompletableFuture<Result> attemptFuture = invocation.attempt(instance);
		attemptFuture.whenComplete((resp, err) -> {
			if (err != null) {
				log.warn("Failback: call failed, scheduling one background retry: {}", invocation.describe());
				scheduleBackgroundRetry(invocation);
			}
		});
		return attemptFuture;
	}

	private void scheduleBackgroundRetry(ClusterInvocation invocation) {
		ScheduledExecutorService scheduler = scheduler();
		if (scheduler == null) {
			log.warn("Failback: client is closing, background retry dropped: {}", invocation.describe());
			return;
		}
		try {
			scheduler.schedule(() -> retryOnce(invocation), RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			log.warn("Failback: client is closing, background retry dropped: {}", invocation.describe());
		}
	}

	private void retryOnce(ClusterInvocation invocation) {
		ServiceInstance retryInstance = invocation.select(invocation.candidates());
		if (retryInstance == null) {
			log.warn("Failback: background retry skipped, no provider available: {}", invocation.describe());
			return;
		}
		invocation.attempt(retryInstance).whenComplete((resp, err) -> {
			if (err != null) {
				log.warn("Failback: background retry also failed: {}", invocation.describe());
			} else {
				log.info("Failback: background retry succeeded: {}", invocation.describe());
			}
		});
	}

	private synchronized ScheduledExecutorService scheduler() {
		if (closed) {
			return null;
		}
		if (retryScheduler == null) {
			retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "rpc-failback-retry");
				t.setDaemon(true);
				return t;
			});
		}
		return retryScheduler;
	}

	@Override
	public synchronized void close() {
		closed = true;
		if (retryScheduler != null) {
			retryScheduler.shutdownNow();
			retryScheduler = null;
		}
	}
}
