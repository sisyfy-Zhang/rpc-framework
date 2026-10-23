package io.github.sisyfyzhang.steadyrpc.core.cluster;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.testutil.Instances;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailbackClusterTest {

	@Test
	void callerSeesFailureImmediately_doesNotBlockForBackgroundRetry() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		CountDownLatch secondAttemptHappened = new CountDownLatch(1);
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1)), 0, instance -> {
			if (attempts.incrementAndGet() == 1) {
				return StubInvocations.failed("boom");
			}
			secondAttemptHappened.countDown();
			return CompletableFuture.completedFuture(Result.value("ok"));
		});

		try (FailbackCluster cluster = new FailbackCluster()) {
			long start = System.nanoTime();
			CompletableFuture<Result> result = cluster.invoke(invocation);
			assertThrows(CompletionException.class, result::join);
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
			// 后台重试约 1s 后才触发；调用方绝不应该等待接近这个时长
			assertTrue(elapsedMillis < 300, "caller blocked for " + elapsedMillis + "ms waiting on the background retry");

			// 后台重试确实会发生，只是不占用调用方的等待时间
			assertTrue(secondAttemptHappened.await(3, TimeUnit.SECONDS), "background retry never happened");
			assertEquals(2, attempts.get());
		}
	}

	@Test
	void closeStopsTheRetryThread() {
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1)), 0, instance -> StubInvocations.failed("boom"));
		FailbackCluster cluster = new FailbackCluster();
		assertThrows(CompletionException.class, () -> cluster.invoke(invocation).join());
		assertTrue(retryThreadAlive(), "the retry thread should exist once a retry has been scheduled");

		cluster.close();

		// 旧实现的重试线程是永不关闭的静态线程；现在归 Cluster 实例所有，关闭后必须退出
		await().atMost(3, TimeUnit.SECONDS).until(() -> !retryThreadAlive());
	}

	private static boolean retryThreadAlive() {
		return Thread.getAllStackTraces().keySet().stream()
				.anyMatch(t -> t.getName().equals("rpc-failback-retry") && t.isAlive());
	}
}
