package com.feynman.rpc.core.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证注册中心注册失败不会拖垮一个已经绑定成功的监听端口。{@link FakeFailingRegistry}
 * （通过本测试模块的 {@code META-INF/rpc/...Registry} 配置为默认 Registry 扩展）在
 * {@code register()} 上总是抛异常——如果这个异常传播进和端口绑定共用的同一个 try/finally，
 * 会把 Netty 的事件循环一起关掉，即便端口已经绑定成功也会拖垮整个服务端进程，
 * 所以两者必须各自独立处理失败。
 */
class NettyInitializerResilienceTest {

	@Test
	void registrationFailureDoesNotTearDownTheBoundPort() throws Exception {
		int port = findFreePort();
		System.setProperty("rpc.port", String.valueOf(port));
		System.setProperty("rpc.weight", "1");

		Thread serverThread = new Thread(() -> new NettyInitializer().start(), "test-provider");
		serverThread.setDaemon(true);
		serverThread.start();

		assertTrue(waitForPortOpen(port, 5000), "port never came up");

		// 留出时间让那个（总会失败的）注册尝试真正跑一遍并抛出异常
		Thread.sleep(800);

		// 已绑定的端口必须依然存活，能继续接受连接
		assertTrue(isPortOpen(port), "port was torn down by the registration failure");
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static boolean waitForPortOpen(int port, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (isPortOpen(port)) {
				return true;
			}
			Thread.sleep(20);
		}
		return false;
	}

	private static boolean isPortOpen(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("localhost", port), 200);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
