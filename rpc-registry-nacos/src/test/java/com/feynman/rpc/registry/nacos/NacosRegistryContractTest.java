package com.feynman.rpc.registry.nacos;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.registry.RegistryFactory;
import com.feynman.rpc.registry.tck.RegistryContractTest;

/**
 * Nacos 实现跑 Registry 契约（TCK）。后端二选一（DESIGN 3.7：需要真实中间件的测试必须门控，不得依赖本机已起的服务）：
 * <ol>
 *   <li>环境变量 {@code RPC_TEST_NACOS_ADDRESS} 或系统属性 {@code rpc.test.nacos.address} 指定的外部 Nacos；</li>
 *   <li>否则 Docker 可用时用 Testcontainers 起 {@code nacos/nacos-server:v2.4.3}（standalone）；</li>
 *   <li>两者都没有：整个类 skipped（不是失败）。</li>
 * </ol>
 *
 * <p>端口映射不能用 Testcontainers 默认的随机端口（Nacos 客户端按"HTTP 端口 + 1000"推算 gRPC 端口），
 * 固定映射 P→8848、P+1000→9848，起法见 {@link NacosTestContainers}（与时序探针 NacosTimingProbeTest 共用）。
 *
 * <p>时间窗按 Nacos 的 AP 模型给：临时实例在客户端连接断开 / 心跳超时后摘除（允许 40s）；不可达时 register 受
 * Nacos 客户端自身的请求超时与重试约束（声明 15s）。Nacos 线程名不可控，线程泄漏用例跳过。
 */
class NacosRegistryContractTest extends RegistryContractTest {

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
		int httpPort = NacosTestContainers.freePortPair();
		container = NacosTestContainers.create(httpPort);
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
		return RegistryConfig.nacos("127.0.0.1:" + NacosTestContainers.freePortPair());
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

	/** Nacos 客户端的 gRPC 重连本身不放弃；仍让后端停机超过一次注册失败上限（15s），覆盖"停机很久才恢复"。 */
	@Override
	protected Duration clientRetryHorizon() {
		return Duration.ofSeconds(15);
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
}
