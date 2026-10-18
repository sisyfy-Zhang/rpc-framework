package com.feynman.rpc.registry.nacos;

import java.util.LinkedHashMap;
import java.util.Map;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.ServiceInstance;

/**
 * 本项目服务模型到 Nacos 模型的映射，纯函数、不碰网络，可以脱离 Nacos 服务端单测：
 * <ul>
 *   <li>groupName = ServiceKey.group——Nacos 的分组本来就是"同名服务的隔离维度"，语义一致；</li>
 *   <li>serviceName = {@code interface:version}——Nacos 没有版本维度，版本只能并进服务名；</li>
 *   <li>节点元数据进 {@link Instance#getMetadata()}，权重同时写入 Nacos 原生的 weight 字段，
 *       控制台和 Nacos 自己的权重语义保持可见。</li>
 * </ul>
 */
final class NacosNaming {

	private NacosNaming() {
	}

	static String serviceName(ServiceKey key) {
		return key.interfaceName() + ":" + key.version();
	}

	static String groupName(ServiceKey key) {
		return key.group();
	}

	static Instance toNacos(ServiceInstance instance) {
		Instance nacos = new Instance();
		nacos.setIp(instance.host());
		nacos.setPort(instance.port());
		nacos.setWeight(instance.weight());
		nacos.setEphemeral(true);
		nacos.setMetadata(new LinkedHashMap<>(instance.metadata()));
		return nacos;
	}

	static ServiceInstance fromNacos(ServiceKey key, Instance nacos) {
		Map<String, String> metadata = nacos.getMetadata() == null ? Map.of() : nacos.getMetadata();
		return new ServiceInstance(key, nacos.getIp(), nacos.getPort(), metadata);
	}
}
