package io.github.sisyfyzhang.steadyrpc.it;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.starter.RpcServerHealthIndicator;

/**
 * health 组件 {@code rpc} 的契约是"能服务、也能被发现"：ZooKeeper 会话丢失后临时节点随之消失，此时必须报
 * OUT_OF_SERVICE（而不是因为"曾经注册成功过"继续报 UP），ZK 恢复、节点在新会话上重建之后回到 UP。
 *
 * <p>做法：注册成功后停掉 TestingServer，Curator 在会话超时（TestingServer 缺省 tickTime 3000ms，协商出的会话超时
 * 下限 6s）后判定 LOST；ZK 停着期间保持 OUT_OF_SERVICE；原地重启 ZK 后在 {@link UnreachableRegistryTest#CATCH_UP_BOUND_SECONDS}
 * 内回到 UP，节点属于新会话。
 */
class RegistrationLossHealthTest {

	public interface Echo {
		String echo(String s);
	}

	@Test
	void healthTurnsOutOfServiceWhenTheZookeeperSessionIsLost_andUpAgainAfterReRegistration() throws Exception {
		try (TestingServer zk = new TestingServer(true)) {
			RpcServer server = RpcServer.builder().port(0).host("127.0.0.1")
					.registry(RegistryConfig.zookeeper(zk.getConnectString()).withProperty("session-timeout-ms", "2000"))
					.build();
			try {
				server.export(Echo.class, s -> "echo:" + s);
				server.start();
				server.registered().get(10, TimeUnit.SECONDS);
				RpcServerHealthIndicator health = new RpcServerHealthIndicator(server);
				assertEquals(Status.UP, health.health().getStatus());

				long stoppedAt = System.nanoTime();
				zk.stop();
				await("OUT_OF_SERVICE after the session is lost").atMost(UnreachableRegistryTest.CATCH_UP_BOUND_SECONDS, TimeUnit.SECONDS)
						.pollInterval(Duration.ofMillis(100))
						.until(() -> Status.OUT_OF_SERVICE.equals(health.health().getStatus()));
				long lostMs = (System.nanoTime() - stoppedAt) / 1_000_000;
				assertFalse(server.isRegistered());
				assertTrue(server.registered().isDone(), "registered() keeps meaning 'was fully registered once'");
				Thread.sleep(1000);
				assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus(), "must stay OUT_OF_SERVICE while ZooKeeper is down");

				long restartedAt = System.nanoTime();
				zk.restart();
				await("UP again after re-registration").atMost(UnreachableRegistryTest.CATCH_UP_BOUND_SECONDS, TimeUnit.SECONDS)
						.pollInterval(Duration.ofMillis(100))
						.until(() -> Status.UP.equals(health.health().getStatus()));
				long restoredMs = (System.nanoTime() - restartedAt) / 1_000_000;
				try (CuratorFramework inspector = CuratorFrameworkFactory.newClient(zk.getConnectString(), new RetryOneTime(100))) {
					inspector.start();
					assertTrue(inspector.blockUntilConnected(5, TimeUnit.SECONDS));
					assertNotNull(inspector.checkExists().forPath("/rpc/" + ServiceKey.of(Echo.class) + "/providers/127.0.0.1:" + server.port()),
							"node must exist again once health is UP");
				}
				System.out.printf("[registration-loss] OUT_OF_SERVICE %dms after ZooKeeper stopped; UP %dms after it restarted%n",
						lostMs, restoredMs);
			} finally {
				server.close();
			}
		}
	}
}
