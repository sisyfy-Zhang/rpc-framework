package io.github.sisyfyzhang.steadyrpc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归（任务 13b，UnreachableRegistryTest 在全量构建里的偶发 "No connection pool"）：{@link RpcClient#providers} 非空，
 * 就必须能立即对其中的提供者发起调用。以前 {@code ServiceDirectory} 先发布新列表、再在 onChange 里建连接池，
 * 两步之间读到新列表的调用方（Cluster 选点、或像集成测试那样轮询 providers() 的代码）在 {@code ConnectionManager.acquire}
 * 上直接失败 "No connection pool"。
 *
 * <p>做法：消费者先订阅（提供者列表为空），一个平台线程忙等 providers() 变为非空、一看到就立刻调用；然后起提供者、注册。
 * 进程内注册中心在注册线程上同步推送，所以"发布列表"与"建连接池"之间的窗口与忙等线程真实并发。重复 {@value #ROUNDS} 次，
 * 每次都要成功。
 */
class ProvidersVisibleImpliesCallableTest {

	public interface Greeter {
		String greet(String name);
	}

	static final int ROUNDS = 200;

	@Test
	void theFirstCallAfterProvidersBecomeVisibleNeverMissesTheConnectionPool() throws Exception {
		List<String> failures = new ArrayList<>();
		for (int round = 0; round < ROUNDS; round++) {
			String ns = "visible-" + UUID.randomUUID();
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns))
					.build()) {
				Greeter greeter = client.reference(Greeter.class, ReferenceOptions.defaults().timeout(java.time.Duration.ofSeconds(5)));
				ServiceKey key = ServiceKey.of(Greeter.class);
				CompletableFuture<String> firstCall = new CompletableFuture<>();
				Thread spinner = Thread.ofPlatform().name("providers-spinner").start(() -> {
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
					while (client.providers(key).isEmpty()) {
						if (System.nanoTime() - deadline > 0) {
							firstCall.completeExceptionally(new AssertionError("providers() stayed empty"));
							return;
						}
						Thread.onSpinWait();
					}
					try {
						firstCall.complete(greeter.greet("r"));
					} catch (Throwable t) {
						firstCall.completeExceptionally(t);
					}
				});
				try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1")
						.registry(InMemoryRegistryFactory.config(ns)).executor(ExecutorConfig.fixed(1)).build()) {
					server.export(Greeter.class, name -> "hi " + name);
					server.start();
					try {
						assertEquals("hi r", firstCall.get(10, TimeUnit.SECONDS));
					} catch (java.util.concurrent.ExecutionException e) {
						failures.add("round " + round + ": " + e.getCause());
					}
					spinner.join(5_000);
				}
			}
		}
		System.out.printf("[providers-visible] %d rounds, %d failure(s)%n", ROUNDS, failures.size());
		assertTrue(failures.isEmpty(), failures.size() + "/" + ROUNDS + " first calls failed: " + failures.subList(0, Math.min(5, failures.size())));
	}
}
