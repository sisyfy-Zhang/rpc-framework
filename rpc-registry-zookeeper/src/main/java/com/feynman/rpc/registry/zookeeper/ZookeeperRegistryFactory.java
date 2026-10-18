package com.feynman.rpc.registry.zookeeper;

import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryFactory;

/** 无状态工厂：每次 {@link #create} 都新建一个独立的 Curator 会话，由调用方持有并关闭。 */
public class ZookeeperRegistryFactory implements RegistryFactory {

	@Override
	public Registry create(RegistryConfig config) {
		return new ZookeeperRegistry(config);
	}
}
