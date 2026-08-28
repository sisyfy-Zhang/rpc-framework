package com.feynman.rpc.core.server;

import com.feynman.rpc.core.serialize.Serializer;

/** 仅供测试用的第二个非默认序列化器占位实现（对应 protobuf 的角色）。 */
public class FakeSerializerB implements Serializer {
	@Override
	public byte code() {
		return 20;
	}

	@Override
	public String name() {
		return "alt";
	}

	@Override
	public byte[] serialize(Object obj) {
		return new byte[0];
	}

	@Override
	public <T> T deserialize(byte[] bytes, Class<T> type) {
		return null;
	}
}
