package com.feynman.rpc.it;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.ServiceKey;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-2 的真实信号版：provider 是独立 JVM（{@link SigtermProviderMain}，即 rpc-examples 的 ProviderApplication + 一个慢服务），
 * 连本测试进程里的 ZooKeeper（curator-test TestingServer）。发起一个 1.5s 的慢调用，确认它已进入服务端后发 SIGTERM，断言：
 * <ul>
 *   <li>慢调用正常返回（在途请求被排空，而不是随进程一起死掉）；</li>
 *   <li>注册节点在慢调用完成<b>之前</b>就消失（先摘注册、后排空）；</li>
 *   <li>进程在 shutdown-timeout（5s）内退出，退出码 143（= 128 + SIGTERM：JVM 跑完关闭钩子后按信号退出的标准退出码；
 *       137 才表示被 SIGKILL 强杀），且日志里有 RpcServer "closed ... all in-flight requests completed"。</li>
 * </ul>
 * 只在 Linux 上运行：Windows 的 {@code Process.destroy()} 是 TerminateProcess，不触发 JVM 关闭钩子；
 * 在 Unix 上 {@code destroy()} 发送的就是 SIGTERM。CI 的 sigterm 作业单独跑这个类。
 */
@EnabledOnOs(OS.LINUX)
class SigtermShutdownTest {

	private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
	private static final int SLOW_MILLIS = 1500;

	@Test
	void sigtermUnregistersDrainsInFlightCallsAndExits() throws Exception {
		try (ZkFixture zk = new ZkFixture()) {
			Path log = Files.createTempFile("sigterm-provider-", ".log");
			Process provider = startProvider(zk, log);
			try {
				ServiceKey key = ServiceKey.of(GracefulShutdownTest.SlowEcho.class);
				await("provider registers in ZooKeeper").atMost(60, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
						.until(() -> !provider.isAlive() || !zk.providers(key).isEmpty());
				assertTrue(provider.isAlive(), () -> "provider exited early:\n" + read(log));
				String address = zk.providers(key).get(0);

				try (RpcClient client = RpcClient.builder().registry(zk.registry()).defaultTimeout(Duration.ofSeconds(10)).build();
						GracefulShutdownTest.DisappearanceWatch watch = new GracefulShutdownTest.DisappearanceWatch(zk, key, address)) {
					GracefulShutdownTest.SlowEcho echo = client.reference(GracefulShutdownTest.SlowEcho.class);
					assertEquals("pong", echo.ping());

					AtomicLong slowDoneAt = new AtomicLong();
					CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> echo.slow(SLOW_MILLIS));
					slow.whenComplete((r, e) -> slowDoneAt.set(System.nanoTime()));
					await("slow call reached the provider").atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
							.until(() -> read(log).contains(SigtermProviderMain.SLOW_STARTED));

					long sigtermAt = System.nanoTime();
					provider.destroy(); // Unix: SIGTERM

					assertEquals("slow-" + SLOW_MILLIS, slow.get(10, TimeUnit.SECONDS), "in-flight call must complete normally");
					assertTrue(provider.waitFor(SHUTDOWN_TIMEOUT.toMillis() + 5000, TimeUnit.MILLISECONDS),
							() -> "provider did not exit:\n" + read(log));
					long exitedAfter = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sigtermAt);
					long goneAfter = TimeUnit.NANOSECONDS.toMillis(watch.goneAt() - sigtermAt);
					long slowDoneAfter = TimeUnit.NANOSECONDS.toMillis(slowDoneAt.get() - sigtermAt);
					System.out.printf("[A0-2 SIGTERM] node gone at %dms, in-flight call done at %dms, process exited at %dms with code %d%n",
							goneAfter, slowDoneAfter, exitedAfter, provider.exitValue());

					String output = read(log);
					assertTrue(watch.goneAt() != 0 && watch.goneAt() - slowDoneAt.get() < 0,
							"the registry node must disappear before the in-flight call completes:\n" + output);
					assertTrue(exitedAfter < SHUTDOWN_TIMEOUT.toMillis(), "exited after " + exitedAfter + "ms:\n" + output);
					assertEquals(143, provider.exitValue(), "128 + SIGTERM after shutdown hooks ran (137 would mean SIGKILL):\n" + output);
					assertTrue(output.contains("all in-flight requests completed"), "graceful close did not run:\n" + output);
					assertTrue(zk.providers(key).isEmpty());
				}
			} finally {
				if (provider.isAlive()) {
					provider.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
				}
			}
		}
	}

	private static Process startProvider(ZkFixture zk, Path log) throws IOException {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		// surefire 用 manifest-only jar 启动测试 JVM 时，java.class.path 只有那个 booter jar；完整测试 classpath 在这个属性里
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		List<String> command = new ArrayList<>(List.of(java, "-cp", classpath, SigtermProviderMain.class.getName(),
				"--rpc.registry.address=" + zk.connectString(),
				"--rpc.server.port=0",
				"--rpc.server.host=127.0.0.1",
				"--rpc.server.shutdown-timeout=" + SHUTDOWN_TIMEOUT.toMillis() + "ms",
				"--rpc.server.deregister-grace=300ms",
				"--spring.main.banner-mode=off"));
		return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
	}

	private static String read(Path log) {
		try {
			return Files.readString(log, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "(cannot read " + log + ": " + e + ")";
		}
	}
}
