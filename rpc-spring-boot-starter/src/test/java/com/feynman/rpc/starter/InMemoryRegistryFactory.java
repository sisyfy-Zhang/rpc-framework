package com.feynman.rpc.starter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 测试用进程内注册中心（本模块不依赖任何真实注册中心实现）：address 作为命名空间，
 * 订阅立即推送全量、变化推送全量、关闭时移除自己注册的节点。
 */
public class InMemoryRegistryFactory implements RegistryFactory {

	private static final Map<String, Map<ServiceKey, Set<ServiceInstance>>> NODES = new ConcurrentHashMap<>();
	private static final Map<String, Map<ServiceKey, List<InstanceListener>>> LISTENERS = new ConcurrentHashMap<>();

	public static List<ServiceInstance> instances(String namespace, ServiceKey key) {
		return new ArrayList<>(nodes(namespace).getOrDefault(key, Set.of()));
	}

	/** 不经任何 Registry 直接放一个节点（模拟"别处注册的提供者"）。 */
	public static void publish(String namespace, ServiceInstance instance) {
		nodes(namespace).computeIfAbsent(instance.key(), k -> ConcurrentHashMap.newKeySet()).add(instance);
		changed(namespace, instance.key());
	}

	private static Map<ServiceKey, Set<ServiceInstance>> nodes(String namespace) {
		return NODES.computeIfAbsent(namespace, n -> new ConcurrentHashMap<>());
	}

	private static List<InstanceListener> listeners(String namespace, ServiceKey key) {
		return LISTENERS.computeIfAbsent(namespace, n -> new ConcurrentHashMap<>())
				.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
	}

	private static synchronized void changed(String namespace, ServiceKey key) {
		List<ServiceInstance> snapshot = instances(namespace, key);
		listeners(namespace, key).forEach(l -> l.onInstancesChanged(snapshot));
	}

	@Override
	public Registry create(RegistryConfig config) {
		String ns = config.address();
		Set<ServiceInstance> mine = ConcurrentHashMap.newKeySet();
		return new Registry() {
			@Override
			public void register(ServiceInstance instance) {
				mine.add(instance);
				nodes(ns).computeIfAbsent(instance.key(), k -> ConcurrentHashMap.newKeySet()).add(instance);
				changed(ns, instance.key());
			}

			@Override
			public void unregister(ServiceInstance instance) {
				mine.remove(instance);
				Set<ServiceInstance> set = nodes(ns).get(instance.key());
				if (set != null && set.remove(instance)) {
					changed(ns, instance.key());
				}
			}

			@Override
			public void subscribe(ServiceKey key, InstanceListener listener) {
				listeners(ns, key).add(listener);
				listener.onInstancesChanged(instances(ns, key));
			}

			@Override
			public void unsubscribe(ServiceKey key, InstanceListener listener) {
				listeners(ns, key).remove(listener);
			}

			@Override
			public void close() {
				for (ServiceInstance instance : List.copyOf(mine)) {
					unregister(instance);
				}
			}
		};
	}
}
