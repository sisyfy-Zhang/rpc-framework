package io.github.sisyfyzhang.steadyrpc.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.MicrometerClientFilter;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.MicrometerServerFilter;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.RpcClientMetrics;
import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.RpcServerMetrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link RpcMetricsAutoConfiguration}：容器里有 MeterRegistry 时，一次真实的 @RpcService / @RpcReference 往返在两端都记下
 * Timer、Gauge 已绑定；{@code rpc.metrics.enabled=false}、没有 MeterRegistry bean、或 classpath 上没有指标模块时什么都不装配。
 */
class RpcMetricsAutoConfigurationTest {

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

	public static class GreeterConsumer {
		@RpcReference(version = "1.0", timeout = 3000)
		Greeter greeter;
	}

	@Configuration(proxyBeanMethods = false)
	static class MeterRegistryConfig {
		@Bean
		SimpleMeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}
	}

	private final String namespace = "metrics-" + UUID.randomUUID();
	private AnnotationConfigApplicationContext context;

	@AfterEach
	void cleanup() {
		if (context != null) {
			context.close();
		}
	}

	/** 与其它 starter 测试同一种装配方式；MeterRegistry 的配置先注册，@ConditionalOnBean 才看得到它（Boot 里由自动配置排序保证）。 */
	private AnnotationConfigApplicationContext contextWith(boolean meterRegistry, Map<String, Object> extra, ClassLoader classLoader) {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		if (classLoader != null) {
			ctx.setClassLoader(classLoader);
		}
		Map<String, Object> properties = new HashMap<>(Map.of(
				"rpc.registry.type", "memory",
				"rpc.registry.address", namespace,
				"rpc.server.port", "0",
				"rpc.server.host", "127.0.0.1",
				"rpc.serializer", "fastjson2"));
		properties.putAll(extra);
		ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		if (meterRegistry) {
			ctx.register(MeterRegistryConfig.class);
		}
		ctx.register(RpcAutoConfiguration.class, RpcMetricsAutoConfiguration.class, GreeterImpl.class, GreeterConsumer.class);
		ctx.refresh();
		return ctx;
	}

	private String roundTrip() {
		context.getBean(RpcServerLifecycle.class).registered().orTimeout(10, TimeUnit.SECONDS).join();
		return context.getBean(GreeterConsumer.class).greeter.greet("metrics");
	}

	@Test
	void withAMeterRegistryBothSidesRecordTimersAndGaugesAreBound() {
		context = contextWith(true, Map.of(), null);
		assertTrue(context.containsBean("rpcMetricsClientFilter"));
		assertTrue(context.containsBean("rpcMetricsServerFilter"));
		assertEquals("hello metrics", roundTrip());

		SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
		Timer client = registry.find(MicrometerClientFilter.METER_NAME).tag("service", Greeter.class.getName())
				.tag("method", "greet").tag("status", "OK").timer();
		Timer server = registry.find(MicrometerServerFilter.METER_NAME).tag("service", Greeter.class.getName())
				.tag("method", "greet").tag("status", "OK").timer();
		assertNotNull(client, registry.getMetersAsString());
		assertNotNull(server, registry.getMetersAsString());
		assertEquals(1, client.count());
		assertEquals(1, server.count());

		assertNotNull(registry.find(RpcServerMetrics.INFLIGHT).gauge(), "server gauges bound once RpcServer is initialized");
		assertNotNull(registry.find(RpcServerMetrics.CONNECTIONS).gauge());
		assertNotNull(registry.find(RpcClientMetrics.INFLIGHT).gauge(), "client gauges bound once the lazy RpcClient exists");
		assertTrue(registry.find(RpcClientMetrics.CONNECTIONS).gauge().value() >= 1.0);
		assertTrue(context.getBean(RpcServer.class).isRunning());

		context.close();
		assertNull(registry.find(RpcServerMetrics.INFLIGHT).gauge(), "gauges are unbound when the context closes");
		assertNull(registry.find(RpcClientMetrics.CONNECTIONS).gauge());
	}

	@Test
	void disabledByPropertyRegistersNothing() {
		context = contextWith(true, Map.of("rpc.metrics.enabled", "false"), null);
		assertFalse(context.containsBean("rpcMetricsClientFilter"));
		assertFalse(context.containsBean("rpcMetricsServerFilter"));
		assertEquals("hello metrics", roundTrip());
		SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
		assertNull(registry.find(MicrometerClientFilter.METER_NAME).timer(), registry.getMetersAsString());
		assertNull(registry.find(MicrometerServerFilter.METER_NAME).timer(), registry.getMetersAsString());
		assertNull(registry.find(RpcServerMetrics.INFLIGHT).gauge());
	}

	@Test
	void withoutAMeterRegistryBeanNothingIsRegistered() {
		context = contextWith(false, Map.of(), null);
		assertFalse(context.containsBean("rpcMetricsClientFilter"));
		assertFalse(context.containsBean("rpcMetricsBinder"));
		assertEquals("hello metrics", roundTrip());
	}

	@Test
	void withoutTheMetricsModuleOnTheClasspathNothingIsRegistered() {
		context = contextWith(true, Map.of(), new FilteredClassLoader("io.github.sisyfyzhang.steadyrpc.metrics."));
		assertFalse(context.containsBean("rpcMetricsClientFilter"));
		assertFalse(context.containsBean("rpcMetricsBinder"));
	}
}
