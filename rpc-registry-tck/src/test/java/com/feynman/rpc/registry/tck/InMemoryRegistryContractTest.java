package com.feynman.rpc.registry.tck;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.InstanceListener;
import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 套件自测：一个满足契约的最小进程内参考实现跑全部用例。它证明套件本身在没有任何真实后端时可编译、可运行、
 * 用例之间互不干扰；真实实现（ZooKeeper / Nacos）各自在自己的模块里继承 {@link RegistryContractTest}。
 *
 * <p>参考实现也演示"后端不可达时订阅"的契约：后端停机时 subscribe 记下监听者、不推任何列表、立即返回；
 * 后端恢复时补推首次全量列表。
 */
class InMemoryRegistryContractTest extends RegistryContractTest {

	private Backend backend;

	@Override
	protected RegistryFactory factory() {
		return config -> {
			if (!"memory://up".equals(config.address())) {
				return new InMemoryRegistry(null);
			}
			return new InMemoryRegistry(backend);
		};
	}

	@Override
	protected void startBackend() {
		backend = new Backend();
	}

	@Override
	protected void stopBackend() {
		backend = null;
	}

	@Override
	protected boolean supportsBackendRestart() {
		return true;
	}

	@Override
	protected void stopBackendForRestart() {
		backend.setUp(false);
	}

	@Override
	protected void restartBackend() {
		backend.setUp(true);
	}

	@Override
	protected RegistryConfig config() {
		return RegistryConfig.of("memory", "memory://up");
	}

	@Override
	protected RegistryConfig unreachableConfig() {
		return RegistryConfig.of("memory", "memory://down");
	}

	@Override
	protected Duration disappearanceWindow() {
		return Duration.ofSeconds(1);
	}

	@Override
	protected Duration registerFailureBound() {
		return Duration.ofMillis(100);
	}

	@Override
	protected Optional<String> threadNamePrefix() {
		return Optional.empty(); // 参考实现不起线程
	}

	/** 共享的"服务端"：节点表 + 订阅表，所有回调同步执行。 */
	private static final class Backend {
		final Map<String, ServiceInstance> nodes = new LinkedHashMap<>(); // key|address -> instance
		final Map<ServiceKey, List<InstanceListener>> listeners = new LinkedHashMap<>();
		/** 后端停机期间订阅、还没收到首次列表的监听者。 */
		final List<Map.Entry<ServiceKey, InstanceListener>> awaitingFirstPush = new ArrayList<>();
		boolean up = true;

		synchronized void setUp(boolean value) {
			up = value;
			if (up) {
				for (Map.Entry<ServiceKey, InstanceListener> pending : awaitingFirstPush) {
					pending.getValue().onInstancesChanged(snapshot(pending.getKey()));
				}
				awaitingFirstPush.clear();
			}
		}

		synchronized List<ServiceInstance> snapshot(ServiceKey key) {
			List<ServiceInstance> list = new ArrayList<>();
			for (ServiceInstance instance : nodes.values()) {
				if (instance.key().equals(key)) {
					list.add(instance);
				}
			}
			list.sort(Comparator.comparing(ServiceInstance::address));
			return list;
		}

		synchronized void changed(ServiceKey key) {
			List<ServiceInstance> snapshot = snapshot(key);
			for (InstanceListener listener : listeners.getOrDefault(key, List.of())) {
				listener.onInstancesChanged(snapshot);
			}
		}
	}

	private static final class InMemoryRegistry implements Registry {
		private final Backend backend;
		private final Map<String, ServiceInstance> mine = new LinkedHashMap<>();
		private final List<Map.Entry<ServiceKey, InstanceListener>> subscriptions = new CopyOnWriteArrayList<>();
		private boolean closed;

		InMemoryRegistry(Backend backend) {
			this.backend = backend;
		}

		private static String id(ServiceInstance instance) {
			return instance.key() + "|" + instance.address();
		}

		private void ensureOpen() {
			if (closed) {
				throw new IllegalStateException("closed");
			}
		}

		@Override
		public void register(ServiceInstance instance) {
			synchronized (this) {
				ensureOpen();
				if (backend == null) {
					throw new RegistryException("backend unreachable");
				}
			}
			synchronized (backend) {
				if (!backend.up) {
					throw new RegistryException("backend is down");
				}
				synchronized (this) {
					mine.put(id(instance), instance);
				}
				backend.nodes.put(id(instance), instance);
				backend.changed(instance.key());
			}
		}

		@Override
		public void unregister(ServiceInstance instance) {
			synchronized (this) {
				if (closed || backend == null || mine.remove(id(instance)) == null) {
					return;
				}
			}
			synchronized (backend) {
				backend.nodes.remove(id(instance));
				backend.changed(instance.key());
			}
		}

		/** 后端不可达（永久不可达的地址，或停机中）时：不抛、不推，记下订阅；恢复时由后端补推首次列表。 */
		@Override
		public void subscribe(ServiceKey key, InstanceListener listener) {
			synchronized (this) {
				ensureOpen();
				subscriptions.add(Map.entry(key, listener));
			}
			if (backend == null) {
				return; // 这个地址永远连不上：不推任何列表
			}
			synchronized (backend) {
				backend.listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
				if (backend.up) {
					listener.onInstancesChanged(backend.snapshot(key));
				} else {
					backend.awaitingFirstPush.add(Map.entry(key, listener));
				}
			}
		}

		@Override
		public void unsubscribe(ServiceKey key, InstanceListener listener) {
			subscriptions.remove(Map.entry(key, listener));
			if (backend == null) {
				return;
			}
			synchronized (backend) {
				List<InstanceListener> list = backend.listeners.get(key);
				if (list != null) {
					list.remove(listener);
				}
				backend.awaitingFirstPush.remove(Map.entry(key, listener));
			}
		}

		@Override
		public void close() {
			List<ServiceInstance> owned;
			synchronized (this) {
				if (closed) {
					return;
				}
				closed = true;
				owned = new ArrayList<>(mine.values());
				mine.clear();
			}
			if (backend == null) {
				return;
			}
			for (Map.Entry<ServiceKey, InstanceListener> subscription : subscriptions) {
				unsubscribe(subscription.getKey(), subscription.getValue());
			}
			synchronized (backend) {
				for (ServiceInstance instance : owned) {
					backend.nodes.remove(id(instance));
					backend.changed(instance.key());
				}
			}
		}
	}
}
