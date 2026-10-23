package io.github.sisyfyzhang.steadyrpc.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;
import io.github.sisyfyzhang.steadyrpc.core.testutil.FailingRegistryFactory;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcServerLifecycleTest {

	public interface Ping {
		String ping(String s);
	}

	@Test
	void startReturnsAfterBindAndRegistryFailureDoesNotTearDownThePort() throws Exception {
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME)
				.port(0)
				.host("127.0.0.1")
				.registry(RegistryConfig.of("failing", "nowhere"))
				.build();
		server.export(Ping.class, s -> s);

		long start = System.nanoTime();
		server.start();
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
		// 旧实现在 start 里 closeFuture().sync() 永不返回；注册在专用线程上后台重试，start 不等它
		assertTrue(elapsedMillis < 3000, "start() blocked for " + elapsedMillis + "ms");
		assertFalse(server.isRegistered());

		int port = server.port();
		assertTrue(isPortOpen(port), "port never came up");
		// 等注册重试真正跑过几轮并失败
		int before = FailingRegistryFactory.REGISTER_ATTEMPTS.get();
		await().atMost(5, TimeUnit.SECONDS).until(() -> FailingRegistryFactory.REGISTER_ATTEMPTS.get() > before);
		assertTrue(isPortOpen(port), "port was torn down by the registration failure");

		server.close();
		assertFalse(isPortOpen(port), "port still open after close()");
		assertFalse(server.isRunning());
	}

	@Test
	void closeIsIdempotentAndBlocksFurtherUse() {
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").build();
		server.export(Ping.class, s -> s);
		server.start();
		server.close();
		server.close();
		assertThrows(IllegalStateException.class, server::start);
		assertThrows(IllegalStateException.class, () -> server.export(Ping.class, s -> s));
	}

	/**
	 * 协议 v2 的信封携带 version / group / paramSig：同一接口按不同 version 暴露、重载方法都可以 export
	 * （任务 1 在 v1 下拒绝这两种情况）；只有完全相同的 interface:version:group 重复暴露仍被拒绝。
	 */
	@Test
	void protocolV2AllowsVersionsAndOverloadsButRejectsDuplicateKeys() {
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).build();
		server.export(Ping.class, s -> s);
		server.export(Ping.class, s -> s, io.github.sisyfyzhang.steadyrpc.core.config.ServiceOptions.defaults().version("2"));
		server.export(Ping.class, s -> s, io.github.sisyfyzhang.steadyrpc.core.config.ServiceOptions.defaults().group("gray"));
		server.export(Overloaded.class, new Overloaded() {
			@Override
			public String a(String s) {
				return s;
			}

			@Override
			public String a(Integer i) {
				return "";
			}
		});
		assertThrows(IllegalStateException.class,
				() -> server.export(Ping.class, s -> s, io.github.sisyfyzhang.steadyrpc.core.config.ServiceOptions.defaults().version("2")));
		assertEquals(4, server.exportedServices().size());
		server.close();
	}

	public interface Overloaded {
		String a(String s);

		String a(Integer i);
	}

	private static boolean isPortOpen(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
