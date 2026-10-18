package com.feynman.rpc.starter;

import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryFactory;

/** 模拟一直不可达的注册中心：create 总是失败（RpcServer 会在后台无限退避重试）。 */
public class FailingRegistryFactory implements RegistryFactory {

	@Override
	public Registry create(RegistryConfig config) {
		throw new RegistryException("registry " + config.address() + " is unreachable (test)");
	}
}
