package io.github.sisyfyzhang.steadyrpc.registry.tck;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.awaitility.Awaitility;

import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceListener;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/**
 * 记录每一次推送的监听者。契约只承诺"每次推送都是全量列表"，不承诺推送次数（实现可以合并事件、可以重复推送
 * 相同列表），所以断言一律针对"最近一次推送"或"推送次数是否还在增长"，不针对确切次数。
 */
public final class PushRecorder implements InstanceListener {

	private final String name;
	private final List<List<ServiceInstance>> pushes = new ArrayList<>();

	public PushRecorder(String name) {
		this.name = name;
	}

	@Override
	public synchronized void onInstancesChanged(List<ServiceInstance> instances) {
		pushes.add(List.copyOf(instances));
	}

	public synchronized int count() {
		return pushes.size();
	}

	/** 最近一次推送；还没有推送时为 null。 */
	public synchronized List<ServiceInstance> latest() {
		return pushes.isEmpty() ? null : pushes.get(pushes.size() - 1);
	}

	public synchronized List<List<ServiceInstance>> all() {
		return List.copyOf(pushes);
	}

	/** 等到最近一次推送满足条件，返回那次推送。 */
	public List<ServiceInstance> awaitLatest(Duration timeout, String description, Predicate<List<ServiceInstance>> condition) {
		Awaitility.await(name + " " + description)
				.atMost(timeout)
				.pollInterval(Duration.ofMillis(20))
				.until(() -> {
					List<ServiceInstance> latest = latest();
					return latest != null && condition.test(latest);
				});
		return latest();
	}

	public static boolean containsAddress(List<ServiceInstance> instances, String address) {
		return instances.stream().anyMatch(i -> i.address().equals(address));
	}

	public static ServiceInstance byAddress(List<ServiceInstance> instances, String address) {
		return instances.stream().filter(i -> i.address().equals(address)).findFirst().orElse(null);
	}

	public static Map<String, String> metadataOf(List<ServiceInstance> instances, String address) {
		ServiceInstance instance = byAddress(instances, address);
		return instance == null ? null : instance.metadata();
	}

	@Override
	public synchronized String toString() {
		return name + pushes;
	}
}
