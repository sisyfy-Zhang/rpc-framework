package io.github.sisyfyzhang.steadyrpc.core.testing;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.testutil.SimpleTestSerializer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故意泄漏的夹具：启动一个 RpcServer 而不关闭。测试方法本身通过，但 {@link RpcLeakCheckExtension} 会在类结束后
 * 让它失败——这是"扩展对每个测试类自动生效且能抓到泄漏"的端到端证据。默认 {@code @Disabled}（否则构建失败、
 * 且泄漏的线程会污染后续测试类）。复现：去掉 {@code @Disabled} 后运行
 * {@code mvn -pl steadyrpc-core test -Dtest=LeakFixtureTest}，预期 {@code Tests run: 2, Failures: 1}（类级失败计为一条），
 * 错误信息以 {@code [RpcLeakCheck] io.github.sisyfyzhang.steadyrpc.core.testing.LeakFixtureTest:} 开头并列出 rpc-server-* 线程。
 */
@Disabled("deliberately leaks an RpcServer to prove RpcLeakCheckExtension fails the class; enable only to reproduce")
class LeakFixtureTest {

	public interface Ping {
		String ping();
	}

	@Test
	void startsAServerAndNeverClosesIt() {
		RpcServer server = RpcServer.builder().serializer(SimpleTestSerializer.NAME).port(0).host("127.0.0.1").build();
		server.export(Ping.class, () -> "pong");
		server.start();
		assertTrue(server.isRunning());
	}
}
