package com.feynman.rpc.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcOverloadedException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ExecutorConfig;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.config.ServiceOptions;
import com.feynman.rpc.core.protocol.Status;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界执行器与过载拒绝：
 * <ul>
 *   <li>A = {@code fixed(1, queueCapacity=1)}：占住唯一线程 + 塞满队列后，第三个请求收到 RpcOverloadedException
 *       （业务没执行）；A = {@code virtualThreads(maxConcurrent=2)}：两个在执行时第三个被拒；</li>
 *   <li>两个 server（A 过载、B 正常）+ failover：retries=0 时打到 A 的调用直接抛 RpcOverloadedException；
 *       retries=1 时这些调用转移到 B 成功，且全部由 B 的实现执行、A 的实现一次都没执行（过载节点被排除）。</li>
 * </ul>
 * 占位调用走只有 A 暴露的 group "pin-a"，保证它们落在 A 上；被测调用走两台都暴露的 default group。
 */
class OverloadProtectionTest {

	public interface Work {
		String hold(int millis);

		String who();
	}

	static final class WorkImpl implements Work {
		private final String name;
		final AtomicInteger whoCalls = new AtomicInteger();

		WorkImpl(String name) {
			this.name = name;
		}

		@Override
		public String hold(int millis) {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return name + ":held";
		}

		@Override
		public String who() {
			whoCalls.incrementAndGet();
			return name;
		}
	}

	static Stream<Arguments> saturatedExecutors() {
		return Stream.of(
				Arguments.of("fixed(1, queueCapacity=1)", ExecutorConfig.fixed(1, 1)),
				Arguments.of("virtualThreads(maxConcurrent=2)", ExecutorConfig.virtualThreads(2)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("saturatedExecutors")
	void saturatedNodeRejectsAndFailoverMovesTheCallToAnotherNode(String name, ExecutorConfig executor) throws Exception {
		try (ZkFixture zk = new ZkFixture();
				RpcServer a = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).executor(executor).build();
				RpcServer b = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).executor(ExecutorConfig.fixed(4)).build()) {
			WorkImpl implA = new WorkImpl("A");
			WorkImpl implB = new WorkImpl("B");
			a.export(Work.class, implA);
			a.export(Work.class, implA, ServiceOptions.defaults().group("pin-a"));
			b.export(Work.class, implB);
			a.start();
			b.start();
			a.registered().orTimeout(10, TimeUnit.SECONDS).join();
			b.registered().orTimeout(10, TimeUnit.SECONDS).join();

			try (RpcClient client = RpcClient.builder().registry(zk.registry()).cluster("failover")
					.defaultTimeout(Duration.ofSeconds(10)).build()) {
				Work pinA = client.reference(Work.class, ReferenceOptions.defaults().group("pin-a"));
				Work noRetry = client.reference(Work.class);
				Work failover = client.reference(Work.class, ReferenceOptions.defaults().retries(1));
				assertEquals("A", pinA.who()); // 预热到 A 的连接
				await().atMost(5, TimeUnit.SECONDS).until(() -> client.providers(com.feynman.rpc.core.ServiceKey.of(Work.class)).size() == 2);
				implA.whoCalls.set(0);

				// 占满 A：fixed(1,1) 是 1 个执行 + 1 个排队；virtualThreads(2) 是 2 个执行。
				// 逐个发出并等前一个真正开始执行：两个请求同时到达时，第一个可能还躺在队列里没被工作线程取走，
				// 容量 1 的队列会直接拒绝第二个（ThreadPoolExecutor 经队列交接任务的固有时序）
				List<CompletableFuture<String>> holders = new ArrayList<>();
				holders.add(CompletableFuture.supplyAsync(() -> pinA.hold(1500)));
				await("first holder running").atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(5))
						.until(() -> a.runningBusinessTasks() == 1);
				holders.add(CompletableFuture.supplyAsync(() -> pinA.hold(1500)));
				await("A saturated").atMost(5, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(5)).until(() -> a.inFlight() == 2);

				RpcOverloadedException direct = assertThrows(RpcOverloadedException.class, pinA::who);
				System.out.printf("[overload %s] third request to A: %s%n", name, direct.getMessage());
				assertTrue(direct.getMessage().contains("server overloaded"), direct.getMessage());
				assertEquals(Status.SERVER_OVERLOADED, direct.status());

				// retries=0：轮询打到 A 的那些调用直接失败
				int rejected = 0;
				for (int i = 0; i < 4; i++) {
					try {
						assertEquals("B", noRetry.who());
					} catch (RpcOverloadedException e) {
						rejected++;
					}
				}
				// retries=1：同样的调用全部转移到 B
				List<String> servedBy = new ArrayList<>();
				for (int i = 0; i < 4; i++) {
					servedBy.add(failover.who());
				}
				System.out.printf("[overload %s] retries=0: %d of 4 calls rejected by A; retries=1: served by %s; A.who executed %d times%n",
						name, rejected, servedBy, implA.whoCalls.get());
				assertTrue(rejected >= 1, "round robin must have sent some calls to the saturated node");
				assertEquals(List.of("B", "B", "B", "B"), servedBy, "failover must move rejected calls to B");
				assertEquals(0, implA.whoCalls.get(), "a rejected request is never executed on the overloaded node");

				for (CompletableFuture<String> holder : holders) {
					assertEquals("A:held", holder.get(10, TimeUnit.SECONDS));
				}
				assertEquals("A", pinA.who(), "A serves again once it is no longer saturated");
				assertEquals(0, client.pendingRequests());
			}
		}
	}
}
