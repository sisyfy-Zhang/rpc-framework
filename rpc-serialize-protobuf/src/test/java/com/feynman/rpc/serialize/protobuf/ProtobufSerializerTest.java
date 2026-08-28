package com.feynman.rpc.serialize.protobuf;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.protocol.RpcRequest;
import com.feynman.rpc.core.protocol.RpcResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtobufSerializerTest {

	@Test
	void roundTripsRpcRequestPojo() {
		ProtobufSerializer serializer = new ProtobufSerializer();
		RpcRequest original = new RpcRequest("com.example.Foo.bar", new byte[] {1, 2, 3});

		byte[] bytes = serializer.serialize(original);
		RpcRequest decoded = serializer.deserialize(bytes, RpcRequest.class);

		assertEquals(original.getCommand(), decoded.getCommand());
		assertArrayEquals(original.getArg(), decoded.getArg());
	}

	@Test
	void roundTripsRpcResponsePojo() {
		ProtobufSerializer serializer = new ProtobufSerializer();
		RpcResponse original = new RpcResponse("boom", new byte[] {9, 8, 7});

		byte[] bytes = serializer.serialize(original);
		RpcResponse decoded = serializer.deserialize(bytes, RpcResponse.class);

		assertEquals(original.getReason(), decoded.getReason());
		assertArrayEquals(original.getResult(), decoded.getResult());
	}

	@Test
	void roundTripsBareStringViaUtf8FastPath() {
		ProtobufSerializer serializer = new ProtobufSerializer();

		byte[] bytes = serializer.serialize("hello world");
		String decoded = serializer.deserialize(bytes, String.class);

		assertEquals("hello world", decoded);
	}

	@Test
	void codeAndNameAreStable() {
		ProtobufSerializer serializer = new ProtobufSerializer();
		assertEquals((byte) 2, serializer.code());
		assertEquals("protobuf", serializer.name());
	}
}
