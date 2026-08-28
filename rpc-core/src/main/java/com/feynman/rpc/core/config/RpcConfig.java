package com.feynman.rpc.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import com.feynman.rpc.core.extension.ExtensionLoader;

/**
 * 不依赖 Spring Boot 的最小化配置层，用来选择当前生效的 SPI 扩展实现（registry/serializer/
 * loadbalance/cluster）。没有这一层的话，classpath 上同时有多个实现（比如 rpc-registry-zookeeper
 * 和 rpc-registry-nacos）毫无意义——{@code ExtensionLoader.getDefaultExtension()} 只会解析到接口
 * {@code @SPI} 注解上写死的那个名字，不重新编译就没法真正切换到另一个实现。
 *
 * <p>解析优先级：JVM 系统属性（最高，如 {@code -Drpc.registry=nacos}）&gt; classpath 下的
 * {@code rpc.properties} 文件 &gt; {@code @SPI} 上的默认值。这里刻意不绑定 {@code application.yml}
 * /Spring {@code Environment}——rpc-spring-boot-starter 会把真正的 {@code @ConfigurationProperties}
 * 绑到同一批 key 上（或直接读 {@code application.yml}），这里的设计不算白做，只是本模块本身不依赖
 * Spring Boot。
 */
public final class RpcConfig {

	public static final String REGISTRY_KEY = "rpc.registry";
	public static final String SERIALIZER_KEY = "rpc.serializer";
	public static final String LOADBALANCE_KEY = "rpc.loadbalance";
	public static final String CLUSTER_KEY = "rpc.cluster";

	private static final Properties FILE_PROPERTIES = loadPropertiesFile();

	private RpcConfig() {
	}

	/** {@code propertyKey} 对应的配置值，没有任何覆盖时返回 {@code null}。 */
	public static String get(String propertyKey) {
		String fromSystemProperty = System.getProperty(propertyKey);
		if (fromSystemProperty != null && !fromSystemProperty.isEmpty()) {
			return fromSystemProperty;
		}
		String fromFile = FILE_PROPERTIES.getProperty(propertyKey);
		return (fromFile != null && !fromFile.isEmpty()) ? fromFile : null;
	}

	/** 按 {@code propertyKey} 配置的名字解析出扩展实现，未配置则退回 SPI 默认值。 */
	public static <T> T resolve(ExtensionLoader<T> loader, String propertyKey) {
		String name = get(propertyKey);
		return name != null ? loader.getExtension(name) : loader.getDefaultExtension();
	}

	private static Properties loadPropertiesFile() {
		Properties properties = new Properties();
		try (InputStream in = RpcConfig.class.getClassLoader().getResourceAsStream("rpc.properties")) {
			if (in != null) {
				properties.load(in);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to load rpc.properties from classpath", e);
		}
		return properties;
	}
}
