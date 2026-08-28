package com.feynman.rpc.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 application.yml/properties 里的 {@code rpc.*} 配置项。这里不是一套新的配置机制——
 * {@link RpcAutoConfiguration} 只是把这里绑定到的值复制进 {@code com.feynman.rpc.core.config.RpcConfig}
 * 本来就在读的那批系统属性，所以无论 Spring Boot 应用还是纯 Spring/无框架应用，底层配置
 * RPC 栈的方式完全一致。
 */
@ConfigurationProperties(prefix = "rpc")
public class RpcProperties {

	/** 服务端 Netty 监听端口（仅在服务端功能开启时有意义）。 */
	private int port = 9000;

	/** 服务端注册权重。 */
	private int weight = 1;

	/** 注册中心 SPI 扩展名，如 {@code zookeeper}、{@code nacos}。未设置则用 SPI 默认值。 */
	private String registry;

	/** 当前生效注册中心后端的连接地址（ZK 或 Nacos 地址）。 */
	private String registryAddress;

	/** 序列化 SPI 扩展名，如 {@code fastjson2}、{@code protobuf}。未设置则用 SPI 默认值。 */
	private String serializer;

	/** 负载均衡 SPI 扩展名，如 {@code roundrobin}、{@code p2c}。未设置则用 SPI 默认值。 */
	private String loadBalance;

	/** 容错（Cluster）SPI 扩展名，如 {@code failfast}/{@code failover}/{@code failback}。 */
	private String cluster;

	private final Provider provider = new Provider();

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getWeight() {
		return weight;
	}

	public void setWeight(int weight) {
		this.weight = weight;
	}

	public String getRegistry() {
		return registry;
	}

	public void setRegistry(String registry) {
		this.registry = registry;
	}

	public String getRegistryAddress() {
		return registryAddress;
	}

	public void setRegistryAddress(String registryAddress) {
		this.registryAddress = registryAddress;
	}

	public String getSerializer() {
		return serializer;
	}

	public void setSerializer(String serializer) {
		this.serializer = serializer;
	}

	public String getLoadBalance() {
		return loadBalance;
	}

	public void setLoadBalance(String loadBalance) {
		this.loadBalance = loadBalance;
	}

	public String getCluster() {
		return cluster;
	}

	public void setCluster(String cluster) {
		this.cluster = cluster;
	}

	public Provider getProvider() {
		return provider;
	}

	/** 绑定 {@code rpc.provider.*}。绑定网络端口的副作用比消费端代理装配大得多，
	 * 所以这里默认关闭，不会"仅仅因为 starter jar 在 classpath 上"就自动开启。 */
	public static class Provider {

		private boolean enabled = false;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}
}
