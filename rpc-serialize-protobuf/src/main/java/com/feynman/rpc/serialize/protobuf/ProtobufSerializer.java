package com.feynman.rpc.serialize.protobuf;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.feynman.rpc.core.serialize.Serializer;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

/**
 * 基于 Protostuff 运行时 schema 推导的 Protobuf 线上格式序列化器，因此能处理调用方传入的任意
 * POJO 作为 RPC 参数/返回值，不需要 .proto/代码生成步骤（不像原生 google protobuf-java，要求
 * 每个消息类型都是生成出来的 {@code Message} 子类——这对一个通用序列化器不现实）。
 *
 * <p>{@code RuntimeSchema} 面向的是结构化 POJO（声明字段 + 无参构造函数）；裸的顶层
 * {@link String} 不符合这个模型，所以字符串直接按 UTF-8 编码，不硬套一个本来就不是给标量
 * 类型用的 schema。
 */
public class ProtobufSerializer implements Serializer {

	private static final Map<Class<?>, Schema<?>> SCHEMA_CACHE = new ConcurrentHashMap<>();

	@Override
	public byte code() {
		return 2;
	}

	@Override
	public String name() {
		return "protobuf";
	}

	@Override
	public byte[] serialize(Object obj) {
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

	@Override
	public <T> T deserialize(byte[] bytes, Class<T> type) {
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
