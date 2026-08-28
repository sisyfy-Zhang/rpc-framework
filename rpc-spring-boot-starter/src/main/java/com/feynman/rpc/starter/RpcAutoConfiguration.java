package com.feynman.rpc.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.feynman.rpc.core.config.RpcConfig;
import com.feynman.rpc.core.config.RpcConsumerConfiguration;
import com.feynman.rpc.core.config.RpcProviderConfiguration;

/**
 * 只要 classpath 上有这个 starter 就自动生效（标准的
 * {@code META-INF/spring/...AutoConfiguration.imports} 机制——消费端不需要 {@code @EnableRpc}）。
 * 复用 {@link RpcConsumerConfiguration}/{@link RpcProviderConfiguration}——和纯 Spring（非 Boot）
 * 应用直接 import 的是同一套 {@code @Configuration} 类，而不是另起一套重复声明相同的 bean。
 *
 * <p>构造函数把绑定好的 {@link RpcProperties} 复制到 {@link RpcConfig} 已经在读的那批系统属性
 * key 上，这样 {@code application.yml} 和裸的 {@code -Drpc.xxx=} 参数最终配置的是同一套底层
 * 机制，不是两套并行的东西。
 */
@AutoConfiguration
@EnableConfigurationProperties(RpcProperties.class)
@Import(RpcConsumerConfiguration.class)
public class RpcAutoConfiguration {

	public RpcAutoConfiguration(RpcProperties properties) {
		applyToSystemProperties(properties);
	}

	private static void applyToSystemProperties(RpcProperties properties) {
		setIfPresent(RpcConfig.REGISTRY_KEY, properties.getRegistry());
		setIfPresent(RpcConfig.SERIALIZER_KEY, properties.getSerializer());
		setIfPresent(RpcConfig.LOADBALANCE_KEY, properties.getLoadBalance());
		setIfPresent(RpcConfig.CLUSTER_KEY, properties.getCluster());
		if (properties.getRegistryAddress() != null) {
			// 具体是哪个注册中心实现在用这个地址，要看 rpc.registry 选的是谁，两个都设一下无副作用
			System.setProperty("rpc.registry.zookeeper.address", properties.getRegistryAddress());
			System.setProperty("rpc.registry.nacos.address", properties.getRegistryAddress());
		}
		System.setProperty("rpc.port", String.valueOf(properties.getPort()));
		System.setProperty("rpc.weight", String.valueOf(properties.getWeight()));
	}

	private static void setIfPresent(String key, String value) {
		if (value != null && !value.isEmpty()) {
			System.setProperty(key, value);
		}
	}

	/**
	 * 绑定一个 Netty 监听端口的副作用比消费端的代理装配大得多，所以服务端需要显式声明
	 * {@code rpc.provider.enabled=true}——或者在启动类上标 {@link EnableRpc}，它会直接
	 * import {@link RpcProviderConfiguration}，效果等同，也不会因此重复注册 bean。
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(prefix = "rpc.provider", name = "enabled", havingValue = "true")
	@Import(RpcProviderConfiguration.class)
	static class ProviderAutoConfiguration {
	}
}
