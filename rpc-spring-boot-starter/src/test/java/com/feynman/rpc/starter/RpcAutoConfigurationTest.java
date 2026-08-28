package com.feynman.rpc.starter;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.mediator.InitialMediator;
import com.feynman.rpc.core.proxy.InvokeProxy;
import com.feynman.rpc.core.server.NettyInitializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 把 {@link RpcAutoConfiguration} 直接注册进一个普通的 {@link AnnotationConfigApplicationContext}
 * （不需要完整的 {@code SpringApplication} 启动流程——{@code @AutoConfiguration} 只是一个
 * 排序特殊的 {@code @Configuration}），验证服务端的两条激活路径、消费端总是会激活，
 * 以及绑定的配置项真的写进了 {@link RpcConfig} 的系统属性 key，而不只是"没有抛异常"。
 */
class RpcAutoConfigurationTest {

	private AnnotationConfigApplicationContext context;

	@AfterEach
	void cleanup() {
		if (context != null) {
			context.close();
		}
		System.clearProperty(RpcConfig.REGISTRY_KEY);
		System.clearProperty(RpcConfig.SERIALIZER_KEY);
		System.clearProperty(RpcConfig.LOADBALANCE_KEY);
		System.clearProperty(RpcConfig.CLUSTER_KEY);
		System.clearProperty("rpc.registry.zookeeper.address");
		System.clearProperty("rpc.registry.nacos.address");
	}

	@Test
	void consumerSideAlwaysActivatesWithNoProviderSideByDefault() {
		context = contextWithProperties(Map.of());
		context.register(RpcAutoConfiguration.class);
		context.refresh();

		assertNotNull(context.getBean(InvokeProxy.class));
		assertEquals(0, context.getBeanNamesForType(NettyInitializer.class).length);
	}

	@Test
	void providerSideActivatesViaProperty() throws InterruptedException {
		// rpc.port=0 -> 随机端口，这个测试永远不会和其它正在监听的端口冲突
		context = contextWithProperties(Map.of("rpc.provider.enabled", "true", "rpc.port", "0"));
		context.register(RpcAutoConfiguration.class);
		refreshInBackground(context); // NettyInitializer.start() 会在 closeFuture().sync() 上阻塞直到关闭

		assertNotNull(waitForBean(context, NettyInitializer.class));
		assertNotNull(waitForBean(context, InitialMediator.class));
	}

	@Test
	void providerSideActivatesViaEnableRpcAnnotation() throws InterruptedException {
		context = contextWithProperties(Map.of("rpc.port", "0"));
		context.register(EnableRpcMarker.class);
		context.register(RpcAutoConfiguration.class);
		refreshInBackground(context);

		assertNotNull(waitForBean(context, NettyInitializer.class));
	}

	@Test
	void boundPropertiesReachRpcConfigSystemPropertyKeys() {
		context = contextWithProperties(Map.of(
				"rpc.registry", "nacos",
				"rpc.serializer", "protobuf",
				"rpc.load-balance", "p2c",
				"rpc.cluster", "failover"));
		context.register(RpcAutoConfiguration.class);
		context.refresh();

		assertEquals("nacos", System.getProperty(RpcConfig.REGISTRY_KEY));
		assertEquals("protobuf", System.getProperty(RpcConfig.SERIALIZER_KEY));
		assertEquals("p2c", System.getProperty(RpcConfig.LOADBALANCE_KEY));
		assertEquals("failover", System.getProperty(RpcConfig.CLUSTER_KEY));
	}

	private static AnnotationConfigApplicationContext contextWithProperties(Map<String, Object> properties) {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		return ctx;
	}

	/** NettyInitializer.start() 会让 refresh 所在线程阻塞在 channel.closeFuture().sync() 上直到关闭，
	 * 所以在激活服务端的 context 上 refresh() 本身永远不会返回——放到后台线程跑，
	 * 改为轮询等待 bean 出现（和 rpc-core 里 NettyInitializerResilienceTest 的手法一样）。 */
	private static void refreshInBackground(AnnotationConfigApplicationContext context) {
		Thread thread = new Thread(context::refresh, "test-context-refresh");
		thread.setDaemon(true);
		thread.start();
	}

	private static <T> T waitForBean(AnnotationConfigApplicationContext context, Class<T> type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			try {
				return context.getBean(type);
			} catch (Exception notReadyYet) {
				Thread.sleep(20);
			}
		}
		throw new AssertionError("Bean of type " + type + " never became available");
	}

	@Configuration
	@EnableRpc
	static class EnableRpcMarker {
	}
}
