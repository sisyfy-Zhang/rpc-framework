package com.feynman.rpc.registry.nacos;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.registry.tck.RegistryContractTest;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

/**
 * Nacos 实现跑 Registry 契约（TCK）。后端二选一（DESIGN 3.7：需要真实中间件的测试必须门控，不得依赖本机已起的服务）：
 * <ol>
 *   <li>环境变量 {@code RPC_TEST_NACOS_ADDRESS} 或系统属性 {@code rpc.test.nacos.address} 指定的外部 Nacos；</li>
 *   <li>否则 Docker 可用时用 Testcontainers 起 {@code nacos/nacos-server:v2.4.3}（standalone）；</li>
 *   <li>两者都没有：整个类 skipped（不是失败）。</li>
 * </ol>
 *
 * <p>端口映射不能用 Testcontainers 默认的随机端口：Nacos 2.x 客户端按"HTTP 端口 + 1000"推算 gRPC 端口，
 * 偏移量只能经 JVM 系统属性 {@code nacos.server.grpc.port.offset} 修改（本仓库禁止 System.setProperty）。
 * 所以挑一个本机空闲的 P（且 P+1000 也空闲），固定映射 P→8848、P+1000→9848。
 *
 * <p>时间窗按 Nacos 的 AP 模型给：临时实例在客户端连接断开 / 心跳超时后摘除（允许 40s）；不可达时 register 受
 * Nacos 客户端自身的请求超时与重试约束（声明 15s）。Nacos 线程名不可控，线程泄漏用例跳过。
 */
class NacosRegistryContractTest extends RegistryContractTest {

	private static final String IMAGE = "nacos/nacos-server:v2.4.3";

	private GenericContainer<?> container;
	private String address;

	@Override
	protected RegistryFactory factory() {
		return new NacosRegistryFactory();
	}

	@Override
	@SuppressWarnings("resource")
	protected void startBackend() {
		String external = externalAddress();
		if (external != null) {
			address = external;
			return;
		}
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
				"Nacos contract test skipped: Docker is not available and RPC_TEST_NACOS_ADDRESS / -Drpc.test.nacos.address is not set");
		int httpPort = freePortPair();
		container = new GenericContainer<>(DockerImageName.parse(IMAGE))
				.withEnv("MODE", "standalone")
				.withExposedPorts(8848, 9848)
				.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
						new PortBinding(Ports.Binding.bindPort(httpPort), new ExposedPort(8848)),
						new PortBinding(Ports.Binding.bindPort(httpPort + 1000), new ExposedPort(9848))))
				.waitingFor(Wait.forHttp("/nacos/v1/console/health/readiness").forPort(8848)
						.withStartupTimeout(Duration.ofMinutes(3)));
		container.start();
		address = "127.0.0.1:" + httpPort;
	}

	@Override
	protected void stopBackend() {
		if (container != null) {
			container.stop();
		}
	}

	@Override
	protected RegistryConfig config() {
		return RegistryConfig.nacos(address);
	}

	@Override
	protected RegistryConfig unreachableConfig() {
		return RegistryConfig.nacos("127.0.0.1:" + freePortPair());
	}

	@Override
	protected Duration disappearanceWindow() {
		return Duration.ofSeconds(40);
	}

	@Override
	protected Duration registerFailureBound() {
		return Duration.ofSeconds(15);
	}

	@Override
	protected Optional<String> threadNamePrefix() {
		return Optional.empty();
	}

	@Override
	protected Duration pushTimeout() {
		return Duration.ofSeconds(20);
	}

	@Override
	protected Duration quietPeriod() {
		return Duration.ofSeconds(3);
	}

	/**
	 * Testcontainers 起的后端可以原地停止再启动：端口是固定映射的（见类注释），新容器在同一地址提供服务（数据为空，
	 * 用例在恢复之后才注册提供者，不依赖旧数据）。外部 Nacos 不归测试管，不能重启，用例跳过。
	 * 本机无 Docker 时整个类 skipped，这条用例尚未实跑（见 DESIGN §8）。
	 */
	@Override
	protected boolean supportsBackendRestart() {
		return container != null;
	}

	@Override
	protected void stopBackendForRestart() {
		container.stop();
	}

	@Override
	protected void restartBackend() {
		container.start();
	}

	/** subscribe 最多等 connect-wait-ms（默认 3000ms），加调度余量。 */
	@Override
	protected Duration subscribeReturnBound() {
		return Duration.ofMillis(Long.parseLong(NacosRegistry.DEFAULT_CONNECT_WAIT_MS) + 2000);
	}

	private static String externalAddress() {
		String fromProperty = System.getProperty("rpc.test.nacos.address");
		String value = fromProperty != null && !fromProperty.isBlank() ? fromProperty : System.getenv("RPC_TEST_NACOS_ADDRESS");
		return value == null || value.isBlank() ? null : value;
	}

	/** 一个本机空闲端口 P，且 P+1000 也空闲（Nacos gRPC 端口 = HTTP 端口 + 1000）。 */
	private static int freePortPair() {
		for (int i = 0; i < 50; i++) {
			int port;
			try (ServerSocket socket = new ServerSocket(0)) {
				port = socket.getLocalPort();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			if (port + 1000 > 65535) {
				continue;
			}
			try (ServerSocket grpc = new ServerSocket(port + 1000)) {
				return port;
			} catch (IOException e) {
				// P+1000 被占，换一个
			}
		}
		throw new IllegalStateException("no free port pair (P, P+1000) found");
	}
}
