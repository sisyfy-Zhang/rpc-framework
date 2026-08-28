package com.feynman.rpc.starter;

import java.util.List;

import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryWatcher;

/** 仅供测试用的空操作 {@link Registry}——本模块 classpath 上没有真实注册中心后端
 * （引入 ZK/Curator 只为测试 Spring 装配不划算），但 {@code NettyInitializer.start()}
 * 在 context refresh 时仍会解析出一个 Registry，所以总得有个实现可用。 */
public class FakeRegistry implements Registry {

	@Override
	public void register(String path) {
		// 空操作
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
