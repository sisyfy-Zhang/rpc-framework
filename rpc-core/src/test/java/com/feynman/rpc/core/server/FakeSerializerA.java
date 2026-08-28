package com.feynman.rpc.core.server;

import com.feynman.rpc.core.serialize.Serializer;

/** 仅供测试用的"默认"序列化器占位实现（对应生产环境中 fastjson2 的角色：code 为 1）。 */
public class FakeSerializerA implements Serializer {
	@Override
	public byte code() {
		return 10;
	}

	@Override
	public String name() {
		return "fastjson2";
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
