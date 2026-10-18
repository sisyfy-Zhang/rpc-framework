package com.feynman.rpc.core.cluster;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.testutil.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailfastClusterTest {

	@Test
	void singleAttemptOnly_evenIfRetriesConfiguredHigh() {
		AtomicInteger attempts = new AtomicInteger();
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1), Instances.at(2)), 5, instance -> {
			attempts.incrementAndGet();
			return StubInvocations.failed("boom"); // Failfast 必须完全无视 retries
		});

		assertThrows(CompletionException.class, () -> new FailfastCluster().invoke(invocation).join());
		assertEquals(1, attempts.get());
	}

	@Test
	void propagatesSuccessUnchanged() {
		Result expected = Result.value("ok");
		ClusterInvocation invocation = StubInvocations.of(List.of(Instances.at(1)), 0,
				instance -> CompletableFuture.completedFuture(expected));

		assertSame(expected, new FailfastCluster().invoke(invocation).join());
	}

	@Test
	void noProviderFailsWithRpcException() {
		ClusterInvocation invocation = StubInvocations.of(List.of(), 0, instance -> {
			throw new AssertionError("must not attempt without a provider");
		});

		CompletionException e = assertThrows(CompletionException.class, () -> new FailfastCluster().invoke(invocation).join());
		assertInstanceOf(RpcException.class, e.getCause());
	}
}
