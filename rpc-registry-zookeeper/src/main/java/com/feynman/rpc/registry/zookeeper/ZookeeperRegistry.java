package com.feynman.rpc.registry.zookeeper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;

import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryWatcher;

/**
 * 基于 ZooKeeper 的 {@link Registry} 实现：连接建立、节点注册、子节点 watch 的重新注册
 * 全部收拢在这一个类里，客户端/服务端的其它代码都不需要直接接触 ZK。
 */
public class ZookeeperRegistry implements Registry {

	private static final String DEFAULT_ZK_ADDRESS = "localhost:2181";

	private final CuratorFramework client;

	public ZookeeperRegistry() {
		// 完整的外部化配置由 rpc-spring-boot-starter 提供；这里先留一个系统属性钩子，
		// 不然这个实现连测试都没法用真实地址的内嵌 ZK 来测（TestingServer 端口是随机分配的）。
		String zkAddress = System.getProperty("rpc.registry.zookeeper.address", DEFAULT_ZK_ADDRESS);
		RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
		this.client = CuratorFrameworkFactory.newClient(zkAddress, retryPolicy);
		this.client.start();
		try {
			client.blockUntilConnected(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void register(String path) {
		try {
			client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path);
		} catch (Exception e) {
			throw new RegistryException("Failed to register " + path, e);
		}
	}

	@Override
	public void unregister(String path) {
		try {
			client.delete().forPath(path);
		} catch (Exception e) {
			throw new RegistryException("Failed to unregister " + path, e);
		}
	}

	@Override
	public List<String> getChildren(String basePath) {
		try {
			return client.getChildren().forPath(basePath);
		} catch (Exception e) {
			throw new RegistryException("Failed to list children of " + basePath, e);
		}
	}

	@Override
	public void subscribe(String basePath, RegistryWatcher watcher) {
		// 消费端完全可能在任何 provider 注册之前就先启动、先订阅——这时 basePath 这个 znode
		// 本身还不存在，直接 getChildren 会以 NoNodeException 失败，所以订阅前先确保它存在。
		try {
			if (client.checkExists().forPath(basePath) == null) {
				client.createContainers(basePath);
			}
		} catch (Exception e) {
			throw new RegistryException("Failed to ensure base path exists: " + basePath, e);
		}

		CuratorWatcher[] curatorWatcherHolder = new CuratorWatcher[1];
		curatorWatcherHolder[0] = new CuratorWatcher() {
			@Override
			public void process(WatchedEvent event) throws Exception {
				resubscribeAndNotify(basePath, watcher, curatorWatcherHolder[0]);
			}
		};
		resubscribeAndNotify(basePath, watcher, curatorWatcherHolder[0]);
	}

	private void resubscribeAndNotify(String basePath, RegistryWatcher watcher, CuratorWatcher curatorWatcher) {
		try {
			List<String> children = client.getChildren().usingWatcher(curatorWatcher).forPath(basePath);
			watcher.onChange(children);
		} catch (Exception e) {
			throw new RegistryException("Failed to subscribe to " + basePath, e);
		}
	}
}
