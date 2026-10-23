package io.github.sisyfyzhang.steadyrpc.core;

import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcClient#retryAttempts()} / {@link RpcClient#retryNodeSwitches()} / {@link RpcClient#nodeCallStats()}：
 * 一个活节点 + 一个没人监听的地址（连接被拒 = 传输失败）。failover 下打到死节点的尝试换到活节点重试并成功；
 * failfast 下不重试、失败原样传播。按节点的计数分清"尝试"与"失败"。
 */
class RpcClientFaultCountersTest {

	public interface Echo {
		String echo(String text);
	}

	private static int deadPort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private record Topology(String ns, RpcServer server, Registry fake, String liveAddress, String deadAddress) implements AutoCloseable {
		@Override
		public void close() {
			fake.close();
			server.close();
		}
	}

	private static Topology topology() throws Exception {
		String ns = "ns-" + UUID.randomUUID();
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns)).executor(ExecutorConfig.fixed(2)).build();
		server.export(Echo.class, text -> text);
		server.start();
		server.registered().get();
		int dead = deadPort();
		Registry fake = new InMemoryRegistryFactory().create(InMemoryRegistryFactory.config(ns));
		fake.register(new ServiceInstance(ServiceKey.of(Echo.class), "127.0.0.1", dead, Map.of(InstanceMetadata.WEIGHT, "1")));
		return new Topology(ns, server, fake, "127.0.0.1:" + server.port(), "127.0.0.1:" + dead);
	}

	@Test
	void failoverRetriesOnAnotherNodeAndCountsPerNode() throws Exception {
		try (Topology t = topology();
				RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(t.ns()))
						.cluster("failover").loadBalance("roundrobin").build()) {
			Echo echo = client.reference(Echo.class, ReferenceOptions.defaults().retries(1));
			assertEquals(2, client.providers(ServiceKey.of(Echo.class)).size());
			for (int i = 0; i < 20; i++) {
				assertEquals("x" + i, echo.echo("x" + i));
			}
			Map<String, RpcClient.NodeCallStats> stats = client.nodeCallStats();
			RpcClient.NodeCallStats dead = stats.get(t.deadAddress());
			RpcClient.NodeCallStats live = stats.get(t.liveAddress());
			assertTrue(dead.attempts() > 0, "round robin sends some first attempts to the dead node: " + stats);
			assertEquals(dead.attempts(), dead.failures(), "every attempt on the dead node is a transport failure: " + stats);
			assertEquals(0, live.failures());
			assertEquals(0, live.timeouts());
			// 每次死节点上的失败都换到活节点重试：重试数 = 换节点数 = 死节点的失败数；活节点的尝试 = 20 次调用里全部最终成功的那一次
			assertEquals(dead.failures(), client.retryAttempts());
			assertEquals(client.retryAttempts(), client.retryNodeSwitches());
			assertEquals(20, live.attempts());
		}
	}

	@Test
	void failfastNeverRetries() throws Exception {
		try (Topology t = topology();
				RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(t.ns()))
						.cluster("failfast").loadBalance("roundrobin").build()) {
			Echo echo = client.reference(Echo.class, ReferenceOptions.defaults().retries(3));
			int failed = 0;
			for (int i = 0; i < 20; i++) {
				try {
					echo.echo("x");
				} catch (RpcException e) {
					failed++;
				}
			}
			RpcClient.NodeCallStats dead = client.nodeCallStats().get(t.deadAddress());
			assertTrue(failed > 0);
			assertEquals(failed, dead.failures());
			assertEquals(0, client.retryAttempts());
			assertEquals(0, client.retryNodeSwitches());
		}
	}
}
