package com.feynman.rpc.serialize.fastjson2;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.RpcRequest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Fastjson2SerializerTest {

	@Test
	void roundTripsRpcRequest() {
		Fastjson2Serializer serializer = new Fastjson2Serializer();
		RpcRequest original = new RpcRequest("com.example.Foo.bar", new byte[] {1, 2, 3});

		byte[] bytes = serializer.serialize(original);
		RpcRequest decoded = serializer.deserialize(bytes, RpcRequest.class);

		assertEquals(original.getCommand(), decoded.getCommand());
		assertArrayEquals(original.getArg(), decoded.getArg());
	}

	@Test
	void codeAndNameAreStable() {
		Fastjson2Serializer serializer = new Fastjson2Serializer();
		assertEquals((byte) 1, serializer.code());
		assertEquals("fastjson2", serializer.name());
	}
}
