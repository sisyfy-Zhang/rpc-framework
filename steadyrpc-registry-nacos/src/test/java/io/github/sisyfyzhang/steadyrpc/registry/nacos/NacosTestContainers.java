package io.github.sisyfyzhang.steadyrpc.registry.nacos;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

/**
 * 测试用 Nacos 容器的起法（契约测试与时序探针共用）。
 *
 * <p>端口映射不能用 Testcontainers 默认的随机端口：Nacos 2.x 客户端按"HTTP 端口 + 1000"推算 gRPC 端口，
 * 偏移量只能经 JVM 系统属性 {@code nacos.server.grpc.port.offset} 修改（本仓库禁止 System.setProperty）。
 * 所以挑一个本机空闲的 P（且 P+1000 也空闲），固定映射 P→8848、P+1000→9848。
 */
final class NacosTestContainers {

	static final String IMAGE = "nacos/nacos-server:v2.4.3";

	private NacosTestContainers() {
	}

	/** 未启动的 standalone 容器，HTTP 端口固定映射到 {@code httpPort}、gRPC 到 {@code httpPort + 1000}。 */
	@SuppressWarnings("resource")
	static GenericContainer<?> create(int httpPort) {
		return new GenericContainer<>(DockerImageName.parse(IMAGE))
				.withEnv("MODE", "standalone")
				.withExposedPorts(8848, 9848)
				.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
						new PortBinding(Ports.Binding.bindPort(httpPort), new ExposedPort(8848)),
						new PortBinding(Ports.Binding.bindPort(httpPort + 1000), new ExposedPort(9848))))
				.waitingFor(Wait.forHttp("/nacos/v1/console/health/readiness").forPort(8848)
						.withStartupTimeout(Duration.ofMinutes(3)));
	}

	/** 一个本机空闲端口 P，且 P+1000 也空闲（Nacos gRPC 端口 = HTTP 端口 + 1000）。 */
	static int freePortPair() {
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
