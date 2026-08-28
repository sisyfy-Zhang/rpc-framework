package com.feynman.rpc.registry.nacos;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;

import com.feynman.rpc.core.registry.Registry;
import com.feynman.rpc.core.registry.RegistryException;
import com.feynman.rpc.core.registry.RegistryWatcher;

/**
 * 基于 Nacos 的 {@link Registry} 实现。Nacos 没有 ZooKeeper 那种层级化 znode 树——只有一个扁平的
 * "服务名"加一份实例列表——所以 {@link NacosPath} 把本项目
 * {@code register("/rpc/host:port#weight")} 的路径模型映射到 Nacos 的
 * {@code registerInstance(serviceName, ip, port)} 模型上；{@link #getChildren}/{@link #subscribe}
 * 再把实例格式化回 ZooKeeper 子节点名同款的 {@code host:port#weight} 字符串，这样调用方
 * （如 {@code TCPClient}）完全不需要关心当前用的是哪个注册中心后端。
 */
public class NacosRegistry implements Registry {

	private static final String DEFAULT_SERVER_ADDR = "localhost:8848";

	private final NamingService namingService;

	public NacosRegistry() {
		String serverAddr = System.getProperty("rpc.registry.nacos.address", DEFAULT_SERVER_ADDR);
		try {
			this.namingService = NacosFactory.createNamingService(serverAddr);
		} catch (NacosException e) {
			throw new RegistryException("Failed to create Nacos naming service at " + serverAddr, e);
		}
	}

	@Override
	public void register(String path) {
		NacosPath parsed = NacosPath.parse(path);
		try {
			Instance instance = new Instance();
			instance.setIp(parsed.host);
			instance.setPort(parsed.port);
			instance.setWeight(parsed.weight);
			namingService.registerInstance(parsed.serviceName, instance);
		} catch (NacosException e) {
			throw new RegistryException("Failed to register " + path, e);
		}
	}

	@Override
	public void unregister(String path) {
		NacosPath parsed = NacosPath.parse(path);
		try {
			namingService.deregisterInstance(parsed.serviceName, parsed.host, parsed.port);
		} catch (NacosException e) {
			throw new RegistryException("Failed to unregister " + path, e);
		}
	}

	@Override
	public List<String> getChildren(String basePath) {
		String serviceName = NacosPath.serviceNameOf(basePath);
		try {
			return toChildNames(namingService.getAllInstances(serviceName));
		} catch (NacosException e) {
			throw new RegistryException("Failed to list children of " + basePath, e);
		}
	}

	@Override
	public void subscribe(String basePath, RegistryWatcher watcher) {
		String serviceName = NacosPath.serviceNameOf(basePath);
		try {
			// 立即推一次当前状态，和 ZookeeperRegistry.subscribe() 的语义保持一致——
			// 订阅这个动作本身就应该先给一份"现在是什么样"，而不是只等下一次变化。
			watcher.onChange(toChildNames(namingService.getAllInstances(serviceName)));

			namingService.subscribe(serviceName, new EventListener() {
				@Override
				public void onEvent(Event event) {
					if (event instanceof NamingEvent namingEvent) {
						watcher.onChange(toChildNames(namingEvent.getInstances()));
					}
				}
			});
		} catch (NacosException e) {
			throw new RegistryException("Failed to subscribe to " + basePath, e);
		}
	}

	private static List<String> toChildNames(List<Instance> instances) {
		List<String> names = new ArrayList<>();
		for (Instance instance : instances) {
			names.add(NacosPath.toChildName(instance.getIp(), instance.getPort(), (int) instance.getWeight()));
		}
		return names;
	}
}
