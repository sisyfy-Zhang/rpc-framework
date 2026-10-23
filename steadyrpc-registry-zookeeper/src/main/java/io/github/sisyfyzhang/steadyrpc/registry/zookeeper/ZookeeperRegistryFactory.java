package io.github.sisyfyzhang.steadyrpc.registry.zookeeper;

import io.github.sisyfyzhang.steadyrpc.core.registry.Registry;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryFactory;

/** 无状态工厂：每次 {@link #create} 都新建一个独立的 Curator 会话，由调用方持有并关闭。 */
public class ZookeeperRegistryFactory implements RegistryFactory {

	@Override
	public Registry create(RegistryConfig config) {
		return new ZookeeperRegistry(config);
	}
}
