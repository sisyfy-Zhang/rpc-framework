package com.feynman.rpc.core.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.OutlierDetectionConfig;
import com.feynman.rpc.core.filter.Result;
import com.feynman.rpc.core.loadbalance.LoadBalance;
import com.feynman.rpc.core.loadbalance.LoadStats;
import com.feynman.rpc.core.loadbalance.P2cLoadBalance;
import com.feynman.rpc.core.loadbalance.RoundRobinLoadBalance;
import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.testutil.Instances;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离群摘除在 Cluster 一侧的接线：候选在交给 LoadBalance 之前已去掉被摘地址（failfast / failover / P2C），
 * failover 的"排除本次调用失败过的节点"在过滤后的候选之上照常生效。
 */
class OutlierClusterTest {

	private static final ServiceInstance A = Instances.at(1);
	private static final ServiceInstance B = Instances.at(2);
	private static final ServiceInstance C = Instances.at(3);
	private static final List<ServiceInstance> ALL = List.of(A, B, C);

	private final AtomicLong clock = new AtomicLong(1_000_000_000_000L);

	/** A 已被摘除的检测器。 */
	private OutlierDetector detectorWithAEjected() {
		OutlierDetector detector = new OutlierDetector(OutlierDetectionConfig.builder().consecutiveFailures(1).build(), "test", () -> ALL,
				new OutlierDetector.Stats(), clock::get);
		detector.onFailure(A.address(), OutlierDetector.TRANSPORT);
		assertTrue(detector.ejectedNodes().containsKey(A.address()));
		return detector;
	}

	/** 记下每次交给它的候选列表。 */
	private static final class RecordingLoadBalance implements LoadBalance {
		final LoadBalance delegate;
		final List<List<String>> seen = Collections.synchronizedList(new ArrayList<>());

		RecordingLoadBalance(LoadBalance delegate) {
			this.delegate = delegate;
		}

		@Override
		public ServiceInstance select(List<ServiceInstance> candidates, LoadStats stats) {
			seen.add(candidates.stream().map(ServiceInstance::address).toList());
			return delegate.select(candidates, stats);
		}
	}

	private static ClusterInvocation invocation(LoadBalance lb, OutlierDetector detector, int retries, List<String> attempted, boolean fail) {
		return new ClusterInvocation(() -> ALL, lb, new LoadStats(), instance -> {
			attempted.add(instance.address());
			return fail ? StubInvocations.failed("down: " + instance.address()) : CompletableFuture.completedFuture(Result.value("ok"));
		}, retries, "test", detector);
	}

	@Test
	void failfastNeverPicksAnEjectedNode() {
		OutlierDetector detector = detectorWithAEjected();
		RecordingLoadBalance lb = new RecordingLoadBalance(new RoundRobinLoadBalance());
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		for (int i = 0; i < 30; i++) {
			new FailfastCluster().invoke(invocation(lb, detector, 0, attempted, false)).join();
		}
		assertFalse(attempted.contains(A.address()), attempted.toString());
		assertTrue(attempted.contains(B.address()) && attempted.contains(C.address()));
		lb.seen.forEach(candidates -> assertEquals(List.of(B.address(), C.address()), candidates));
	}

	@Test
	void failoverExcludesFailedNodesOnTopOfTheFilteredCandidates() {
		OutlierDetector detector = detectorWithAEjected();
		RecordingLoadBalance lb = new RecordingLoadBalance(new RoundRobinLoadBalance());
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		// 每次尝试都失败、允许重试 3 次：B、C 各试一次之后候选被排除光，退回过滤后的全集（B、C），A 始终不出现
		assertThrows(CompletionException.class, () -> new FailoverCluster().invoke(invocation(lb, detector, 3, attempted, true)).join());
		assertEquals(4, attempted.size(), attempted.toString());
		assertFalse(attempted.contains(A.address()), attempted.toString());
		assertTrue(!attempted.get(0).equals(attempted.get(1)), "the retry switched to the other healthy node: " + attempted);
		assertEquals(List.of(B.address(), C.address()), lb.seen.get(0));
		assertEquals(1, lb.seen.get(1).size(), "second attempt: the node that just failed is excluded: " + lb.seen);
	}

	@Test
	void failoverWithoutDetectorStillSeesEveryNode() {
		RecordingLoadBalance lb = new RecordingLoadBalance(new RoundRobinLoadBalance());
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		new FailoverCluster().invoke(invocation(lb, null, 0, attempted, false)).join();
		assertEquals(List.of(A.address(), B.address(), C.address()), lb.seen.get(0));
	}

	@Test
	void p2cOnlySeesFilteredCandidates() {
		OutlierDetector detector = detectorWithAEjected();
		RecordingLoadBalance lb = new RecordingLoadBalance(new P2cLoadBalance());
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		for (int i = 0; i < 200; i++) {
			new FailfastCluster().invoke(invocation(lb, detector, 0, attempted, false)).join();
		}
		assertFalse(attempted.contains(A.address()));
		lb.seen.forEach(candidates -> assertEquals(List.of(B.address(), C.address()), candidates));
	}

	@Test
	void ejectedNodeReturnsAfterExpiry() {
		OutlierDetector detector = detectorWithAEjected();
		RecordingLoadBalance lb = new RecordingLoadBalance(new RoundRobinLoadBalance());
		List<String> attempted = Collections.synchronizedList(new ArrayList<>());
		clock.addAndGet(OutlierDetectionConfig.DEFAULT_BASE_EJECTION.toNanos());
		for (int i = 0; i < 3; i++) {
			new FailfastCluster().invoke(invocation(lb, detector, 0, attempted, false)).join();
		}
		assertTrue(attempted.contains(A.address()), attempted.toString());
	}
}
