package com.feynman.rpc.core.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailoverClusterTest {

	@Test
	void retriesOnADifferentAddressAndSucceeds() {
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return Map.of("a", 1, "b", 1);
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				attempted.add(address);
				CompletableFuture<RpcMessage> f = new CompletableFuture<>();
				if (attempted.size() == 1) {
					f.completeExceptionally(new RuntimeException("first node is down"));
				} else {
					f.complete(new RpcMessage(1, MessageType.RESPONSE, (byte) 1, Status.OK, new byte[0]));
				}
				return f;
			}

			@Override
			public int maxRetries() {
				return 1;
			}

			@Override
			public String describe() {
				return "test";
			}
		};

		RpcMessage result = new FailoverCluster().invoke(invocation).join();
		assertEquals(2, attempted.size());
		assertNotEquals(attempted.get(0), attempted.get(1));
		assertEquals(Status.OK, result.getStatus());
	}

	@Test
	void doesNotRetryWhenMaxRetriesIsZero() {
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return Map.of("a", 1, "b", 1);
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				attempted.add(address);
				CompletableFuture<RpcMessage> f = new CompletableFuture<>();
				f.completeExceptionally(new RuntimeException("boom"));
				return f;
			}

			@Override
			public int maxRetries() {
				return 0; // 默认值，未显式声明 @RemoteInvoke(retries=...) 时应有的行为
			}

			@Override
			public String describe() {
				return "test";
			}
		};

		assertThrows(CompletionException.class, () -> new FailoverCluster().invoke(invocation).join());
		assertEquals(1, attempted.size());
	}

	@Test
	void exhaustsRetriesAndPropagatesLastFailure() {
		ClusterInvocation invocation = new ClusterInvocation() {
			@Override
			public Map<String, Integer> addressWeights() {
				return Map.of("a", 1, "b", 1);
			}

			@Override
			public CompletableFuture<RpcMessage> attempt(String address) {
				CompletableFuture<RpcMessage> f = new CompletableFuture<>();
				f.completeExceptionally(new RuntimeException("always fails: " + address));
				return f;
			}

			@Override
			public int maxRetries() {
				return 2;
			}

			@Override
			public String describe() {
				return "test";
			}
		};

		assertThrows(CompletionException.class, () -> new FailoverCluster().invoke(invocation).join());
	}
}
