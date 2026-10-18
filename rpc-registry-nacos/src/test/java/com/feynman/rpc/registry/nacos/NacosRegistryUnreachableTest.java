package com.feynman.rpc.registry.nacos;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.ServiceKey;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.registry.tck.PushRecorder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不需要 Docker 的那一半订阅契约：Nacos 不可达时 subscribe 不抛异常、在 connect-wait-ms 上限内返回、不推任何列表
 * （"没连上"不能当成"没有提供者"）。"恢复后补推"需要真实后端，由 TCK 的
 * {@code subscribeWhileBackendUnreachableThenRecovers} 在 Docker 可用时验证。
 */
class NacosRegistryUnreachableTest {

	@Test
	void subscribeAgainstAnUnreachableServerReturnsWithinConnectWaitWithoutPushing() throws Exception {
		RegistryConfig config = RegistryConfig.nacos("127.0.0.1:" + unusedPort())
				.withProperty(NacosRegistry.CONNECT_WAIT_MS, "1000");
		try (NacosRegistry registry = new NacosRegistry(config)) {
			PushRecorder recorder = new PushRecorder("consumer");
			ServiceKey key = new ServiceKey("tck.Unreachable", "1.0", "tck");

			long start = System.nanoTime();
			assertDoesNotThrow(() -> registry.subscribe(key, recorder));
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			System.out.printf("[Nacos unreachable] subscribe returned after %dms (connect-wait-ms=1000) without throwing; pushes=%s%n",
					elapsed, recorder);
			assertTrue(elapsed < 3000, "subscribe blocked " + elapsed + "ms");
			Thread.sleep(1500); // 后台重试至少又跑了一轮
			assertEquals(0, recorder.count(), "nothing may be pushed while Nacos is unreachable: " + recorder);
		}
	}

	private static int unusedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
