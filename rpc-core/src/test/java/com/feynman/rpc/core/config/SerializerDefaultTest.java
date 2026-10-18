package com.feynman.rpc.core.config;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.extension.SPI;
import com.feynman.rpc.core.registry.RegistryConfig;
import com.feynman.rpc.core.serialize.Serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 默认序列化方式是 Protostuff（SPI 名 protobuf，DECISIONS D-001）：不配置时客户端首选、服务端兜底都解析成它。
 * 只校验配置解析，不加载实现——rpc-core 的测试 classpath 上按设计没有 rpc-serialize-protobuf。
 */
class SerializerDefaultTest {

	@Test
	void serializerDefaultsToProtobufOnBothSides() {
		assertEquals("protobuf", Serializer.class.getAnnotation(SPI.class).value());
		assertEquals("protobuf", RpcClientConfig.builder().registry(RegistryConfig.of("memory", "x")).build().serializer());
		assertEquals("protobuf", RpcServerConfig.builder().build().serializer());
	}

	@Test
	void explicitSerializerWins() {
		assertEquals("fastjson2",
				RpcClientConfig.builder().registry(RegistryConfig.of("memory", "x")).serializer(" fastjson2 ").build().serializer());
		assertEquals("fastjson2", RpcServerConfig.builder().serializer("fastjson2").build().serializer());
	}
}
