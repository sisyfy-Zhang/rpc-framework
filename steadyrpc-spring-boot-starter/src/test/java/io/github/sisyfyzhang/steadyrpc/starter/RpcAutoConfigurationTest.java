package io.github.sisyfyzhang.steadyrpc.starter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.OutlierDetectionConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.ReferenceOptions;
import io.github.sisyfyzhang.steadyrpc.core.config.RpcClientConfig;
import io.github.sisyfyzhang.steadyrpc.core.config.RpcServerConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 把 {@link RpcAutoConfiguration} 注册进普通的 {@link AnnotationConfigApplicationContext}（{@code @AutoConfiguration}
 * 只是排序特殊的 {@code @Configuration}），验证：refresh 不再阻塞、注解驱动的 export / reference 真的能往返调用、
 * 只有存在 {@code @RpcService} 才绑定端口、配置以不可变对象进入 core 而不是写进 System 属性。
 */
class RpcAutoConfigurationTest {

	public interface Greeter {
		String greet(String name);
	}

	@RpcService(version = "1.0")
	public static class GreeterImpl implements Greeter {
		@Override
		public String greet(String name) {
			return "hello " + name;
		}
	}

	@Component
	public static class GreeterConsumer implements InitializingBean {
		@RpcReference(version = "1.0", timeout = 3000)
		private Greeter greeter;
		private String seenDuringInit;

		@Override
		public void afterPropertiesSet() {
			// 注入发生在初始化回调之前
			seenDuringInit = String.valueOf(greeter);
		}
	}

	private AnnotationConfigApplicationContext context;
	private final String namespace = "starter-" + UUID.randomUUID();

	@AfterEach
	void cleanup() {
		if (context != null) {
			context.close();
		}
	}

	private AnnotationConfigApplicationContext contextWith(Map<String, Object> extra, Class<?>... components) {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		Map<String, Object> properties = new java.util.HashMap<>(Map.of(
				"rpc.registry.type", "memory",
				"rpc.registry.address", namespace,
				"rpc.server.port", "0",
				"rpc.server.host", "127.0.0.1",
				// 本模块测试 classpath 上只有 fastjson2；缺省名是 protobuf（D-001），所以显式指定
				"rpc.serializer", "fastjson2"));
		properties.putAll(extra);
		ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		ctx.register(RpcAutoConfiguration.class);
		if (components.length > 0) {
			ctx.register(components);
		}
		return ctx;
	}

	@Test
	void providerAndConsumerRoundTripWithoutBlockingRefresh() {
		context = contextWith(Map.of(), GreeterImpl.class, GreeterConsumer.class);
		// 旧实现在 ContextRefreshedEvent 里 closeFuture().sync()，refresh 永不返回
		assertTimeoutPreemptively(Duration.ofSeconds(15), () -> context.refresh());

		RpcServer server = context.getBean(RpcServer.class);
		ServiceKey key = new ServiceKey(Greeter.class.getName(), "1.0", null);
		RpcServerLifecycle lifecycle = context.getBean(RpcServerLifecycle.class);
		assertTrue(lifecycle.isRunning());
		// start 不等注册；readiness 一类的检查经 RpcServerLifecycle.registered() 等它
		lifecycle.registered().orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();
		assertTrue(lifecycle.isRegistered());
		assertEquals(List.of(key), server.exportedServices());
		assertEquals(1, InMemoryRegistryFactory.instances(namespace, key).size());

		GreeterConsumer consumer = context.getBean(GreeterConsumer.class);
		assertEquals("RpcReference(" + key + ")", consumer.seenDuringInit);
		assertEquals("hello spring", consumer.greeter.greet("spring"));

		context.close();
		assertFalse(server.isRunning());
		assertTrue(InMemoryRegistryFactory.instances(namespace, key).isEmpty(), "close must unregister");
	}

	@Test
	void consumerOnlyContextDoesNotStartTheServer() {
		context = contextWith(Map.of(), GreeterConsumer.class);
		context.refresh();

		assertFalse(context.getBean(RpcServerLifecycle.class).isRunning());
		assertFalse(context.getBean(RpcServer.class).isRunning());
		assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("rpc-server-")));
	}

	@Test
	void providerOnlyContextNeverCreatesTheClient() {
		context = contextWith(Map.of(), GreeterImpl.class);
		context.refresh();

		assertTrue(context.getBean(RpcServer.class).isRunning());
		assertFalse(context.getBeanFactory().containsSingleton("rpcClient"), "RpcClient is lazy and nobody referenced anything");
	}

	@Test
	void outlierDetectionIsOnByDefaultAndCanBeTurnedOff() {
		RpcProperties defaults = new RpcProperties();
		defaults.getRegistry().setType("memory");
		defaults.getRegistry().setAddress(namespace);
		assertEquals(OutlierDetectionConfig.defaults(), defaults.toClientConfig().outlierDetection(), "nothing set: core defaults");
		context = contextWith(Map.of("rpc.client.outlier-detection.enabled", "false"));
		context.refresh();
		OutlierDetectionConfig outlier = context.getBean(RpcClient.class).config().outlierDetection();
		assertFalse(outlier.enabled());
		assertEquals(OutlierDetectionConfig.DEFAULT_CONSECUTIVE_FAILURES, outlier.consecutiveFailures(), "other fields keep the core defaults");
	}

	@Test
	void propertiesBindToImmutableConfigsAndNeverTouchSystemProperties() {
		context = contextWith(Map.ofEntries(
				Map.entry("rpc.serializer", "fastjson2"),
				Map.entry("rpc.serializer-options.fastjson2.some-option", "on"),
				Map.entry("rpc.serializer-options.protobuf.allowed-packages", "com.acme.api.,com.acme.dto."),
				Map.entry("rpc.registry.properties.session-timeout-ms", "3000"),
				Map.entry("rpc.client.load-balance", "p2c"),
				Map.entry("rpc.client.cluster", "failover"),
				Map.entry("rpc.client.default-timeout", "3s"),
				Map.entry("rpc.client.connections-per-node", "4"),
				Map.entry("rpc.client.empty-protection", "false"),
				Map.entry("rpc.client.shutdown-timeout", "2s"),
				Map.entry("rpc.client.exception-allowlist", "com.acme.common.,com.acme.errors."),
				Map.entry("rpc.client.io-threads", "3"),
				Map.entry("rpc.client.sync-wait-grace", "250ms"),
				Map.entry("rpc.client.flush-consolidation", "false"),
				Map.entry("rpc.client.write-buffer-low", "16KB"),
				Map.entry("rpc.client.write-buffer-high", "128KB"),
				Map.entry("rpc.client.outbound-hard-limit", "4MB"),
				Map.entry("rpc.client.outlier-detection.min-requests", "40"),
				Map.entry("rpc.client.outlier-detection.failure-ratio", "0.75"),
				Map.entry("rpc.client.outlier-detection.consecutive-failures", "0"),
				Map.entry("rpc.client.outlier-detection.base-ejection", "5s"),
				Map.entry("rpc.client.outlier-detection.max-ejection", "1m"),
				Map.entry("rpc.client.outlier-detection.max-ejection-percent", "34"),
				Map.entry("rpc.server.weight", "7"),
				Map.entry("rpc.server.flush-consolidation", "false"),
				Map.entry("rpc.server.write-buffer-low", "65536"),
				Map.entry("rpc.server.write-buffer-high", "256KB"),
				Map.entry("rpc.server.outbound-backpressure", "false"),
				Map.entry("rpc.server.outbound-hard-limit", "0"),
				Map.entry("rpc.server.io-threads", "2"),
				Map.entry("rpc.server.shutdown-timeout", "4s"),
				Map.entry("rpc.server.deregister-grace", "1500ms"),
				Map.entry("rpc.server.remote-stack-trace", "false"),
				Map.entry("rpc.server.remote-stack-trace-max-bytes", "1024"),
				Map.entry("rpc.server.executor.type", "virtual"),
				Map.entry("rpc.server.executor.max-concurrent", "64")));
		context.refresh();

		RpcClientConfig client = context.getBean(RpcClient.class).config();
		assertEquals("memory", client.registry().type());
		assertEquals("3000", client.registry().properties().get("session-timeout-ms"));
		assertEquals("p2c", client.loadBalance());
		assertEquals("failover", client.cluster());
		assertEquals(Duration.ofSeconds(3), client.defaultTimeout());
		assertEquals(4, client.connectionsPerNode());
		assertFalse(client.emptyProtection());
		assertEquals(Duration.ofSeconds(2), client.shutdownTimeout());
		assertEquals(List.of("com.acme.common.", "com.acme.errors."), client.exceptionAllowlist());
		assertEquals(3, client.ioThreads());
		assertEquals(Duration.ofMillis(250), client.syncWaitGrace());
		assertFalse(client.flushConsolidation());
		assertEquals(16 * 1024, client.writeBufferLow());
		assertEquals(128 * 1024, client.writeBufferHigh());
		assertEquals(4L * 1024 * 1024, client.outboundHardLimit());
		assertEquals(OutlierDetectionConfig.builder().minRequests(40).failureRatio(0.75).consecutiveFailures(0)
				.baseEjection(Duration.ofSeconds(5)).maxEjection(Duration.ofMinutes(1)).maxEjectionPercent(34).build(), client.outlierDetection(),
				"unset outlier-detection.enabled keeps the core default (on)");
		Map<String, String> expectedOptions = Map.of("fastjson2.some-option", "on",
				"protobuf.allowed-packages", "com.acme.api.,com.acme.dto.");
		assertEquals(expectedOptions, client.serializerOptions());

		RpcServerConfig server = context.getBean(RpcServer.class).config();
		assertEquals(0, server.port());
		assertEquals("127.0.0.1", server.host());
		assertEquals(7, server.weight());
		assertEquals(2, server.ioThreads());
		assertFalse(server.flushConsolidation());
		assertEquals(64 * 1024, server.writeBufferLow(), "a plain number is bytes");
		assertEquals(256 * 1024, server.writeBufferHigh());
		assertFalse(server.outboundBackpressure());
		assertEquals(0, server.outboundHardLimit(), "0 = hard limit off");
		assertEquals(new ExecutorConfig.VirtualThreads(64), server.executor());
		assertEquals(Duration.ofSeconds(4), server.shutdownTimeout());
		assertEquals(1024, server.remoteStackTraceMaxBytes());
		assertEquals(Duration.ofMillis(1500), server.deregisterGrace());
		assertFalse(server.remoteStackTrace());
		assertEquals(expectedOptions, server.serializerOptions(), "serializer-options are shared by client and server");

		List<String> rpcSystemProperties = System.getProperties().stringPropertyNames().stream()
				.filter(name -> name.startsWith("rpc."))
				.toList();
		assertTrue(rpcSystemProperties.isEmpty(), "starter leaked configuration into System properties: " + rpcSystemProperties);
	}

	@Test
	void unsetPropertiesFallBackToCoreDefaults() {
		RpcProperties properties = new RpcProperties();
		RpcClientConfig client = properties.toClientConfig();
		assertEquals("zookeeper", client.registry().type());
		assertEquals("protobuf", client.serializer(), "Serializer SPI default is protobuf (D-001)");
		assertEquals("protobuf", properties.toServerConfig().serializer());
		assertEquals("roundrobin", client.loadBalance());
		assertEquals("failfast", client.cluster());
		assertEquals(RpcClientConfig.DEFAULT_TIMEOUT, client.defaultTimeout());
		assertTrue(client.emptyProtection());
		assertEquals(RpcServerConfig.DEFAULT_PORT, properties.toServerConfig().port());
		assertEquals(RpcClientConfig.DEFAULT_SHUTDOWN_TIMEOUT, client.shutdownTimeout());
		RpcServerConfig server = properties.toServerConfig();
		assertEquals(RpcServerConfig.DEFAULT_SHUTDOWN_TIMEOUT, server.shutdownTimeout());
		assertEquals(ExecutorConfig.defaults(), server.executor());
		assertEquals(RpcServerConfig.DEFAULT_DEREGISTER_GRACE, server.deregisterGrace());
		assertTrue(server.remoteStackTrace());
		assertEquals(RpcServerConfig.DEFAULT_REMOTE_STACK_TRACE_MAX_BYTES, server.remoteStackTraceMaxBytes());
		assertTrue(server.serializerOptions().isEmpty());
		assertEquals(RpcClientConfig.DEFAULT_IO_THREADS, client.ioThreads());
		assertEquals(RpcClientConfig.DEFAULT_SYNC_WAIT_GRACE, client.syncWaitGrace());
		assertEquals(RpcServerConfig.DEFAULT_IO_THREADS, server.ioThreads());
		assertTrue(client.flushConsolidation(), "flush consolidation is on by default");
		assertTrue(server.flushConsolidation(), "flush consolidation is on by default");
		assertEquals(RpcClientConfig.DEFAULT_WRITE_BUFFER_LOW, client.writeBufferLow());
		assertEquals(RpcClientConfig.DEFAULT_WRITE_BUFFER_HIGH, client.writeBufferHigh());
		assertEquals(16L * RpcClientConfig.DEFAULT_WRITE_BUFFER_HIGH, client.outboundHardLimit());
		assertEquals(RpcServerConfig.DEFAULT_WRITE_BUFFER_LOW, server.writeBufferLow());
		assertEquals(RpcServerConfig.DEFAULT_WRITE_BUFFER_HIGH, server.writeBufferHigh());
		assertTrue(server.outboundBackpressure(), "outbound backpressure is on by default");
		assertEquals(16L * RpcServerConfig.DEFAULT_WRITE_BUFFER_HIGH, server.outboundHardLimit());
	}

	@Test
	void settingOnlyTheHighWaterMarkKeepsTheDefaultLowAndDerivesTheHardLimit() {
		RpcProperties properties = new RpcProperties();
		properties.getServer().setWriteBufferHigh(org.springframework.util.unit.DataSize.ofMegabytes(1));
		RpcServerConfig server = properties.toServerConfig();
		assertEquals(RpcServerConfig.DEFAULT_WRITE_BUFFER_LOW, server.writeBufferLow());
		assertEquals(1024 * 1024, server.writeBufferHigh());
		assertEquals(16L * 1024 * 1024, server.outboundHardLimit(), "hard limit follows the configured high water mark");
	}

	@Test
	void fixedExecutorPropertiesBindThroughTheEnvironment() {
		context = contextWith(Map.of(
				"rpc.server.executor.type", "fixed",
				"rpc.server.executor.threads", "3",
				"rpc.server.executor.queue-capacity", "7"));
		context.refresh();
		assertEquals(ExecutorConfig.fixed(3, 7), context.getBean(RpcServer.class).config().executor());
	}

	@Test
	void directExecutorIsOptInThroughTheEnvironment() {
		context = contextWith(Map.of("rpc.server.executor.type", "direct", "rpc.server.executor.max-concurrent", "32"));
		context.refresh();
		assertEquals(ExecutorConfig.direct(32), context.getBean(RpcServer.class).config().executor());
		RpcProperties properties = new RpcProperties();
		properties.getServer().getExecutor().setType(RpcProperties.ExecutorType.DIRECT);
		assertEquals(ExecutorConfig.direct(), properties.toServerConfig().executor());
	}

	@Test
	void virtualExecutorWithoutLimitUsesTheCoreDefault() {
		context = contextWith(Map.of("rpc.server.executor.type", "virtual"));
		context.refresh();
		assertEquals(ExecutorConfig.virtualThreads(), context.getBean(RpcServer.class).config().executor());
	}

	static class AnnotatedFields {
		@RpcReference
		Greeter defaults;
		@RpcReference(version = "2.0", group = "g", timeout = 1500, retries = 2, inheritDeadline = false)
		Greeter everything;
		@RpcReference(awaitProvidersMs = 2500)
		Greeter awaiting;
	}

	@Test
	void rpcReferenceAttributesMapToReferenceOptions() throws Exception {
		ReferenceOptions defaults = RpcReferenceInjector.optionsOf(
				AnnotatedFields.class.getDeclaredField("defaults").getAnnotation(RpcReference.class));
		assertEquals(ReferenceOptions.defaults().version("").group(""), defaults);
		assertTrue(defaults.inheritDeadline(), "inheritDeadline defaults to true");

		ReferenceOptions everything = RpcReferenceInjector.optionsOf(
				AnnotatedFields.class.getDeclaredField("everything").getAnnotation(RpcReference.class));
		assertEquals(new ReferenceOptions("2.0", "g", Duration.ofMillis(1500), 2, false), everything);
	}

	@Test
	void awaitProvidersMsMapsToReferenceOptionsAndDefaultsToNoWait() throws Exception {
		ReferenceOptions defaults = RpcReferenceInjector.optionsOf(
				AnnotatedFields.class.getDeclaredField("defaults").getAnnotation(RpcReference.class));
		assertEquals(null, defaults.awaitProviders(), "awaitProvidersMs = 0 (default) must not wait");

		ReferenceOptions awaiting = RpcReferenceInjector.optionsOf(
				AnnotatedFields.class.getDeclaredField("awaiting").getAnnotation(RpcReference.class));
		assertEquals(Duration.ofMillis(2500), awaiting.awaitProviders());
		assertEquals(ReferenceOptions.defaults().version("").group("").awaitProviders(Duration.ofMillis(2500)), awaiting);
	}

	@Test
	void fixedExecutorQueueCapacityBinds() {
		RpcProperties properties = new RpcProperties();
		properties.getServer().getExecutor().setThreads(3);
		properties.getServer().getExecutor().setQueueCapacity(5);
		assertEquals(ExecutorConfig.fixed(3, 5), properties.toServerConfig().executor());
	}
}
