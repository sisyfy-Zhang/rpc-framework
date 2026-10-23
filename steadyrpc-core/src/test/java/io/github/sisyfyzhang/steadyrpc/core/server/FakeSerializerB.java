package io.github.sisyfyzhang.steadyrpc.core.server;

import java.lang.reflect.Type;

import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;

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
	public byte[] serialize(Object value, Type declaredType) {
		return new byte[0];
	}

	@Override
	public Object deserialize(byte[] bytes, Type declaredType) {
		return null;
	}
}
