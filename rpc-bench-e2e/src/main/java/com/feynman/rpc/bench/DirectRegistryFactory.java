package com.feynman.rpc.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.InstanceMetadata;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * {@code --direct host:port[,host:port...]} 的实现：一个"注册中心"SPI 扩展（名 {@value #TYPE}），把地址列表当作
 * <b>任何</b>被订阅服务的全部提供者，订阅时立即推送、此后不再变化。RpcClient 没有直连 API，但 Registry 本来就是
 * SPI——在 harness 里加一个扩展是不改 core 的最小方式：压测路径上的连接池、负载均衡、Cluster 与注册中心模式完全相同，
 * 只是提供者列表的来源不同。只读：register 抛 {@link RegistryException}，服务端不应配置它。
 */
public final class DirectRegistryFactory implements RegistryFactory {

	public static final String TYPE = "direct";

	public static RegistryConfig config(String addresses) {
		return RegistryConfig.of(TYPE, addresses);
	}

	@Override
	public Registry create(RegistryConfig config) {
		return new DirectRegistry(parse(config.address()));
	}

	/** {@code host:port[,host:port...]}；格式错误抛 IllegalArgumentException。 */
	static List<HostPort> parse(String addresses) {
		List<HostPort> result = new ArrayList<>();
		for (String raw : addresses.split(",")) {
			String address = raw.trim();
			int colon = address.lastIndexOf(':');
			if (colon <= 0 || colon == address.length() - 1) {
				throw new IllegalArgumentException("direct address must be host:port, got \"" + address + "\"");
			}
			int port;
			try {
				port = Integer.parseInt(address.substring(colon + 1));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("direct address has a non-numeric port: \"" + address + "\"");
			}
			if (port <= 0 || port > 65535) {
				throw new IllegalArgumentException("direct address port out of range: \"" + address + "\"");
			}
			result.add(new HostPort(address.substring(0, colon), port));
		}
		return result;
	}

	record HostPort(String host, int port) {
	}

	private static final class DirectRegistry implements Registry {
		private final List<HostPort> addresses;
		private volatile boolean closed;

		DirectRegistry(List<HostPort> addresses) {
			this.addresses = addresses;
		}

		@Override
		public void register(ServiceInstance instance) {
			throw new RegistryException("direct registry is read-only: providers come from --direct");
		}

		@Override
		public void unregister(ServiceInstance instance) {
			// 只读注册中心：没有可摘除的节点
		}

		@Override
		public void subscribe(ServiceKey key, InstanceListener listener) {
			if (closed) {
				throw new IllegalStateException("direct registry closed");
			}
			List<ServiceInstance> instances = new ArrayList<>();
			for (HostPort address : addresses) {
				instances.add(new ServiceInstance(key, address.host(), address.port(),
						Map.of(InstanceMetadata.WEIGHT, "1", InstanceMetadata.PROTOCOL, "2")));
			}
			listener.onInstancesChanged(instances);
		}

		@Override
		public void unsubscribe(ServiceKey key, InstanceListener listener) {
			// 列表从不变化，没有需要停止的推送
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
