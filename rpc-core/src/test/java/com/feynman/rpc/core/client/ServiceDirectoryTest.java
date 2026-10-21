package com.feynman.rpc.core.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.registry.ServiceInstance;
import com.feynman.rpc.core.testutil.Instances;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceDirectoryTest {

	@Test
	void emptyPushIsIgnoredWhenProtectionIsOnAndListWasNonEmpty() {
		AtomicInteger changes = new AtomicInteger();
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> changes.incrementAndGet());

		directory.onInstancesChanged(List.of(Instances.at(1), Instances.at(2)));
		directory.onInstancesChanged(List.of());

		assertEquals(2, directory.instances().size(), "empty protection must keep the previous providers");
		assertEquals(1, changes.get(), "an ignored empty push must not trigger connection changes");
	}

	@Test
	void emptyPushIsAppliedWhenProtectionIsOff() {
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, false, d -> {
		});

		directory.onInstancesChanged(List.of(Instances.at(1)));
		directory.onInstancesChanged(List.of());

		assertTrue(directory.instances().isEmpty());
	}

	@Test
	void initialEmptyPushIsAcceptedEvenWithProtection() {
		// 一开始就没有提供者不是"抖动"，没有旧列表可保留
		AtomicInteger changes = new AtomicInteger();
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> changes.incrementAndGet());

		directory.onInstancesChanged(List.of());

		assertTrue(directory.instances().isEmpty());
		assertEquals(1, changes.get());
	}

	@Test
	void nonEmptyUpdatesReplaceTheList() {
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> {
		});

		directory.onInstancesChanged(List.of(Instances.at(1), Instances.at(2)));
		directory.onInstancesChanged(List.of(Instances.at(3)));

		assertEquals(List.of(Instances.at(3)), directory.instances());
	}

	@Test
	void awaitProvidersIsWokenByTheUpdateThatMakesTheListNonEmpty() throws Exception {
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> {
		});
		directory.onInstancesChanged(List.of());
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Thread waiter = Thread.ofPlatform().start(() -> {
			try {
				result.complete(directory.awaitProviders(Duration.ofSeconds(30)));
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});
		await().atMost(5, TimeUnit.SECONDS).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);

		directory.onInstancesChanged(List.of(Instances.at(1)));

		assertTrue(result.get(2, TimeUnit.SECONDS), "the push must wake the waiter well before its 30s budget");
	}

	/**
	 * 先建连接池、再唤醒：onChange（RpcClient 在里面建连接池）故意阻塞 200ms，等待者被唤醒的时刻必须不早于 onChange 返回。
	 * 反过来的顺序下，醒来的调用方会抢在连接池建好之前发起调用（"No connection pool for: ..."）。
	 */
	@Test
	void awaitProvidersWakesOnlyAfterOnChangeHasBuiltTheConnectionPools() throws Exception {
		java.util.concurrent.atomic.AtomicLong onChangeReturnedAt = new java.util.concurrent.atomic.AtomicLong();
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> {
			// onChange 按连接目标建池；路由视图（instances）此时还没发布，见 newProvidersAreRoutableOnlyAfterTheirConnectionPoolsArePrepared
			if (d.connectionTargets().isEmpty()) {
				return;
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			onChangeReturnedAt.set(System.nanoTime());
		});
		directory.onInstancesChanged(List.of());
		CompletableFuture<Long> wokenAt = new CompletableFuture<>();
		Thread waiter = Thread.ofPlatform().start(() -> {
			try {
				assertTrue(directory.awaitProviders(Duration.ofSeconds(30)));
				wokenAt.complete(System.nanoTime());
			} catch (Throwable t) {
				wokenAt.completeExceptionally(t);
			}
		});
		await().atMost(5, TimeUnit.SECONDS).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);

		directory.onInstancesChanged(List.of(Instances.at(1)));

		long woken = wokenAt.get(5, TimeUnit.SECONDS);
		assertTrue(onChangeReturnedAt.get() != 0, "onChange ran for the non-empty update");
		assertTrue(woken - onChangeReturnedAt.get() >= 0, "the waiter woke " + (onChangeReturnedAt.get() - woken) / 1_000_000
				+ "ms before onChange (connection pools) finished");
	}

	@Test
	void awaitProvidersTimesOutWhileEmptyAndReturnsAtOnceWhenNonEmpty() throws Exception {
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> {
		});
		long start = System.nanoTime();
		assertFalse(directory.awaitProviders(Duration.ofMillis(100)));
		assertTrue(System.nanoTime() - start >= Duration.ofMillis(100).toNanos());

		directory.onInstancesChanged(List.of(Instances.at(1)));
		assertTrue(directory.awaitProviders(Duration.ZERO), "a non-empty list needs no waiting, even with a zero budget");
	}

	/**
	 * 回归（任务 13b）：路由视图（instances）只在 onChange（RpcClient 在里面按 connectionTargets 建连接池）返回之后才发布。
	 * 以前先发布、后建池：onChange 执行期间读者已经看到新地址，调用在 ConnectionManager.acquire 上失败 "No connection pool"。
	 */
	@Test
	void newProvidersAreRoutableOnlyAfterTheirConnectionPoolsArePrepared() {
		List<List<ServiceInstance>> routableDuringOnChange = new java.util.ArrayList<>();
		List<List<ServiceInstance>> targetsDuringOnChange = new java.util.ArrayList<>();
		ServiceDirectory directory = new ServiceDirectory(Instances.KEY, true, d -> {
			routableDuringOnChange.add(d.instances());
			targetsDuringOnChange.add(d.connectionTargets());
		});
		directory.onInstancesChanged(List.of(Instances.at(1)));
		assertEquals(List.of(List.of()), routableDuringOnChange, "while pools are being prepared the new provider must not be routable yet");
		assertEquals(List.of(List.of(Instances.at(1))), targetsDuringOnChange, "onChange must already see the new provider as a target");
		assertEquals(List.of(Instances.at(1)), directory.instances());

		// 加一个：一次 onChange，期间路由视图仍是旧列表
		routableDuringOnChange.clear();
		targetsDuringOnChange.clear();
		directory.onInstancesChanged(List.of(Instances.at(1), Instances.at(2)));
		assertEquals(List.of(List.of(Instances.at(1))), routableDuringOnChange);
		assertEquals(List.of(List.of(Instances.at(1), Instances.at(2))), targetsDuringOnChange);
	}

	/** 有地址下线：第一次 onChange 时它仍可路由、池仍在目标里；发布后第二次 onChange 才把它移出目标（排空）。 */
	@Test
	void removedProvidersLeaveTheRoutingViewBeforeTheirPoolsAreDrained() {
		List<List<ServiceInstance>> routableDuringOnChange = new java.util.ArrayList<>();
		List<List<ServiceInstance>> targetsDuringOnChange = new java.util.ArrayList<>();
		ServiceDirectory replaced = new ServiceDirectory(Instances.KEY, true, d -> {
			routableDuringOnChange.add(d.instances());
			targetsDuringOnChange.add(d.connectionTargets());
		});
		replaced.onInstancesChanged(List.of(Instances.at(1), Instances.at(2)));
		routableDuringOnChange.clear();
		targetsDuringOnChange.clear();

		replaced.onInstancesChanged(List.of(Instances.at(2), Instances.at(3)));
		assertEquals(List.of(List.of(Instances.at(1), Instances.at(2)), List.of(Instances.at(2), Instances.at(3))), routableDuringOnChange);
		assertEquals(List.of(List.of(Instances.at(1), Instances.at(2), Instances.at(3)), List.of(Instances.at(2), Instances.at(3))),
				targetsDuringOnChange, "union first (new pool built, old kept), then narrowed after publishing");
		assertEquals(List.of(Instances.at(2), Instances.at(3)), replaced.connectionTargets());
	}
}
