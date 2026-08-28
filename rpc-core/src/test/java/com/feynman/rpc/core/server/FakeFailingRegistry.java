package com.feynman.rpc.core.server;

import java.util.List;

import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryWatcher;

/** 仅供测试用的 {@link Registry}：{@link #register} 总是失败，用来验证注册失败不会再拖垮
 * 一个已经绑定成功的 Netty 监听端口（见 NettyInitializerResilienceTest）。 */
public class FakeFailingRegistry implements Registry {

	@Override
	public void register(String path) {
		throw new RegistryException("simulated registry outage", new RuntimeException("boom"));
	}

	@Override
	public void unregister(String path) {
		// 空操作
	}

	@Override
	public List<String> getChildren(String basePath) {
		return List.of();
	}

	@Override
	public void subscribe(String basePath, RegistryWatcher watcher) {
		watcher.onChange(List.of());
	}
}
