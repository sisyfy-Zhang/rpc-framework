package io.github.sisyfyzhang.steadyrpc.it;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布到 Central 的 8 个 jar 的 MANIFEST：{@code Automatic-Module-Name} 为模块根包名（放在模块路径上时模块名稳定，不从 jar 文件名推导），
 * 并带 {@code Implementation-Title / Version / Vendor}（steadyrpc-parent 的 maven-jar-plugin 配置）。
 *
 * <p>核对的是本模块 classpath 上真实的 jar（{@code mvn verify / install} 时是兄弟模块 target/ 下刚打出的包，或本地仓库里同一构建装进去的包）。
 * steadyrpc-registry-tck 不在本模块的依赖里（把它的抽象契约测试放上 classpath 没有必要），按 steadyrpc-registry-zookeeper 的 jar 位置换 artifactId 找：
 * target/ 布局与本地仓库布局里，jar 的上上级目录都以 artifactId 命名。只跑到 test 阶段（{@code mvn test}）时 classpath 上是
 * target/classes 而不是 jar，这里失败并说明原因（与 {@link LeakCheckWiringTest} 同一约定）。
 */
class PublishedJarManifestTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource({
			"steadyrpc-core,                io.github.sisyfyzhang.steadyrpc.core,                  io.github.sisyfyzhang.steadyrpc.core.RpcClient",
			"steadyrpc-registry-zookeeper,  io.github.sisyfyzhang.steadyrpc.registry.zookeeper,    io.github.sisyfyzhang.steadyrpc.registry.zookeeper.ZookeeperRegistryFactory",
			"steadyrpc-registry-nacos,      io.github.sisyfyzhang.steadyrpc.registry.nacos,        io.github.sisyfyzhang.steadyrpc.registry.nacos.NacosRegistryFactory",
			"steadyrpc-registry-tck,        io.github.sisyfyzhang.steadyrpc.registry.tck,          io.github.sisyfyzhang.steadyrpc.registry.zookeeper.ZookeeperRegistryFactory",
			"steadyrpc-serialize-fastjson2, io.github.sisyfyzhang.steadyrpc.serialize.fastjson2,   io.github.sisyfyzhang.steadyrpc.serialize.fastjson2.Fastjson2Serializer",
			"steadyrpc-serialize-protobuf,  io.github.sisyfyzhang.steadyrpc.serialize.protobuf,    io.github.sisyfyzhang.steadyrpc.serialize.protobuf.ProtobufSerializer",
			"steadyrpc-metrics-micrometer,  io.github.sisyfyzhang.steadyrpc.metrics.micrometer,    io.github.sisyfyzhang.steadyrpc.metrics.micrometer.MicrometerServerFilter",
			"steadyrpc-spring-boot-starter, io.github.sisyfyzhang.steadyrpc.starter,               io.github.sisyfyzhang.steadyrpc.starter.RpcAutoConfiguration" })
	void publishedJarsCarryAutomaticModuleNameAndImplementationEntries(String artifactId, String moduleName, String anchorClass) throws Exception {
		Path jar = jarOf(artifactId, Class.forName(anchorClass, false, getClass().getClassLoader()));
		assertTrue(Files.isRegularFile(jar), artifactId + ": jar not found at " + jar);
		try (JarFile file = new JarFile(jar.toFile())) {
			Attributes main = file.getManifest().getMainAttributes();
			String version = pomVersion(file, artifactId);
			System.out.printf("[manifest] %s: Automatic-Module-Name=%s, Implementation-Title=%s, Implementation-Version=%s, Implementation-Vendor=%s%n",
					jar.getFileName(), main.getValue("Automatic-Module-Name"), main.getValue("Implementation-Title"),
					main.getValue("Implementation-Version"), main.getValue("Implementation-Vendor"));
			assertEquals(moduleName, main.getValue("Automatic-Module-Name"), artifactId + " Automatic-Module-Name");
			assertEquals(artifactId, main.getValue("Implementation-Title"), artifactId + " Implementation-Title");
			assertEquals(version, main.getValue("Implementation-Version"), artifactId + " Implementation-Version (pom.properties says " + version + ")");
			assertEquals("sisyfy-Zhang", main.getValue("Implementation-Vendor"), artifactId + " Implementation-Vendor");
		}
	}

	/** anchor 类所在的 jar；artifactId 与该 jar 不同时（tck）按同一布局换 artifactId。 */
	private static Path jarOf(String artifactId, Class<?> anchor) throws URISyntaxException {
		Path location = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertTrue(Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar"),
				anchor.getName() + " was loaded from " + location + ", not from a jar: run the integration tests with "
						+ "'mvn verify' / 'mvn install' so that the packaged jars (and their MANIFEST) are on the classpath");
		String anchorArtifact = location.getParent().getParent().getFileName().toString();
		if (anchorArtifact.equals(artifactId)) {
			return location;
		}
		String fileName = location.getFileName().toString();
		assertTrue(fileName.startsWith(anchorArtifact + "-"), "unexpected layout: " + location);
		return location.getParent().getParent().resolveSibling(artifactId).resolve(location.getParent().getFileName())
				.resolve(artifactId + fileName.substring(anchorArtifact.length()));
	}

	private static String pomVersion(JarFile file, String artifactId) throws Exception {
		var entry = file.getJarEntry("META-INF/maven/io.github.sisyfy-zhang/" + artifactId + "/pom.properties");
		assertNotNull(entry, artifactId + ": pom.properties missing");
		Properties pom = new Properties();
		try (InputStream in = file.getInputStream(entry)) {
			pom.load(in);
		}
		return pom.getProperty("version");
	}
}
