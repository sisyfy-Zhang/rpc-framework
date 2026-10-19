package com.feynman.rpc.metrics.micrometer;

import java.util.List;
import java.util.Map;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;

/** 测试用"注册中心"：地址 {@code host:port} 即任何被订阅服务的唯一提供者（客户端用；服务端不配注册中心）。 */
public final class StaticRegistryFactory implements RegistryFactory {

	static final String TYPE = "static";

	static RegistryConfig config(int port) {
		return RegistryConfig.of(TYPE, "127.0.0.1:" + port);
	}

	@Override
	public Registry create(RegistryConfig config) {
		String address = config.address();
		int colon = address.lastIndexOf(':');
		String host = address.substring(0, colon);
		int port = Integer.parseInt(address.substring(colon + 1));
		return new Registry() {
			@Override
			public void register(ServiceInstance instance) {
				throw new UnsupportedOperationException("static registry is read-only");
			}

			@Override
			public void unregister(ServiceInstance instance) {
			}

			@Override
			public void subscribe(ServiceKey key, InstanceListener listener) {
				listener.onInstancesChanged(List.of(new ServiceInstance(key, host, port,
						Map.of(InstanceMetadata.WEIGHT, "1", InstanceMetadata.PROTOCOL, "2"))));
			}

			@Override
			public void unsubscribe(ServiceKey key, InstanceListener listener) {
			}

			@Override
			public void close() {
			}
		};
	}
}
