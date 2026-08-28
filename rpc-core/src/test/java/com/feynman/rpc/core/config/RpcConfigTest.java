package com.feynman.rpc.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.extension.ExtensionLoader;
import com.feynman.rpc.core.serialize.Serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 复用 {@code NegotiationTest} 已经配好的测试专用 {@code fastjson2}/{@code alt} Serializer
 * fixture（见 rpc-core 测试目录下的 {@code META-INF/rpc/...Serializer}），证明
 * {@link RpcConfig#resolve} 真的切换了返回的扩展实现，而不只是没抛异常。
 */
class RpcConfigTest {

	@AfterEach
	void clearOverride() {
		System.clearProperty(RpcConfig.SERIALIZER_KEY);
	}

	@Test
	void getReturnsNullWhenNothingConfigured() {
		assertNull(RpcConfig.get(RpcConfig.SERIALIZER_KEY));
	}

	@Test
	void getReturnsSystemPropertyWhenSet() {
		System.setProperty(RpcConfig.SERIALIZER_KEY, "alt");
		assertEquals("alt", RpcConfig.get(RpcConfig.SERIALIZER_KEY));
	}

	@Test
	void resolveFallsBackToSpiDefaultWhenUnconfigured() {
		ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
		Serializer resolved = RpcConfig.resolve(loader, RpcConfig.SERIALIZER_KEY);
		assertEquals("fastjson2", resolved.name());
	}

	@Test
	void resolveHonorsSystemPropertyOverride() {
		System.setProperty(RpcConfig.SERIALIZER_KEY, "alt");
		ExtensionLoader<Serializer> loader = ExtensionLoader.getExtensionLoader(Serializer.class);
		Serializer resolved = RpcConfig.resolve(loader, RpcConfig.SERIALIZER_KEY);
		assertEquals("alt", resolved.name());
	}
}
