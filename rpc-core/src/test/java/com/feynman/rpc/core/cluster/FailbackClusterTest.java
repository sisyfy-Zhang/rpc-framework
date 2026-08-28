package com.feynman.rpc.core.cluster;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailbackClusterTest {

	@Test
	void callerSeesFailureImmediately_doesNotBlockForBackgroundRetry() {
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch secondAttemptHappened = new CountDownLatch(1);

		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return Map.of("a", 1);
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				int n = attempts.incrementAndGet();
				CompletableFuture<RpcMessage> f = new CompletableFuture<>();
				if (n == 1) {
					f.completeExceptionally(new RuntimeException("boom"));
				} else {
					secondAttemptHappened.countDown();
					f.complete(new RpcMessage(1, MessageType.RESPONSE, (byte) 1, Status.OK, new byte[0]));
				}
				return f;
			}

			@Override
			public int maxRetries() {
				return 0;
			}

			@Override
			public String describe() {
				return "test";
			}
		};

		long start = System.nanoTime();
		CompletableFuture<RpcMessage> result = new FailbackCluster().invoke(invocation);
		assertThrows(CompletionException.class, result::join);
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
		// 后台重试约 1s 后才触发；调用方绝不应该等待接近这个时长
		assertTrue(elapsedMillis < 300, "caller blocked for " + elapsedMillis + "ms waiting on the background retry");

		// 后台重试确实会发生，只是不占用调用方的等待时间
		boolean retried = awaitUninterruptibly(secondAttemptHappened, 3, TimeUnit.SECONDS);
		assertTrue(retried, "background retry never happened");
		assertEquals(2, attempts.get());
	}

	private static boolean awaitUninterruptibly(CountDownLatch latch, long timeout, TimeUnit unit) {
		try {
			return latch.await(timeout, unit);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
