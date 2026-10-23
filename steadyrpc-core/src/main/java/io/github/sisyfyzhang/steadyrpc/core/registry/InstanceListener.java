package io.github.sisyfyzhang.steadyrpc.core.registry;

import java.util.List;

/** {@link Registry#subscribe} 的回调：每次收到的都是该服务当前的全量提供者列表。 */
@FunctionalInterface
public interface InstanceListener {

	void onInstancesChanged(List<ServiceInstance> instances);
}
