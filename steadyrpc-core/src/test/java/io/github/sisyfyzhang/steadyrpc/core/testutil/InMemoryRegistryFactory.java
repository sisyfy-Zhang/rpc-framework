package io.github.sisyfyzhang.steadyrpc.core.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 进程内注册中心：{@code RegistryConfig.address} 作为命名空间，同一命名空间下的 Registry 共享节点表。
 * 语义对齐真实实现：订阅立即推送全量、变化时推送全量、Registry 关闭时它注册的节点随"会话"消失。
 * 仅测试使用，所以可以有静态状态。
 */
public class InMemoryRegistryFactory implements RegistryFactory {

	public static final String TYPE = "memory";

	private static final Map<String, Hub> HUBS = new ConcurrentHashMap<>();

	public static RegistryConfig config(String namespace) {
		return RegistryConfig.of(TYPE, namespace);
	}

	public static List<ServiceInstance> instances(String namespace, ServiceKey key) {
		return hub(namespace).snapshot(key);
	}

	/** 模拟注册中心抖动：向订阅者推送一个空列表，而节点表本身不变。 */
	public static void pushEmpty(String namespace, ServiceKey key) {
		hub(namespace).listeners(key).forEach(l -> l.onInstancesChanged(List.of()));
	}

	/** 模拟"提供者从注册中心摘除、但进程还在服务"（优雅下线的第一步）：从节点表删除并推送给订阅者。 */
	public static void unregister(String namespace, ServiceInstance instance) {
		hub(namespace).remove(instance);
	}

	private static Hub hub(String namespace) {
		return HUBS.computeIfAbsent(namespace, n -> new Hub());
	}

	@Override
	public Registry create(RegistryConfig config) {
		return new InMemoryRegistry(hub(config.address()));
	}

	private static final class Hub {
		private final Map<ServiceKey, Set<ServiceInstance>> instances = new ConcurrentHashMap<>();
		private final Map<ServiceKey, List<InstanceListener>> listeners = new ConcurrentHashMap<>();

		synchronized void add(ServiceInstance instance) {
			instances.computeIfAbsent(instance.key(), k -> ConcurrentHashMap.newKeySet()).add(instance);
			notifyListeners(instance.key());
		}

		synchronized void remove(ServiceInstance instance) {
			Set<ServiceInstance> set = instances.get(instance.key());
			if (set != null && set.remove(instance)) {
				notifyListeners(instance.key());
			}
		}

		List<ServiceInstance> snapshot(ServiceKey key) {
			return new ArrayList<>(instances.getOrDefault(key, Set.of()));
		}

		List<InstanceListener> listeners(ServiceKey key) {
			return listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
		}

		private void notifyListeners(ServiceKey key) {
			List<ServiceInstance> current = snapshot(key);
			listeners(key).forEach(l -> l.onInstancesChanged(current));
		}
	}

	private static final class InMemoryRegistry implements Registry {
		private final Hub hub;
		private final Set<ServiceInstance> mine = ConcurrentHashMap.newKeySet();
		private final Map<ServiceKey, List<InstanceListener>> subscriptions = new ConcurrentHashMap<>();

		InMemoryRegistry(Hub hub) {
			this.hub = hub;
		}

		@Override
		public void register(ServiceInstance instance) {
			mine.add(instance);
			hub.add(instance);
		}

		@Override
		public void unregister(ServiceInstance instance) {
			mine.remove(instance);
			hub.remove(instance);
		}

		@Override
		public void subscribe(ServiceKey key, InstanceListener listener) {
			subscriptions.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
			hub.listeners(key).add(listener);
			listener.onInstancesChanged(hub.snapshot(key));
		}

		@Override
		public void unsubscribe(ServiceKey key, InstanceListener listener) {
			hub.listeners(key).remove(listener);
			List<InstanceListener> list = subscriptions.get(key);
			if (list != null) {
				list.remove(listener);
			}
		}

		@Override
		public void close() {
			subscriptions.forEach((key, list) -> list.forEach(l -> hub.listeners(key).remove(l)));
			subscriptions.clear();
			mine.forEach(hub::remove);
			mine.clear();
		}
	}
}
