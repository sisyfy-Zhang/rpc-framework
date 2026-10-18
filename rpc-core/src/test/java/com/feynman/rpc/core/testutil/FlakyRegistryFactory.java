package com.feynman.rpc.core.testutil;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 前 N 次 register 失败、之后成功的注册中心（N = address 解析成的整数），用来验证 RpcServer 的后台注册会持续重试
 * 并在成功后完成 registered()。测试代码，静态计数器只服务于断言。
 */
public class FlakyRegistryFactory implements RegistryFactory {

	public static final AtomicInteger ATTEMPTS = new AtomicInteger();
	public static final Set<ServiceInstance> REGISTERED = ConcurrentHashMap.newKeySet();

	@Override
	public Registry create(RegistryConfig config) {
		int failures = Integer.parseInt(config.address());
		AtomicInteger remaining = new AtomicInteger(failures);
		return new Registry() {
			@Override
			public void register(ServiceInstance instance) {
				ATTEMPTS.incrementAndGet();
				if (remaining.getAndDecrement() > 0) {
					throw new RegistryException("simulated outage");
				}
				REGISTERED.add(instance);
			}

			@Override
			public void unregister(ServiceInstance instance) {
				REGISTERED.remove(instance);
			}

			@Override
			public void subscribe(ServiceKey key, InstanceListener listener) {
				listener.onInstancesChanged(List.of());
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
