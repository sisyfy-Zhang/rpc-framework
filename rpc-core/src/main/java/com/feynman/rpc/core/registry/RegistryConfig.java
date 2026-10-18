package com.feynman.rpc.core.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 选哪个注册中心实现（{@code type}，即 RegistryFactory 的 SPI 扩展名）以及怎么连它。
 * 不可变：同一个配置对象可以安全地交给多个 client / server，而旧实现把地址塞进 System 属性，
 * 同一 JVM 里两个实例没法连不同的注册中心。
 *
 * <p>{@code properties} 留给各实现自己的可选参数（ZK 会话超时、Nacos namespace / 认证等），
 * core 不需要认识它们。
 */
public record RegistryConfig(String type, String address, Map<String, String> properties) {

	public static final String ZOOKEEPER = "zookeeper";
	public static final String NACOS = "nacos";

	public RegistryConfig {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("registry type must not be blank");
		}
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("registry address must not be blank");
		}
		properties = properties == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
	}

	public static RegistryConfig of(String type, String address) {
		return new RegistryConfig(type, address, Map.of());
	}

	public static RegistryConfig zookeeper(String address) {
		return of(ZOOKEEPER, address);
	}

	public static RegistryConfig nacos(String address) {
		return of(NACOS, address);
	}

	/** 返回追加了一个实现相关属性的新配置（原对象不变）。 */
	public RegistryConfig withProperty(String key, String value) {
		Map<String, String> copy = new LinkedHashMap<>(properties);
		copy.put(key, value);
		return new RegistryConfig(type, address, copy);
	}

	public String property(String key, String defaultValue) {
		String value = properties.get(key);
		return value == null || value.isBlank() ? defaultValue : value;
	}
}
