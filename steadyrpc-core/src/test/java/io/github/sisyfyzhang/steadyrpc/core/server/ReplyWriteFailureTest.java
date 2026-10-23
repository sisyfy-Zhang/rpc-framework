package io.github.sisyfyzhang.steadyrpc.core.server;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.client.NettyClient;
import io.github.sisyfyzhang.steadyrpc.core.client.RequestWriter;
import io.github.sisyfyzhang.steadyrpc.core.codec.EnvelopeCodec;
import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.loadbalance.LoadStats;
import io.github.sisyfyzhang.steadyrpc.core.protocol.MethodSignature;
import io.github.sisyfyzhang.steadyrpc.core.protocol.RpcMessage;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializers;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 F2：回包写失败不再静默。业务方法执行期间客户端断开，业务线程随后写回包必然失败（连接已关闭）——
 * 每次失败计入服务端的 {@code replyWriteFailures}（同时记 debug；每条连接的第一次另记一行 warn），在途计数照常归零。
 */
class ReplyWriteFailureTest {

	public interface Slow {
		String slow(String s);
	}

	@Test
	void replyWritesOnAClosedConnectionAreCountedAndTheGateStillDrains() throws Exception {
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		ServiceKey key = ServiceKey.of(Slow.class);
		Serializers serializers = Serializers.load(SimpleTestSerializer.NAME, Map.of());
		ServiceDispatcher dispatcher = new ServiceDispatcher(List.of(), serializers, true);
		dispatcher.register(key, Slow.class, (Slow) s -> {
			entered.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return s;
		});
		NettyServer server = new NettyServer(dispatcher, ExecutorConfig.fixed(4), new SimpleTestSerializer().code());
		NettyClient client = new NettyClient(Serializers.load(SimpleTestSerializer.NAME, Map.of()), 1, new LoadStats());
		try {
			int port = server.bind(0).getPort();
			ServiceInstance instance = new ServiceInstance(key, "127.0.0.1", port, Map.of());
			client.updateConnections(List.of(instance));
			Method m = Slow.class.getMethod("slow", String.class);
			String paramSig = MethodSignature.paramSig(m);
			RequestWriter writer = serializer -> EnvelopeCodec.request(key.interfaceName(), key.version(), key.group(), "slow", paramSig,
					List.of(serializer.serialize("x", String.class)));
			// 同一条连接上两个在途请求：两次写失败、一行 warn
			CompletableFuture<RpcMessage> first = client.send(instance.address(), writer, Map.of(), TimeUnit.SECONDS.toNanos(30));
			CompletableFuture<RpcMessage> second = client.send(instance.address(), writer, Map.of(), TimeUnit.SECONDS.toNanos(30));
			assertTrue(entered.await(10, TimeUnit.SECONDS), "both requests must reach the business method");
			assertEquals(0, server.replyWriteFailures());

			client.closeConnections();
			await().atMost(10, TimeUnit.SECONDS).until(() -> server.activeConnections() == 0);
			release.countDown();

			await().atMost(10, TimeUnit.SECONDS).until(() -> server.replyWriteFailures() == 2);
			await().atMost(10, TimeUnit.SECONDS).until(() -> server.inFlight() == 0);
			assertTrue(first.isCompletedExceptionally() && second.isCompletedExceptionally(),
					"the client side failed both calls when the connection closed");
		} finally {
			release.countDown();
			client.close();
			server.close();
		}
	}
}
