package io.github.sisyfyzhang.steadyrpc.it;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实的代理 + 传输链路验证异步优先派发：RpcServer / RpcClient 都是实例，经进程内 TestingServer 走完整的
 * 注册 → 订阅 → 调用路径。
 */
class AsyncInvocationTest {

	public interface AsyncEcho {
		CompletableFuture<String> echo(String s);
	}

	static final class AsyncEchoImpl implements AsyncEcho {
		@Override
		public CompletableFuture<String> echo(String s) {
			return CompletableFuture.supplyAsync(() -> {
				try {
					Thread.sleep(1200); // 模拟耗时的服务端处理，时长远超一次性的 Netty/JIT 预热噪声
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "echo:" + s;
			});
		}
	}

	@Test
	void asyncMethodReturnsWithoutBlockingCallerThread() throws Exception {
		try (ZkFixture zk = new ZkFixture();
				RpcServer server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).build()) {
			server.export(AsyncEcho.class, new AsyncEchoImpl());
			server.start();
			server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求

			try (RpcClient client = RpcClient.builder().registry(zk.registry()).build()) {
				AsyncEcho echo = client.reference(AsyncEcho.class);

				long start = System.nanoTime();
				CompletableFuture<String> future = echo.echo("world");
				long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

				// 调用本身必须在服务端模拟的 1200ms 延迟结束前就返回
				// （留了足够余量应对首次网络调用的一次性 Netty/JIT 类加载开销）
				assertTrue(elapsedMillis < 600, "caller thread was blocked for " + elapsedMillis + "ms, expected async return");
				assertEquals("echo:world", future.get(5, TimeUnit.SECONDS));
			}
		}
	}
}
