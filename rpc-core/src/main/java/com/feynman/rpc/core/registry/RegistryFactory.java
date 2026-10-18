package com.feynman.rpc.core.registry;

import com.feynman.rpc.core.extension.SPI;

/**
 * 注册中心 SPI 的入口。SPI 只负责"按名字找到实现类"，因此这里是无状态工厂：ExtensionLoader
 * 可以放心缓存它；真正有状态的 {@link Registry}（连接、会话）每次 {@link #create} 新建，
 * 由调用方持有和关闭。扩展名即 {@link RegistryConfig#type()}。
 */
@SPI("zookeeper")
public interface RegistryFactory {

	Registry create(RegistryConfig config);
}
