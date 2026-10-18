package com.feynman.rpc.core.testutil;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;

/** {@link Registry#register} 总是失败，用来验证注册失败不会拖垮一个已经绑定成功的监听端口。 */
public class FailingRegistryFactory implements RegistryFactory {

	public static final AtomicInteger REGISTER_ATTEMPTS = new AtomicInteger();

	@Override
	public Registry create(RegistryConfig config) {
		return new Registry() {
			@Override
			public void register(ServiceInstance instance) {
				REGISTER_ATTEMPTS.incrementAndGet();
				throw new RegistryException("simulated registry outage", new RuntimeException("boom"));
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
			public void close() {
			}
		};
	}
}
