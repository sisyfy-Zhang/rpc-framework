package com.feynman.rpc.core.client;

import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 某个服务在消费端视角下的提供者列表，由注册中心订阅推送维护。Cluster 每次调用都从这里读最新列表。
 *
 * <p>推空保护在这里实现：推送空列表且此前非空时保留旧列表并告警。判断放在消费端而不是注册中心实现里，
 * 因为"空列表可信吗"是消费端的策略问题，与用 ZK 还是 Nacos 无关；两个实现因此只需忠实转发。
 * 框架内部类型。
 */
public final class ServiceDirectory implements InstanceListener {

	private static final Logger log = LoggerFactory.getLogger(ServiceDirectory.class);

	private final ServiceKey key;
	private final boolean emptyProtection;
	private final Consumer<ServiceDirectory> onChange;
	private volatile List<ServiceInstance> instances = List.of();

	/**
	 * @param onChange 列表真正发生替换后回调（RpcClient 据此调整连接池）
	 */
	public ServiceDirectory(ServiceKey key, boolean emptyProtection, Consumer<ServiceDirectory> onChange) {
		this.key = key;
		this.emptyProtection = emptyProtection;
		this.onChange = onChange;
	}

	@Override
	public void onInstancesChanged(List<ServiceInstance> latest) {
		List<ServiceInstance> current = instances;
		if (latest.isEmpty() && emptyProtection && !current.isEmpty()) {
			log.warn("Registry pushed an EMPTY provider list for {}; empty protection keeps the previous {} provider(s) {}",
					key, current.size(), current.stream().map(ServiceInstance::address).toList());
			return;
		}
		instances = List.copyOf(latest);
		log.info("Providers of {} updated: {}", key, instances.stream().map(ServiceInstance::address).toList());
		onChange.accept(this);
	}

	public ServiceKey key() {
		return key;
	}

	public List<ServiceInstance> instances() {
		return instances;
	}
}
