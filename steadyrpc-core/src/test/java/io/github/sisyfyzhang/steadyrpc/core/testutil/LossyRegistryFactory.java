package io.github.sisyfyzhang.steadyrpc.core.testutil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistrationListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 支持 {@link Registry#addRegistrationListener} 的注册中心：测试用 {@link #lose(String)} / {@link #restore(String)}
 * 模拟"会话过期、节点丢失"与"在新会话上重建完成"。address 作为命名空间区分用例。测试代码，静态表只服务于断言。
 */
public class LossyRegistryFactory implements RegistryFactory {

	private static final Map<String, RegistrationListener> LISTENERS = new ConcurrentHashMap<>();

	public static void lose(String namespace) {
		LISTENERS.get(namespace).onRegistrationsLost();
	}

	public static void restore(String namespace) {
		LISTENERS.get(namespace).onRegistrationsRestored();
	}

	@Override
	public Registry create(RegistryConfig config) {
		String namespace = config.address();
		return new Registry() {
			@Override
			public void register(ServiceInstance instance) {
			}

			@Override
			public void unregister(ServiceInstance instance) {
			}

			@Override
			public void subscribe(ServiceKey key, InstanceListener listener) {
				listener.onInstancesChanged(List.of());
			}

			@Override
			public void unsubscribe(ServiceKey key, InstanceListener listener) {
			}

			@Override
			public boolean addRegistrationListener(RegistrationListener listener) {
				LISTENERS.put(namespace, listener);
				return true;
			}

			@Override
			public void close() {
				LISTENERS.remove(namespace);
			}
		};
	}
}
