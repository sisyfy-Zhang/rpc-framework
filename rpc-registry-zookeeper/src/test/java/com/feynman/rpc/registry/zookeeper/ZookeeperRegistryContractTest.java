package com.feynman.rpc.registry.zookeeper;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;

import org.apache.curator.test.TestingServer;

import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.registry.tck.RegistryContractTest;

/**
 * ZooKeeper 实现跑 Registry 契约（TCK）。后端是 curator-test 的进程内 {@link TestingServer}，不依赖 Docker。
 * 时间窗按 ZK 的一致性模型给：会话关闭即删除临时节点（允许 5s）；不可达时 register 最多等 connect-wait-ms（1s）。
 */
class ZookeeperRegistryContractTest extends RegistryContractTest {

	private TestingServer server;

	@Override
	protected RegistryFactory factory() {
		return new ZookeeperRegistryFactory();
	}

	@Override
	protected void startBackend() throws Exception {
		server = new TestingServer(true);
	}

	@Override
	protected void stopBackend() throws Exception {
		if (server != null) {
			server.close();
		}
	}

	@Override
	protected RegistryConfig config() {
		return RegistryConfig.zookeeper(server.getConnectString());
	}

	@Override
	protected RegistryConfig unreachableConfig() {
		return RegistryConfig.zookeeper("127.0.0.1:" + unusedPort());
	}

	@Override
	protected Duration disappearanceWindow() {
		return Duration.ofSeconds(5);
	}

	@Override
	protected Duration registerFailureBound() {
		// connect-wait-ms 默认 1000ms，加上调度余量
		return Duration.ofMillis(2500);
	}

	@Override
	protected Optional<String> threadNamePrefix() {
		return Optional.of("rpc-registry-zk-");
	}

	/** TestingServer 可以原地停止、在同一端口重启（数据目录保留）：订阅在 ZK 停机期间发起、恢复后补推。 */
	@Override
	protected boolean supportsBackendRestart() {
		return true;
	}

	@Override
	protected void stopBackendForRestart() throws Exception {
		server.stop();
	}

	@Override
	protected void restartBackend() throws Exception {
		server.restart();
	}

	/**
	 * Curator 后台操作在没连上时先每秒重排、满 connection-timeout-ms（缺省 5000）后按 ExponentialBackoffRetry(500ms, 3 次)
	 * 再试三次（退避上限 500 + 1500 + 3500ms）才放弃；15s 让订阅时发出的那次后台操作肯定已经放弃过。
	 */
	@Override
	protected Duration clientRetryHorizon() {
		return Duration.ofSeconds(15);
	}

	static int unusedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
