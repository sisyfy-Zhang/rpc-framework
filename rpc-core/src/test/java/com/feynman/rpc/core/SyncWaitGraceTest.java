package com.feynman.rpc.core;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.config.RpcClientConfig;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.testutil.InMemoryRegistryFactory;
import com.feynman.rpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步调用的第二道上界（{@code RpcClientConfig.syncWaitGrace}）。
 *
 * <p>"deadline 定时器没有按时触发"的模拟方式：在途表的定时器是 {@link CompletableFuture#orTimeout}，由 JDK 进程级唯一的
 * 延迟调度线程（CompletableFutureDelayScheduler）执行。往它上面放一个直接在该线程上运行、阻塞在闩上的任务，
 * 它就执行不了任何 orTimeout——与现场"超时到了、future 却没有被完成"的效果相同，而且不需要在生产代码里留注入点。
 * 阻塞只持续一个测试方法，finally 里放开；放开后积压的超时任务对已完成的 future 是空操作。
 */
class SyncWaitGraceTest {

	private static final String GRACE_MESSAGE = "deadline timer did not fire within grace";

	public interface Hold {
		/** {@code millis} 毫秒后返回；{@code millis < 0} 时一直等到测试放开（模拟"服务端不回包"）。 */
		String hold(Integer millis);
	}

	static final class HoldImpl implements Hold {
		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public String hold(Integer millis) {
			try {
				if (millis < 0) {
					release.await(10, TimeUnit.SECONDS);
				} else {
					Thread.sleep(millis);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "held:" + millis;
		}
	}

	/** 让 JDK 的延迟调度线程卡住，直到 close；返回时它已经卡在闩上。 */
	private static AutoCloseable stallDeadlineTimer() throws InterruptedException {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS, Runnable::run).execute(() -> {
			entered.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(entered.await(5, TimeUnit.SECONDS), "delay scheduler did not pick up the stall task");
		return release::countDown;
	}

	private static RpcServer server(String ns) {
		return RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).executor(ExecutorConfig.fixed(4)).build();
	}

	private static RpcClient client(String ns, Duration timeout, Duration grace) {
		return RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns))
				.defaultTimeout(timeout).syncWaitGrace(grace).build();
	}

	@Test
	void syncCallGivesUpAfterDeadlinePlusGraceWhenTheTimerNeverFires() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		HoldImpl impl = new HoldImpl();
		try (RpcServer server = server(ns)) {
			server.export(Hold.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			// grace 300ms：与缺省 1s 拉开距离，下面的耗时上界同时验证"配置的值真的生效"
			try (RpcClient client = client(ns, Duration.ofMillis(200), Duration.ofMillis(300))) {
				Hold hold = client.reference(Hold.class);
				assertEquals("held:0", hold.hold(0)); // 预热连接：卡住定时器之后不再依赖任何 JDK 定时任务

				RpcDeadlineExceededException e;
				long elapsedMillis;
				try (AutoCloseable stalled = stallDeadlineTimer()) {
					long start = System.nanoTime();
					e = assertThrows(RpcDeadlineExceededException.class, () -> hold.hold(-1));
					elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
					// 在途表、在途闸门在定时器仍然卡住的时候就已经释放（不是等定时器恢复后才清掉）
					assertEquals(0, client.pendingRequests(), "the abandoned request is removed from the pending table");
					assertEquals(0, client.inFlightCalls(), "the abandoned call leaves the in-flight gate");
				}
				assertTrue(e.getMessage().contains(GRACE_MESSAGE), e.getMessage());
				assertEquals(com.feynman.rpc.core.protocol.Status.DEADLINE_EXCEEDED, e.status());
				assertTrue(elapsedMillis >= 480, "gave up after " + elapsedMillis + "ms, before deadline 200ms + grace 300ms");
				assertTrue(elapsedMillis < 1150, "gave up after " + elapsedMillis + "ms: configured grace 300ms not applied "
						+ "(default grace would be " + RpcClientConfig.DEFAULT_SYNC_WAIT_GRACE.toMillis() + "ms)");

				impl.release.countDown();
				// 定时器恢复后一切照常：正常调用成功，正常超时仍由定时器给出（不是 grace 的消息）
				assertEquals("held:5", hold.hold(5));
				RpcDeadlineExceededException normal = assertThrows(RpcDeadlineExceededException.class, () -> hold.hold(1000));
				assertFalse(normal.getMessage().contains(GRACE_MESSAGE), normal.getMessage());
				assertTrue(normal.getMessage().contains("no response within"), normal.getMessage());
				await().atMost(5, TimeUnit.SECONDS).until(() -> client.pendingRequests() == 0 && client.inFlightCalls() == 0);
			}
		} finally {
			impl.release.countDown();
		}
	}

	@Test
	void virtualThreadCallerIsReleasedTooAndTheInterruptFlagSurvives() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		HoldImpl impl = new HoldImpl();
		try (RpcServer server = server(ns)) {
			server.export(Hold.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			try (RpcClient client = client(ns, Duration.ofMillis(100), Duration.ofMillis(200))) {
				Hold hold = client.reference(Hold.class);
				assertEquals("held:0", hold.hold(0));

				AtomicReference<Throwable> failure = new AtomicReference<>();
				AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
				try (AutoCloseable stalled = stallDeadlineTimer()) {
					// 现场挂住的是压测的虚拟线程：同样的等待在虚拟线程上也必须有上界；等待期间的中断不打断等待（与 join 一致），标志保留
					Thread caller = Thread.ofVirtual().start(() -> {
						Thread.currentThread().interrupt();
						try {
							hold.hold(-1);
						} catch (Throwable t) {
							failure.set(t);
						}
						interruptedAfter.set(Thread.currentThread().isInterrupted());
					});
					assertTrue(caller.join(Duration.ofSeconds(5)), "virtual-thread caller still blocked after 5s");
				}
				assertInstanceOf(RpcDeadlineExceededException.class, failure.get());
				assertTrue(failure.get().getMessage().contains(GRACE_MESSAGE), failure.get().getMessage());
				assertEquals(Boolean.TRUE, interruptedAfter.get(), "interrupt flag is restored");
				impl.release.countDown();
				assertEquals("held:1", hold.hold(1));
				await().atMost(5, TimeUnit.SECONDS).until(() -> client.pendingRequests() == 0 && client.inFlightCalls() == 0);
			}
		} finally {
			impl.release.countDown();
		}
	}

	@Test
	void graceIsConfigurableWithASaneDefault() {
		RegistryConfig registry = InMemoryRegistryFactory.config("ns-" + UUID.randomUUID());
		assertEquals(Duration.ofSeconds(1), RpcClientConfig.DEFAULT_SYNC_WAIT_GRACE);
		assertEquals(RpcClientConfig.DEFAULT_SYNC_WAIT_GRACE, RpcClientConfig.builder().registry(registry).build().syncWaitGrace());
		assertEquals(RpcClientConfig.DEFAULT_SYNC_WAIT_GRACE,
				RpcClientConfig.builder().registry(registry).syncWaitGrace(null).build().syncWaitGrace());
		assertEquals(Duration.ofMillis(250),
				RpcClientConfig.builder().registry(registry).syncWaitGrace(Duration.ofMillis(250)).build().syncWaitGrace());
		assertEquals(Duration.ZERO, RpcClientConfig.builder().registry(registry).syncWaitGrace(Duration.ZERO).build().syncWaitGrace());
		assertThrows(IllegalArgumentException.class,
				() -> RpcClientConfig.builder().registry(registry).syncWaitGrace(Duration.ofMillis(-1)).build());
	}
}
