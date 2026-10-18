package com.feynman.rpc.core.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.feynman.rpc.core.ServiceKey;

/**
 * 某个服务的一个提供者节点：地址 + 元数据。元数据（见 {@link InstanceMetadata}）让消费端能按
 * 权重、协议版本做选择，而不必像旧实现那样把权重硬编码进节点名 {@code host:port#weight}。
 */
public record ServiceInstance(ServiceKey key, String host, int port, Map<String, String> metadata) {

	public ServiceInstance {
		if (key == null) {
			throw new IllegalArgumentException("key must not be null");
		}
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("host must not be blank");
		}
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("port out of range: " + port);
		}
		metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
	}

	/** {@code host:port}：连接池与负载统计的键。同一地址上的多个服务共享连接。 */
	public String address() {
		return host + ":" + port;
	}

	/** 注册权重，缺省或非法时按 1 处理——权重是调优参数，不该因为写错而让节点不可用。 */
	public int weight() {
		String raw = metadata.get(InstanceMetadata.WEIGHT);
		if (raw == null) {
			return 1;
		}
		try {
			return Math.max(1, Integer.parseInt(raw));
		} catch (NumberFormatException e) {
			return 1;
		}
	}
}
