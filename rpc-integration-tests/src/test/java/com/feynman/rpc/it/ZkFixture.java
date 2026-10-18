package com.feynman.rpc.it;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.RegistryConfig;

/**
 * 进程内 ZooKeeper（curator-test TestingServer，随机端口）+ 一个只读的检查客户端。
 * 集成测试不依赖 Docker，也不依赖本机已起的 ZK。
 */
final class ZkFixture implements AutoCloseable {

	private final TestingServer server;
	private final CuratorFramework inspector;

	ZkFixture() throws Exception {
		server = new TestingServer(true);
		inspector = CuratorFrameworkFactory.newClient(server.getConnectString(), new RetryOneTime(100));
		inspector.start();
		inspector.blockUntilConnected(5, TimeUnit.SECONDS);
	}

	String connectString() {
		return server.getConnectString();
	}

	RegistryConfig registry() {
		return RegistryConfig.zookeeper(server.getConnectString());
	}

	static String providersPath(ServiceKey key) {
		return "/rpc/" + key + "/providers";
	}

	/** 某服务当前在 ZK 上的提供者节点名（host:port）；路径不存在时视为空。 */
	List<String> providers(ServiceKey key) throws Exception {
		String path = providersPath(key);
		if (inspector.checkExists().forPath(path) == null) {
			return List.of();
		}
		return inspector.getChildren().forPath(path);
	}

	String nodeData(ServiceKey key, String address) throws Exception {
		return new String(inspector.getData().forPath(providersPath(key) + "/" + address), java.nio.charset.StandardCharsets.UTF_8);
	}

	/** 线程快照里名字以 rpc- 开头的存活线程（框架自己的 IO / 业务 / 注册中心线程）。 */
	static Set<String> rpcThreads() {
		Set<String> names = new TreeSet<>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && thread.getName().startsWith("rpc-")) {
				names.add(thread.getName());
			}
		}
		return names;
	}

	@Override
	public void close() throws Exception {
		inspector.close();
		server.close();
	}
}
