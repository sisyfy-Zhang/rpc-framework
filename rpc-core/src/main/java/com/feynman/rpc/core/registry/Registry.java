package com.feynman.rpc.core.registry;

import java.util.List;

import com.feynman.rpc.core.extension.SPI;

/**
 * 服务注册中心扩展点。各实现分别在独立模块中（如 rpc-registry-zookeeper、rpc-registry-nacos），
 * 调用方只需引入自己实际依赖的那个后端。
 */
@SPI("zookeeper")
public interface Registry {

	/** 在 {@code path} 注册一个临时节点；本进程消失时节点应随之消失。 */
	void register(String path);

	/** 移除此前注册过的节点（若存在）。 */
	void unregister(String path);

	/** 返回 {@code basePath} 下当前的直接子节点名列表。 */
	List<String> getChildren(String basePath);

	/**
	 * 订阅 {@code basePath} 下子节点的变化。watcher 会立即收到一次当前子节点列表，
	 * 之后每次变化再收到一次。
	 */
	void subscribe(String basePath, RegistryWatcher watcher);
}
