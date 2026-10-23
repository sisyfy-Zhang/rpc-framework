package io.github.sisyfyzhang.steadyrpc.bench;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.extension.ExtensionLoader;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistrationListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * {@code server --advertise-port <p>} 的实现：一个包装别的注册中心的 SPI 扩展（名 {@value #TYPE}），注册 / 摘除时把节点的端口换成
 * {@code advertise-port}，其余原样转给被包装的注册中心（属性 {@code delegate}，缺省 zookeeper）。用途：提供者监听真实端口，注册中心里写
 * toxiproxy 代理的端口，消费端经代理访问它（故障注入实验，见 bench/bin/fault-topology.sh）。
 *
 * <p>不改 core：Registry 本来就是 SPI；RpcServer 的注册、摘除、优雅停机顺序全部照常，只是公布出去的端口不同。
 */
public final class PortMappingRegistryFactory implements RegistryFactory {

	public static final String TYPE = "portmap";
	static final String DELEGATE = "delegate";
	static final String ADVERTISE_PORT = "advertise-port";

	/** 包装 {@code delegate}：注册时公布 {@code advertisePort}。 */
	public static RegistryConfig wrap(RegistryConfig delegate, int advertisePort) {
		RegistryConfig config = RegistryConfig.of(TYPE, delegate.address()).withProperty(DELEGATE, delegate.type())
				.withProperty(ADVERTISE_PORT, String.valueOf(advertisePort));
		for (var e : delegate.properties().entrySet()) {
			config = config.withProperty(e.getKey(), e.getValue());
		}
		return config;
	}

	@Override
	public Registry create(RegistryConfig config) {
		String delegateType = config.property(DELEGATE, RegistryConfig.ZOOKEEPER);
		int port = Integer.parseInt(config.property(ADVERTISE_PORT, "0"));
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("portmap registry needs advertise-port in 1..65535, got " + port);
		}
		Registry delegate = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(delegateType)
				.create(new RegistryConfig(delegateType, config.address(), withoutOwnKeys(config.properties())));
		return new PortMappingRegistry(delegate, port);
	}

	private static java.util.Map<String, String> withoutOwnKeys(java.util.Map<String, String> properties) {
		java.util.Map<String, String> rest = new java.util.LinkedHashMap<>(properties);
		rest.remove(DELEGATE);
		rest.remove(ADVERTISE_PORT);
		return rest;
	}

	private static final class PortMappingRegistry implements Registry {
		private final Registry delegate;
		private final int port;

		PortMappingRegistry(Registry delegate, int port) {
			this.delegate = delegate;
			this.port = port;
		}

		private ServiceInstance mapped(ServiceInstance instance) {
			return new ServiceInstance(instance.key(), instance.host(), port, instance.metadata());
		}

		@Override
		public void register(ServiceInstance instance) {
			delegate.register(mapped(instance));
		}

		@Override
		public void unregister(ServiceInstance instance) {
			delegate.unregister(mapped(instance));
		}

		@Override
		public void subscribe(ServiceKey key, InstanceListener listener) {
			delegate.subscribe(key, listener);
		}

		@Override
		public void unsubscribe(ServiceKey key, InstanceListener listener) {
			delegate.unsubscribe(key, listener);
		}

		@Override
		public boolean addRegistrationListener(RegistrationListener listener) {
			return delegate.addRegistrationListener(listener);
		}

		@Override
		public void close() {
			delegate.close();
		}
	}
}
