package io.github.sisyfyzhang.steadyrpc.core.testing;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcLeakCheckExtension} 必须真的能抓到泄漏（阳性对照），否则"全部测试类零泄漏"没有意义：
 * <ul>
 *   <li>一个启动后未关闭的 RpcServer：线程检查在宽限期后报告它的 rpc- 线程；关闭后报告为空；</li>
 *   <li>测试类静态字段持有在途不为零的 RpcClient / RpcServer：在途检查报告两者；在途归零后不再报告。</li>
 * </ul>
 * 端到端的证据（一个真的不 close 的测试类在扩展下失败）见 {@link LeakFixtureTest}，它默认 {@code @Disabled}。
 */
class RpcLeakCheckExtensionSelfTest {

	public interface Gate {
		String pass();
	}

	static final class GateImpl implements Gate {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		@Override
		public String pass() {
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "passed";
		}
	}

	/** 模拟"测试类暴露的字段"。 */
	static class Holder {
		static RpcClient client;
		static RpcServer server;
	}

	@Test
	void reportsThreadsOfAnUnclosedServerAndNothingAfterClose() throws Exception {
		assertTrue(RpcLeakCheckExtension.rpcThreads().isEmpty(), "precondition: no rpc- threads from earlier tests");
		RpcServer leaked = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").build();
		try {
			leaked.export(Gate.class, new GateImpl());
			leaked.start();
			Map<String, String> found = RpcLeakCheckExtension.awaitNoRpcThreads(Duration.ofMillis(300));
			assertFalse(found.isEmpty(), "an unclosed RpcServer must be reported");
			assertTrue(found.keySet().stream().anyMatch(name -> name.startsWith("rpc-server-")), found.toString());
		} finally {
			leaked.close();
		}
		assertTrue(RpcLeakCheckExtension.awaitNoRpcThreads(RpcLeakCheckExtension.DEFAULT_GRACE).isEmpty(),
				"closing the server must make the check pass");
	}

	@Test
	void reportsInFlightCallsHeldByTestClassFields() throws Exception {
		String ns = "leak-" + UUID.randomUUID();
		GateImpl impl = new GateImpl();
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").registry(InMemoryRegistryFactory.config(ns)).build()) {
			server.export(Gate.class, impl);
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join();
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				Holder.client = client;
				Holder.server = server;
				Gate gate = client.reference(Gate.class);
				CompletableFuture<String> call = CompletableFuture.supplyAsync(gate::pass);
				assertTrue(impl.entered.await(5, TimeUnit.SECONDS));

				List<String> problems = RpcLeakCheckExtension.inFlightProblems(Holder.class, null);
				assertEquals(2, problems.size(), problems.toString());
				assertTrue(problems.stream().anyMatch(p -> p.contains("Holder.client: RpcClient has 1 in-flight call(s)")), problems.toString());
				assertTrue(problems.stream().anyMatch(p -> p.contains("Holder.server: RpcServer has 1 in-flight request(s)")), problems.toString());

				impl.release.countDown();
				assertEquals("passed", call.get(5, TimeUnit.SECONDS));
				await().atMost(2, TimeUnit.SECONDS).until(() -> RpcLeakCheckExtension.inFlightProblems(Holder.class, null).isEmpty());
			} finally {
				Holder.client = null;
				Holder.server = null;
			}
		}
	}
}
