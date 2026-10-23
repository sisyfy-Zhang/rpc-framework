package io.github.sisyfyzhang.steadyrpc.core.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionLoaderTest {

	@Test
	void loadsNamedExtensionsFromConfigFile() {
		ExtensionLoader<TestGreeter> loader = ExtensionLoader.getExtensionLoader(TestGreeter.class);

		assertEquals("hello", loader.getExtension("hello").greet());
		assertEquals("hi", loader.getExtension("hi").greet());
	}

	@Test
	void defaultExtensionComesFromSpiAnnotationValue() {
		ExtensionLoader<TestGreeter> loader = ExtensionLoader.getExtensionLoader(TestGreeter.class);

		assertEquals("hello", loader.getDefaultExtension().greet());
	}

	@Test
	void instancesAreCachedPerName() {
		ExtensionLoader<TestGreeter> loader = ExtensionLoader.getExtensionLoader(TestGreeter.class);

		assertSame(loader.getExtension("hello"), loader.getExtension("hello"));
	}

	@Test
	void newExtensionCreatesAFreshInstanceEveryTime() {
		// 有状态扩展（LoadBalance 游标、Cluster 重试线程）每个 RpcClient 各持一份，不能共享缓存单例
		ExtensionLoader<TestGreeter> loader = ExtensionLoader.getExtensionLoader(TestGreeter.class);

		assertNotSame(loader.newExtension("hello"), loader.newExtension("hello"));
		assertNotSame(loader.getExtension("hello"), loader.newExtension("hello"));
	}

	@Test
	void unknownExtensionNameThrows() {
		ExtensionLoader<TestGreeter> loader = ExtensionLoader.getExtensionLoader(TestGreeter.class);

		assertThrows(IllegalStateException.class, () -> loader.getExtension("nope"));
	}

	@Test
	void nonSpiInterfaceIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> ExtensionLoader.getExtensionLoader(Runnable.class));
	}
}
