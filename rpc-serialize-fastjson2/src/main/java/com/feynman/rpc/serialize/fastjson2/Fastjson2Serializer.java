package com.feynman.rpc.serialize.fastjson2;

import com.alibaba.fastjson2.JSON;
import com.feynman.rpc.core.serialize.Serializer;

public class Fastjson2Serializer implements Serializer {

	@Override
	public byte code() {
		return 1;
	}

	@Override
	public String name() {
		return "fastjson2";
	}

	@Override
	public byte[] serialize(Object obj) {
		return JSON.toJSONBytes(obj);
	}

	@Override
	public <T> T deserialize(byte[] bytes, Class<T> type) {
		return JSON.parseObject(bytes, type);
	}
}
