package io.github.sisyfyzhang.steadyrpc.core.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.testutil.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailoverClusterTest {

	@Test
	void retriesOnADifferentAddressAndSucceeds() {
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1), Instances.at(2)), 1, instance -> {
			attempted.add(instance.address());
			return attempted.size() == 1
					? StubInvocations.failed("first node is down")
					: CompletableFuture.completedFuture(Result.value("ok"));
		});

		Result result = new FailoverCluster().invoke(invocation).join();
		assertEquals(2, attempted.size());
		assertNotEquals(attempted.get(0), attempted.get(1));
		assertEquals("ok", result.value());
	}

	@Test
	void doesNotRetryWhenRetriesIsZero() {
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1), Instances.at(2)), 0, instance -> {
			attempted.add(instance.address());
			return StubInvocations.failed("boom");
		});

		assertThrows(CompletionException.class, () -> new FailoverCluster().invoke(invocation).join());
		assertEquals(1, attempted.size());
	}

	@Test
	void doesNotRetryWhenServerReturnedAnError() {
		// 服务端明确返回的错误装在 Result 里：已经执行过了，failover 不应重试（可能非幂等）
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1), Instances.at(2)), 3, instance -> {
			attempted.add(instance.address());
			return CompletableFuture.completedFuture(Result.exception(new IllegalStateException("business failure")));
		});

		Result result = new FailoverCluster().invoke(invocation).join();
		assertEquals(1, attempted.size());
		assertEquals("business failure", result.exception().getMessage());
	}

	@Test
	void exhaustsRetriesAndPropagatesLastFailure() {
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1), Instances.at(2)), 2,
				instance -> StubInvocations.failed("always fails: " + instance.address()));

		assertThrows(CompletionException.class, () -> new FailoverCluster().invoke(invocation).join());
	}
}
