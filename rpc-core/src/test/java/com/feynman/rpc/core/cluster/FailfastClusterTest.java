package com.feynman.rpc.core.cluster;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailfastClusterTest {

	@Test
	void singleAttemptOnly_evenIfRetriesConfiguredHigh() {
		AtomicInteger attempts = new AtomicInteger();
		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return Map.of("a", 1, "b", 1);
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				attempts.incrementAndGet();
				CompletableFuture<RpcMessage> f = new CompletableFuture<>();
				f.completeExceptionally(new RuntimeException("boom"));
				return f;
			}

			@Override
			public int maxRetries() {
				return 5; // Failfast 必须完全无视这个值
			}

			@Override
			public String describe() {
				return "test";
			}
		};

		assertThrows(CompletionException.class, () -> new FailfastCluster().invoke(invocation).join());
		assertEquals(1, attempts.get());
	}

	@Test
	void propagatesSuccessUnchanged() {
		RpcMessage expected = new RpcMessage(1, MessageType.RESPONSE, (byte) 1, Status.OK, new byte[0]);
		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return Map.of("a", 1);
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				return CompletableFuture.completedFuture(expected);
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

		assertEquals(expected, new FailfastCluster().invoke(invocation).join());
	}
}
