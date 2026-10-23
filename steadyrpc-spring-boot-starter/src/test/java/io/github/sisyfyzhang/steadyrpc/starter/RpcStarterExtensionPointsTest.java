package io.github.sisyfyzhang.steadyrpc.starter;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.filter.ClientFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.DeadlineClientFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.Filter;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invoker;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.filter.ServerFilter;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * starter 的扩展点：Filter bean 自动接入（含顺序）、Customizer、可选健康指示器、异步 stop(Runnable)。
 * 与 {@link RpcAutoConfigurationTest} 一样直接把自动配置类注册进 {@link AnnotationConfigApplicationContext}。
 */
class RpcStarterExtensionPointsTest {

	public interface Echo {
		String echo(String value);

		String slow(int millis);
	}

	@RpcService
	public static class EchoImpl implements Echo {
		static volatile CountDownLatch slowStarted = new CountDownLatch(1);

		@Override
		public String echo(String value) {
			return value;
		}

		@Override
		public String slow(int millis) {
			slowStarted.countDown();
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			}
			return "slow-" + millis;
		}
	}

	@Component
	public static class EchoConsumer {
		@RpcReference(timeout = 5000)
		Echo echo;
	}

	/** 各 Filter 记录"哪一端、谁"执行了，顺序即执行顺序。 */
	static final List<String> TRACE = new CopyOnWriteArrayList<>();

	abstract static class Recording implements Filter {
		private final String name;
		private final int order;

		Recording(String name, int order) {
			this.name = name;
			this.order = order;
		}

		@Override
		public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
			if (invocation.methodName().equals("echo")) {
				TRACE.add((invocation.isInbound() ? "server:" : "client:") + name);
			}
			return next.invoke(invocation);
		}

		@Override
		public int order() {
			return order;
		}
	}

	/** 注册顺序在前、order 更大：执行应排在 {@link ClientEarly} 之后。 */
	@Component
	public static class ClientLate extends Recording implements ClientFilter {
		public ClientLate() {
			super("late(10)", 10);
		}
	}

	@Component
	public static class ClientEarly extends Recording implements ClientFilter {
		public ClientEarly() {
			super("early(5)", 5);
		}
	}

	/** 早于内置 DeadlineClientFilter（order 为负）：靠 order 插队。 */
	@Component
	public static class ClientBeforeBuiltIns extends Recording implements ClientFilter {
		public ClientBeforeBuiltIns() {
			super("first", DeadlineClientFilter.ORDER - 1);
		}
	}

	/** 同时实现两个标记接口：两条链都应接入。 */
	@Component
	public static class BothSides extends Recording implements ClientFilter, ServerFilter {
		public BothSides() {
			super("both(20)", 20);
		}
	}

	@Component
	public static class ServerOnly extends Recording implements ServerFilter {
		public ServerOnly() {
			super("server-only(1)", 1);
		}
	}

	/** 没有标记接口：不知道放哪一端，不接入。 */
	@Component
	public static class Unmarked extends Recording {
		public Unmarked() {
			super("unmarked", 0);
		}
	}

	/** 两个同为 order 0 的 Filter：core 按收集顺序（即 @Order）稳定排序。 */
	@Component
	@Order(2)
	public static class SameOrderB extends Recording implements ClientFilter {
		public SameOrderB() {
			super("same-B", 0);
		}
	}

	@Component
	@Order(1)
	public static class SameOrderA extends Recording implements ClientFilter {
		public SameOrderA() {
			super("same-A", 0);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Customizers {
		static final AtomicReference<String> SEEN_TIMEOUT = new AtomicReference<>();

		@Bean
		@Order(1)
		RpcClientCustomizer firstClientCustomizer() {
			return builder -> builder.defaultTimeout(Duration.ofSeconds(7)).serializerOption("custom.key", "from-customizer");
		}

		@Bean
		@Order(2)
		RpcClientCustomizer secondClientCustomizer() {
			// 后执行者覆盖：properties 设了 3s、第一个 customizer 设了 7s，最终 9s
			return builder -> builder.defaultTimeout(Duration.ofSeconds(9)).connectionsPerNode(3);
		}

		@Bean
		RpcServerCustomizer serverCustomizer() {
			return builder -> builder.weight(42).shutdownTimeout(Duration.ofSeconds(3));
		}
	}

	private AnnotationConfigApplicationContext context;
	private final String namespace = "starter-ext-" + UUID.randomUUID();

	@BeforeEach
	void reset() {
		TRACE.clear();
		EchoImpl.slowStarted = new CountDownLatch(1);
	}

	@AfterEach
	void cleanup() {
		if (context != null) {
			context.close();
		}
	}

	private AnnotationConfigApplicationContext contextWith(Map<String, Object> extra, Class<?>... components) {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		Map<String, Object> properties = new HashMap<>(Map.of(
				"rpc.registry.type", "memory",
				"rpc.registry.address", namespace,
				"rpc.server.port", "0",
				"rpc.server.host", "127.0.0.1",
				// 本模块测试 classpath 上只有 fastjson2；缺省名是 protobuf（D-001），所以显式指定
				"rpc.serializer", "fastjson2"));
		properties.putAll(extra);
		ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		ctx.register(RpcAutoConfiguration.class, RpcHealthAutoConfiguration.class);
		if (components.length > 0) {
			ctx.register(components);
		}
		return ctx;
	}

	@Test
	void filterBeansJoinTheRightChainInOrder() {
		context = contextWith(Map.of(), EchoImpl.class, EchoConsumer.class,
				ClientLate.class, ClientEarly.class, ClientBeforeBuiltIns.class, BothSides.class, ServerOnly.class,
				Unmarked.class, SameOrderB.class, SameOrderA.class);
		context.refresh();
		context.getBean(RpcServerLifecycle.class).registered().orTimeout(10, TimeUnit.SECONDS).join();

		assertEquals("hi", context.getBean(EchoConsumer.class).echo.echo("hi"));

		// 客户端：order 升序；同为 0 的两个按 @Order 收集顺序；server-only 与 unmarked 不出现在客户端
		// 服务端：内置 DeadlineServerFilter（不记录）→ server-only(1) → both(20)
		assertEquals(List.of(
				"client:first", "client:same-A", "client:same-B", "client:early(5)", "client:late(10)", "client:both(20)",
				"server:server-only(1)", "server:both(20)"), TRACE);
		assertEquals(6, context.getBean(RpcClient.class).config().clientFilters().size());
		assertEquals(2, context.getBean(RpcServer.class).config().serverFilters().size());
	}

	@Test
	void customizersRunAfterPropertiesInOrder() {
		context = contextWith(Map.of("rpc.client.default-timeout", "3s", "rpc.server.weight", "5"),
				Customizers.class, EchoConsumer.class);
		context.refresh();

		RpcClient client = context.getBean(RpcClient.class);
		assertEquals(Duration.ofSeconds(9), client.config().defaultTimeout());
		assertEquals(3, client.config().connectionsPerNode());
		assertEquals("from-customizer", client.config().serializerOptions().get("custom.key"));
		RpcServer server = context.getBean(RpcServer.class);
		assertEquals(42, server.config().weight(), "customizer overrides rpc.server.weight");
		assertEquals(Duration.ofSeconds(3), server.config().shutdownTimeout());
	}

	@Test
	void healthIndicatorFollowsTheServerLifecycle() {
		context = contextWith(Map.of(), EchoImpl.class);
		context.refresh();
		HealthIndicator indicator = context.getBean("rpcHealthIndicator", HealthIndicator.class);
		RpcServer server = context.getBean(RpcServer.class);
		server.registered().orTimeout(10, TimeUnit.SECONDS).join();

		Health up = indicator.health();
		assertEquals(Status.UP, up.getStatus());
		assertEquals("STARTED", up.getDetails().get("state"));
		assertEquals(true, up.getDetails().get("registered"));

		// 摘掉节点不等于未注册（RpcServer 认为自己已注册）；用 close 驱动状态：CLOSED → DOWN
		context.getBean(RpcServerLifecycle.class).stop();
		assertEquals(RpcServer.State.CLOSED, server.state());
		assertEquals(Status.DOWN, indicator.health().getStatus());
	}

	@Test
	void healthIsOutOfServiceWhileTheRegistryIsUnreachableAndWhileDraining() throws Exception {
		// 注册中心类型 failing：create 总是失败，服务端端口照常服务、后台无限重试
		context = contextWith(Map.of("rpc.registry.type", "failing", "rpc.server.shutdown-timeout", "5s"), EchoImpl.class);
		context.refresh();
		HealthIndicator indicator = context.getBean("rpcHealthIndicator", HealthIndicator.class);
		RpcServer server = context.getBean(RpcServer.class);
		assertEquals(RpcServer.State.STARTED, server.state());
		Health notRegistered = indicator.health();
		assertEquals(Status.OUT_OF_SERVICE, notRegistered.getStatus());
		assertEquals(false, notRegistered.getDetails().get("registered"));

		// DRAINING：直连一个慢调用占住在途，然后异步停止
		try (RpcClient direct = RpcClient.builder().serializer("fastjson2").registry(new RegistryConfig("memory", namespace, Map.of())).build()) {
			InMemoryRegistryFactory.publish(namespace, new ServiceInstance(ServiceKey.of(Echo.class), "127.0.0.1", server.port(), Map.of()));
			Echo echo = direct.reference(Echo.class);
			CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> echo.slow(800));
			assertTrue(EchoImpl.slowStarted.await(5, TimeUnit.SECONDS));
			CountDownLatch stopped = new CountDownLatch(1);
			context.getBean(RpcServerLifecycle.class).stop(stopped::countDown);
			await().atMost(3, TimeUnit.SECONDS).until(() -> server.state() == RpcServer.State.DRAINING);
			assertEquals(Status.OUT_OF_SERVICE, indicator.health().getStatus());
			assertEquals("slow-800", slow.get(5, TimeUnit.SECONDS));
			assertTrue(stopped.await(5, TimeUnit.SECONDS));
			assertEquals(Status.DOWN, indicator.health().getStatus());
		}
	}

	@Test
	void consumerOnlyApplicationIsHealthy() {
		context = contextWith(Map.of(), EchoConsumer.class);
		context.refresh();
		Health health = context.getBean("rpcHealthIndicator", HealthIndicator.class).health();
		assertEquals(Status.UP, health.getStatus());
		assertFalse(context.getBean(RpcServer.class).isRunning());
	}

	@Test
	void healthIndicatorCanBeDisabled() {
		context = contextWith(Map.of("management.health.rpc.enabled", "false"), EchoImpl.class);
		context.refresh();
		assertFalse(context.containsBean("rpcHealthIndicator"));
	}

	@Test
	void noHealthIndicatorWithoutActuator() {
		context = contextWith(Map.of(), EchoImpl.class);
		context.setClassLoader(new FilteredClassLoader(HealthIndicator.class));
		context.refresh();
		assertFalse(context.containsBean("rpcHealthIndicator"));
		assertTrue(context.getBean(RpcServer.class).isRunning(), "the rest of the starter works without Actuator");
	}

	@Test
	void stopWithCallbackClosesOnADedicatedThreadAndCallsBackWhenDone() throws Exception {
		context = contextWith(Map.of(), EchoImpl.class);
		context.refresh();
		RpcServerLifecycle lifecycle = context.getBean(RpcServerLifecycle.class);
		RpcServer server = context.getBean(RpcServer.class);
		server.registered().orTimeout(10, TimeUnit.SECONDS).join();

		AtomicReference<String> callbackThread = new AtomicReference<>();
		AtomicReference<RpcServer.State> stateAtCallback = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		lifecycle.stop(() -> {
			callbackThread.set(Thread.currentThread().getName());
			stateAtCallback.set(server.state());
			done.countDown();
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertEquals(RpcServerLifecycle.SHUTDOWN_THREAD_NAME, callbackThread.get());
		assertEquals(RpcServer.State.CLOSED, stateAtCallback.get(), "callback only after close finished");
		assertFalse(lifecycle.isRunning());

		// 未运行时直接回调
		CountDownLatch immediate = new CountDownLatch(1);
		lifecycle.stop(immediate::countDown);
		assertEquals(0, immediate.getCount());
	}

	@Test
	void contextCloseWaitsForInFlightCallsThroughTheAsyncStop() throws Exception {
		context = contextWith(Map.of(), EchoImpl.class, EchoConsumer.class);
		context.refresh();
		context.getBean(RpcServerLifecycle.class).registered().orTimeout(10, TimeUnit.SECONDS).join();
		RpcServer server = context.getBean(RpcServer.class);
		Echo echo = context.getBean(EchoConsumer.class).echo;
		CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> echo.slow(500));
		assertTrue(EchoImpl.slowStarted.await(5, TimeUnit.SECONDS));

		context.close();
		assertEquals(RpcServer.State.CLOSED, server.state(), "context.close() returns only after the server closed");
		assertEquals("slow-500", slow.get(1, TimeUnit.SECONDS));
	}
}
