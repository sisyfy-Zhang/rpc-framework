package io.github.sisyfyzhang.steadyrpc.core.testutil;

import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.InstanceMetadata;
import io.github.sisyfyzhang.steadyrpc.core.registry.ServiceInstance;

/** 测试里构造节点的简写。 */
public final class Instances {

	public static final ServiceKey KEY = new ServiceKey("com.example.Demo", "", "default");

	private Instances() {
	}

	public static ServiceInstance at(int port) {
		return at(port, 1);
	}

	public static ServiceInstance at(int port, int weight) {
		return new ServiceInstance(KEY, "127.0.0.1", port, Map.of(InstanceMetadata.WEIGHT, String.valueOf(weight)));
	}
}
