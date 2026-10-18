package com.feynman.rpc.it;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.Deadline;
import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcContext;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.protocol.AttachmentKeys;
import com.feynman.rpc.it.support.Discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * deadline 向下传递：A → B → C 三个服务（同一 JVM 三个 RpcServer），A 调 B 的超时 500ms，B 内部调 C 的引用配置 5s。
 * <ul>
 *   <li>B 的同步业务方法里发起的调用继承剩余预算：C 收到的 {@code rpc.timeout} ≤ 500ms；</li>
 *   <li>B 用 {@code inheritDeadline(false)} 的引用：C 收到约 5000ms；</li>
 *   <li>B 的业务方法返回 future、在别的线程上发起下游调用：ThreadLocal 看不到，自动继承失效（C 收到约 5000ms，
 *       这是 javadoc 里写明的缺口）；用 {@code RpcContext.withDeadline(serverDeadline, ...)} 显式带过去后 ≤ 500ms；</li>
 *   <li>业务方法里 {@code RpcContext.serverDeadline()} 可读到剩余预算。</li>
 * </ul>
 */
class DeadlinePropagationTest {

	public interface ServiceC {
		/** 返回本次请求收到的 rpc.timeout（毫秒）。 */
		long receivedTimeout();
	}

	public interface ServiceB {
		long viaInheriting();

		long viaNonInheriting();

		CompletableFuture<Long> asyncWithoutPropagation();

		CompletableFuture<Long> asyncWithExplicitDeadline();

		long remainingBudgetMillis();
	}

	public interface ServiceA {
		long callB(String which);
	}

	static final class CImpl implements ServiceC {
		@Override
		public long receivedTimeout() {
			return Long.parseLong(RpcContext.getServerAttachment(AttachmentKeys.TIMEOUT));
		}
	}

	static final class BImpl implements ServiceB {
		private final ServiceC inheriting;
		private final ServiceC nonInheriting;

		BImpl(RpcClient client) {
			this.inheriting = client.reference(ServiceC.class, ReferenceOptions.defaults().timeout(Duration.ofSeconds(5)));
			this.nonInheriting = client.reference(ServiceC.class,
					ReferenceOptions.defaults().timeout(Duration.ofSeconds(5)).inheritDeadline(false));
		}

		@Override
		public long viaInheriting() {
			return inheriting.receivedTimeout();
		}

		@Override
		public long viaNonInheriting() {
			return nonInheriting.receivedTimeout();
		}

		@Override
		public CompletableFuture<Long> asyncWithoutPropagation() {
			// 业务方法返回后才在别的线程上发起下游调用：那个线程上没有入站上下文
			return CompletableFuture.supplyAsync(inheriting::receivedTimeout);
		}

		@Override
		public CompletableFuture<Long> asyncWithExplicitDeadline() {
			Deadline deadline = RpcContext.serverDeadline().orElse(null); // 在同步部分取出
			return CompletableFuture.supplyAsync(() -> RpcContext.withDeadline(deadline, inheriting::receivedTimeout));
		}

		@Override
		public long remainingBudgetMillis() {
			Optional<Deadline> deadline = RpcContext.serverDeadline();
			return deadline.map(d -> d.remaining().toMillis()).orElse(-1L);
		}
	}

	static final class AImpl implements ServiceA {
		private final ServiceB b;

		AImpl(RpcClient client) {
			this.b = client.reference(ServiceB.class, ReferenceOptions.defaults().timeout(Duration.ofMillis(500)));
		}

		@Override
		public long callB(String which) {
			return switch (which) {
				case "inherit" -> b.viaInheriting();
				case "no-inherit" -> b.viaNonInheriting();
				case "async" -> b.asyncWithoutPropagation().join();
				case "async-explicit" -> b.asyncWithExplicitDeadline().join();
				case "budget" -> b.remainingBudgetMillis();
				default -> throw new IllegalArgumentException(which);
			};
		}
	}

	private static ZkFixture zk;
	private static RpcServer serverA;
	private static RpcServer serverB;
	private static RpcServer serverC;
	private static RpcClient clientOfA;
	private static RpcClient clientOfB;
	private static RpcClient caller;
	private static ServiceA a;

	@BeforeAll
	static void start() throws Exception {
		zk = new ZkFixture();
		serverC = server();
		serverC.export(ServiceC.class, new CImpl());
		clientOfB = RpcClient.builder().registry(zk.registry()).build();
		serverB = server();
		serverB.export(ServiceB.class, new BImpl(clientOfB));
		clientOfA = RpcClient.builder().registry(zk.registry()).build();
		serverA = server();
		serverA.export(ServiceA.class, new AImpl(clientOfA));
		for (RpcServer server : new RpcServer[] { serverC, serverB, serverA }) {
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
		}
		caller = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(10)).build();
		a = caller.reference(ServiceA.class);
		// clientOfB / clientOfA 在 C / B 注册之前就已订阅：registered() 之后仍要等 watch 推送到达各自的提供者视图
		Discovery.awaitProviders("clientOfB", clientOfB, ServiceC.class);
		Discovery.awaitProviders("clientOfA", clientOfA, ServiceB.class);
		Discovery.awaitProviders("caller", caller, ServiceA.class);
		a.callB("inherit"); // 预热整条链路的连接
	}

	private static RpcServer server() {
		return RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).build();
	}

	@AfterAll
	static void stop() throws Exception {
		caller.close();
		serverA.close();
		clientOfA.close();
		serverB.close();
		clientOfB.close();
		serverC.close();
		zk.close();
	}

	@Test
	void nestedCallInheritsTheRemainingBudgetOfTheInboundRequest() {
		long inherited = a.callB("inherit");
		long notInherited = a.callB("no-inherit");
		System.out.printf("[deadline] A->B timeout 500ms, B->C reference timeout 5000ms: C received rpc.timeout=%dms (inherit), "
				+ "%dms (inheritDeadline=false)%n", inherited, notInherited);
		assertTrue(inherited > 0 && inherited <= 500, "C must see at most A's 500ms budget, got " + inherited);
		assertTrue(notInherited > 4000 && notInherited <= 5000, "inheritDeadline(false) keeps the reference's own 5s, got " + notInherited);
	}

	@Test
	void serverDeadlineIsReadableInTheBusinessMethod() {
		long budget = a.callB("budget");
		System.out.println("[deadline] B saw a remaining budget of " + budget + "ms");
		assertTrue(budget > 0 && budget <= 500, String.valueOf(budget));
	}

	@Test
	void asyncHopNeedsTheExplicitApi() {
		long lost = a.callB("async");
		long explicit = a.callB("async-explicit");
		System.out.printf("[deadline] call issued on another thread after B returned a future: rpc.timeout=%dms without "
				+ "withDeadline, %dms with RpcContext.withDeadline(serverDeadline, ...)%n", lost, explicit);
		assertTrue(lost > 4000, "documented gap: automatic inheritance cannot cross threads, got " + lost);
		assertTrue(explicit > 0 && explicit <= 500, "withDeadline carries the budget across threads, got " + explicit);
		assertEquals(-1L, RpcContext.serverDeadline().map(d -> 0L).orElse(-1L), "nothing is bound on the test thread");
	}
}
