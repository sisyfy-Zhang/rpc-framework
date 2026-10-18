package com.feynman.rpc.it;

import java.io.InputStream;
import java.util.Properties;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.Extension;

import com.feynman.rpc.core.testing.RpcLeakCheckExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住"泄漏断言对本模块每个测试类自动生效"的接线：rpc-core 的 test-jar 带来扩展类与它的服务注册文件，
 * 本模块的 junit-platform.properties 打开自动检测。任何一环被误删（例如有人把 test-jar 依赖去掉、或复制了一份
 * 不带开关的 junit-platform.properties），这里先失败，而不是让泄漏断言静默失效。
 */
class LeakCheckWiringTest {

	@Test
	void leakCheckExtensionIsRegisteredForAutodetection() throws Exception {
		boolean registered = ServiceLoader.load(Extension.class).stream()
				.anyMatch(provider -> provider.type() == RpcLeakCheckExtension.class);
		assertTrue(registered, "META-INF/services/org.junit.jupiter.api.extension.Extension must list RpcLeakCheckExtension");

		Properties junit = new Properties();
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("junit-platform.properties")) {
			assertNotNull(in, "junit-platform.properties missing");
			junit.load(in);
		}
		assertEquals("true", junit.getProperty("junit.jupiter.extensions.autodetection.enabled"));
	}

	/**
	 * 只跑到 test 阶段（{@code mvn test}）时 test-jar 还没打出来，Maven 会把 rpc-core 的整个 target/test-classes 放进本模块的
	 * classpath，连带 core 测试专用的 SPI 文件（假序列化器、内存注册中心），悄悄改变这里的协商与注册行为。
	 * 遇到这种情况直接失败并说明原因，而不是让集成测试在错误的环境里"通过"。
	 */
	@Test
	void coreTestClassesAreNotOnTheClasspath() throws Exception {
		var resources = getClass().getClassLoader().getResources("META-INF/rpc/com.feynman.rpc.core.serialize.Serializer");
		while (resources.hasMoreElements()) {
			String url = resources.nextElement().toString();
			assertTrue(!url.contains("rpc-core/target/test-classes"),
					"rpc-core's test classes are on the classpath (" + url + "): run the integration tests with "
							+ "'mvn verify' / 'mvn install' so that rpc-core's test-jar (leak check only) is used instead");
		}
	}
}
