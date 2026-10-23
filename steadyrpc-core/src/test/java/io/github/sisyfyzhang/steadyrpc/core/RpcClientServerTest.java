package io.github.sisyfyzhang.steadyrpc.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.filter.Filter;
import io.github.sisyfyzhang.steadyrpc.core.testutil.InMemoryRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * steadyrpc-core 自身的端到端测试：真实 socket、真实 Netty 线程，注册中心用进程内实现，序列化用测试编解码器。
 * 跨模块（真实 ZooKeeper、真实序列化器、Spring）的版本在 steadyrpc-integration-tests。
 */
class RpcClientServerTest {

	public interface Greeter {
		String greet(String name);

		CompletableFuture<String> greetAsync(String name);
	}

	public interface Counter {
		Integer next();
	}

	public interface Failing {
		String fail(String reason);
	}

	public interface Calc {
		String add(int a, Integer b);

		String add(String a, String b);

		Long mul(long a, Long b, String label);
	}

	static final class GreeterImpl implements Greeter {
		private final String serverName;

		GreeterImpl(String serverName) {
			this.serverName = serverName;
		}

		@Override
		public String greet(String name) {
			return serverName + ":hello " + name;
		}

		@Override
		public CompletableFuture<String> greetAsync(String name) {
			return CompletableFuture.supplyAsync(() -> serverName + ":async " + name);
		}
	}

	static final class CounterImpl implements Counter {
		private final AtomicInteger value = new AtomicInteger();

		@Override
		public Integer next() {
			return value.incrementAndGet();
		}
	}

	private static String namespace() {
		return "ns-" + UUID.randomUUID();
	}

	private static RpcServer server(String namespace, Filter... filters) {
		return RpcServer.builder().serializer(SimpleTestSerializer.NAME)
				.port(0)
				.host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(namespace))
				.executor(ExecutorConfig.fixed(2))
				.serverFilter(filters)
				.build();
	}

	@Test
	void routesEachInterfaceToTheServerThatExportsIt() {
		String ns = namespace();
		try (RpcServer greeterServer = server(ns); RpcServer counterServer = server(ns)) {
			greeterServer.export(Greeter.class, new GreeterImpl("A"));
			counterServer.export(Counter.class, new CounterImpl());
			greeterServer.start();
			greeterServer.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			counterServer.start();
			counterServer.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求

			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				Greeter greeter = client.reference(Greeter.class);
				Counter counter = client.reference(Counter.class);

				assertEquals("A:hello bob", greeter.greet("bob"));
				assertEquals(1, counter.next());
				assertEquals(2, counter.next());

				assertEquals(List.of("127.0.0.1:" + greeterServer.port()),
						client.providers(ServiceKey.of(Greeter.class)).stream().map(i -> i.address()).toList());
				assertEquals(List.of("127.0.0.1:" + counterServer.port()),
						client.providers(ServiceKey.of(Counter.class)).stream().map(i -> i.address()).toList());
				assertEquals(0, client.pendingRequests());
			}
		}
	}

	@Test
	void asyncMethodReturnsFutureAndCompletesWithValue() throws Exception {
		String ns = namespace();
		try (RpcServer server = server(ns)) {
			server.export(Greeter.class, new GreeterImpl("A"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				CompletableFuture<String> future = client.reference(Greeter.class).greetAsync("amy");
				assertEquals("A:async amy", future.get(5, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void filtersRunOnBothSidesAndAttachmentsAreSnapshottedPerCall() {
		String ns = namespace();
		List<String> clientTrace = Collections.synchronizedList(new ArrayList<>());
		AtomicInteger serverCalls = new AtomicInteger();
		Filter serverFilter = (next, inv) -> {
			serverCalls.incrementAndGet();
			return next.invoke(inv);
		};
		Filter outer = (next, inv) -> {
			clientTrace.add("outer:" + inv.getAttachment("traceparent"));
			return next.invoke(inv);
		};
		Filter inner = (next, inv) -> {
			clientTrace.add("inner:" + inv.methodName());
			return next.invoke(inv);
		};

		try (RpcServer server = server(ns, serverFilter)) {
			server.export(Greeter.class, new GreeterImpl("A"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME)
					.registry(InMemoryRegistryFactory.config(ns))
					.clientFilter(outer, inner)
					.build()) {
				Greeter greeter = client.reference(Greeter.class);

				RpcContext.setAttachment("traceparent", "00-trace-1");
				assertEquals("A:hello x", greeter.greet("x"));
				// 发起即消费：下一次调用不会带上上一次的 attachments
				assertNull(RpcContext.getAttachment("traceparent"));
				greeter.greet("y");

				assertEquals(List.of("outer:00-trace-1", "inner:greet", "outer:null", "inner:greet"), clientTrace);
				assertEquals(2, serverCalls.get());
			}
		}
	}

	/**
	 * 协议 v2 起服务端异常按类型还原（任务 1 时这里断言的是"压成字符串的 RpcException"）：
	 * IllegalStateException 在客户端 classpath 上、有 (String) 构造器 → 同类型、同消息；仍然不重试。
	 */
	@Test
	void serverSideErrorSurfacesAsSameTypeWithoutRetry() {
		String ns = namespace();
		AtomicInteger invocations = new AtomicInteger();
		try (RpcServer server = server(ns)) {
			server.export(Failing.class, reason -> {
				invocations.incrementAndGet();
				throw new IllegalStateException(reason);
			});
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME)
					.registry(InMemoryRegistryFactory.config(ns))
					.cluster("failover")
					.build()) {
				Failing failing = client.reference(Failing.class, ReferenceOptions.defaults().retries(3));

				IllegalStateException e = assertThrows(IllegalStateException.class, () -> failing.fail("out of stock"));
				assertEquals("out of stock", e.getMessage());
				assertTrue(e.getCause() instanceof RemoteStackTrace remote && remote.remoteStack().contains("IllegalStateException"),
						"remote stack trace must be attached as the cause");
				assertEquals(1, invocations.get(), "a server-side failure must not be retried");
			}
		}
	}

	@Test
	void emptyPushKeepsProvidersWhenProtectionIsOn() {
		String ns = namespace();
		try (RpcServer server = server(ns)) {
			server.export(Greeter.class, new GreeterImpl("A"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				Greeter greeter = client.reference(Greeter.class);
				InMemoryRegistryFactory.pushEmpty(ns, ServiceKey.of(Greeter.class));

				assertEquals(1, client.providers(ServiceKey.of(Greeter.class)).size());
				assertEquals("A:hello still", greeter.greet("still"));
			}
		}
	}

	@Test
	void emptyPushClearsProvidersWhenProtectionIsOff() {
		String ns = namespace();
		try (RpcServer server = server(ns)) {
			server.export(Greeter.class, new GreeterImpl("A"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME)
					.registry(InMemoryRegistryFactory.config(ns))
					.emptyProtection(false)
					.build()) {
				Greeter greeter = client.reference(Greeter.class);
				InMemoryRegistryFactory.pushEmpty(ns, ServiceKey.of(Greeter.class));

				assertTrue(client.providers(ServiceKey.of(Greeter.class)).isEmpty());
				RpcException e = assertThrows(RpcException.class, () -> greeter.greet("gone"));
				assertTrue(e.getMessage().contains("No available provider"), e.getMessage());
			}
		}
	}

	@Test
	void versionAndGroupArePartOfTheServiceIdentity() {
		String ns = namespace();
		try (RpcServer server = server(ns)) {
			server.export(Greeter.class, new GreeterImpl("gray"), io.github.sisyfyzhang.steadyrpc.core.config.ServiceOptions.defaults().version("2.0").group("gray"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				Greeter defaultGroup = client.reference(Greeter.class);
				Greeter gray = client.reference(Greeter.class, ReferenceOptions.defaults().version("2.0").group("gray"));

				assertEquals("gray:hello z", gray.greet("z"));
				assertThrows(RpcException.class, () -> defaultGroup.greet("z"), "default group has no provider");
			}
		}
	}

	@Test
	void closeUnregistersAndStopsAllFrameworkThreads() {
		String ns = namespace();
		RpcServer server = server(ns);
		server.export(Greeter.class, new GreeterImpl("A"));
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
		RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build();
		assertEquals("A:hello q", client.reference(Greeter.class).greet("q"));
		assertEquals(1, InMemoryRegistryFactory.instances(ns, ServiceKey.of(Greeter.class)).size());

		client.close();
		server.close();

		assertTrue(InMemoryRegistryFactory.instances(ns, ServiceKey.of(Greeter.class)).isEmpty());
		assertEquals(0, client.pendingRequests());
		await().atMost(5, TimeUnit.SECONDS).until(() -> rpcThreads().isEmpty());
		assertThrows(IllegalStateException.class, () -> client.reference(Greeter.class));
	}

	@Test
	void virtualThreadExecutorServesRequests() {
		String ns = namespace();
		try (RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME)
				.port(0)
				.host("127.0.0.1")
				.registry(InMemoryRegistryFactory.config(ns))
				.executor(ExecutorConfig.virtualThreads())
				.build()) {
			server.export(Greeter.class, new GreeterImpl("V"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				assertEquals("V:hello vt", client.reference(Greeter.class).greet("vt"));
			}
		}
	}

	@Test
	void multipleArgumentsOverloadsAndVersionsRouteByParamSig() {
		String ns = namespace();
		try (RpcServer server = server(ns)) {
			server.export(Calc.class, new Calc() {
				@Override
				public String add(int a, Integer b) {
					return "v1-int:" + (a + b);
				}

				@Override
				public String add(String a, String b) {
					return "v1-str:" + a + b;
				}

				@Override
				public Long mul(long a, Long b, String label) {
					return label == null ? -1L : a * b;
				}
			});
			server.export(Calc.class, new Calc() {
				@Override
				public String add(int a, Integer b) {
					return "v2-int:" + (a + b);
				}

				@Override
				public String add(String a, String b) {
					return "v2-str:" + a + b;
				}

				@Override
				public Long mul(long a, Long b, String label) {
					return 0L;
				}
			}, io.github.sisyfyzhang.steadyrpc.core.config.ServiceOptions.defaults().version("2"));
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).build()) {
				Calc v1 = client.reference(Calc.class);
				Calc v2 = client.reference(Calc.class, ReferenceOptions.defaults().version("2"));

				assertEquals("v1-int:5", v1.add(2, 3));
				assertEquals("v1-str:23", v1.add("2", "3"));
				assertEquals(42L, v1.mul(6L, 7L, "x"));
				assertEquals(-1L, v1.mul(6L, 7L, null), "null argument travels as present=0");
				assertEquals("v2-int:5", v2.add(2, 3));
				assertEquals("v2-str:23", v2.add("2", "3"));
				assertEquals(0, client.pendingRequests());
			}
		}
	}

	@Test
	void serverReadsAttachmentsAndResponseAttachmentsComeBack() {
		String ns = namespace();
		List<String> seenByBusiness = Collections.synchronizedList(new ArrayList<>());
		List<String> seenByClientFilter = Collections.synchronizedList(new ArrayList<>());
		Filter echoBack = (next, inv) -> next.invoke(inv)
				.thenApply(result -> result.withAttachments(java.util.Map.of("served-by", "A", "echo", inv.getAttachment("tenant"))));
		Filter readResponse = (next, inv) -> next.invoke(inv).whenComplete((result, error) -> {
			if (result != null) {
				seenByClientFilter.add(result.attachments().get("served-by") + "/" + result.attachments().get("echo"));
			}
		});
		try (RpcServer server = server(ns, echoBack)) {
			server.export(Greeter.class, new Greeter() {
				@Override
				public String greet(String name) {
					seenByBusiness.add(RpcContext.getServerAttachment("tenant") + "|" + RpcContext.serverAttachments().containsKey("rpc.timeout"));
					return "hi " + name;
				}

				@Override
				public CompletableFuture<String> greetAsync(String name) {
					return CompletableFuture.completedFuture(name);
				}
			});
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			try (RpcClient client = RpcClient.builder().serializer(SimpleTestSerializer.NAME).registry(InMemoryRegistryFactory.config(ns)).clientFilter(readResponse).build()) {
				RpcContext.setAttachment("tenant", "t-42");
				assertEquals("hi bob", client.reference(Greeter.class).greet("bob"));
			}
		}
		assertEquals(List.of("t-42|true"), seenByBusiness);
		assertEquals(List.of("A/t-42"), seenByClientFilter);
		assertTrue(RpcContext.serverAttachments().isEmpty(), "server attachments are unbound after the call");
	}

	static List<String> rpcThreads() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(Thread::isAlive)
				.map(Thread::getName)
				.filter(name -> name.startsWith("rpc-"))
				.toList();
	}
}
