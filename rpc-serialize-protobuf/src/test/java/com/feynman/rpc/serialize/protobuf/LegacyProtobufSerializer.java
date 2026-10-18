package com.feynman.rpc.serialize.protobuf;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

/**
 * 声明类型改造之前的旧版 ProtobufSerializer 实现（只按运行时 Class 序列化，与帧格式 / 协议版本无关），原样冻结在
 * 测试代码里，只供 {@link ProtostuffBehaviorProbeTest}
 * 使用：探针 P2a / P2b / P9 记录的正是"按运行时 Class 调 RuntimeSchema.getSchema、走全局 IdStrategy"这条旧路径的行为，
 * 新实现（按声明类型构造 schema、自有白名单策略）已经不会复现它们。保留旧路径，-Pprobe 的结果表才能保持不变、
 * 继续作为 docs/pitfalls.md 第 2 条的证据。不要在生产代码里使用。
 */
final class LegacyProtobufSerializer {

	private static final Map<Class<?>, Schema<?>> SCHEMA_CACHE = new ConcurrentHashMap<>();

	byte[] serialize(Object obj) {
		if (obj == null) {
			return new byte[0];
		}
		if (obj instanceof String) {
			return ((String) obj).getBytes(StandardCharsets.UTF_8);
		}
		@SuppressWarnings("unchecked")
		Class<Object> objClass = (Class<Object>) obj.getClass();
		Schema<Object> schema = schemaFor(objClass);
		LinkedBuffer buffer = LinkedBuffer.allocate(512);
		try {
			return ProtostuffIOUtil.toByteArray(obj, schema, buffer);
		} finally {
			buffer.clear();
		}
	}

	<T> T deserialize(byte[] bytes, Class<T> type) {
		if (bytes == null || bytes.length == 0) {
			return null;
		}
		if (type == String.class) {
			return type.cast(new String(bytes, StandardCharsets.UTF_8));
		}
		Schema<T> schema = schemaFor(type);
		T instance = schema.newMessage();
		ProtostuffIOUtil.mergeFrom(bytes, instance, schema);
		return instance;
	}

	@SuppressWarnings("unchecked")
	private static <T> Schema<T> schemaFor(Class<T> type) {
		return (Schema<T>) SCHEMA_CACHE.computeIfAbsent(type, RuntimeSchema::getSchema);
	}
}
