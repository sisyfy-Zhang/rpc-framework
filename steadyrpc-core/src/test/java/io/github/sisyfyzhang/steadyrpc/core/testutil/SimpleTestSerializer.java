package io.github.sisyfyzhang.steadyrpc.core.testutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;

import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;

/**
 * steadyrpc-core 按设计在 classpath 上没有真实序列化实现；这个最小编解码器只认识几种标量（String / Integer / Long）与 byte[]，
 * 足够让 core 自己的端到端测试跑通真实 socket 往返。协议 v2 的信封不经过序列化器，所以这里不再需要认识任何框架类型。
 *
 * <p>SPI 名 {@value #NAME}、code 10（与协商测试的约定一致）。它<b>不占用</b> Serializer 的 SPI 缺省名（protobuf）：
 * core 的测试一律显式 {@code serializer(SimpleTestSerializer.NAME)}，缺省名变化不影响它们，也不会让人误以为
 * core 的测试跑的是 Protostuff。
 */
public class SimpleTestSerializer implements Serializer {

	public static final String NAME = "simple";

	@Override
	public byte code() {
		return 10;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public byte[] serialize(Object value, Type declaredType) {
		if (value instanceof SerializationBomb bomb) {
			throw bomb.error();
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			if (value instanceof String s) {
				out.writeByte('S');
				out.writeUTF(s);
			} else if (value instanceof Integer i) {
				out.writeByte('I');
				out.writeInt(i);
			} else if (value instanceof Long l) {
				out.writeByte('L');
				out.writeLong(l);
			} else if (value instanceof byte[] b) {
				out.writeByte('B');
				out.writeInt(b.length);
				out.write(b);
			} else {
				throw new IllegalArgumentException("SimpleTestSerializer cannot serialize " + value.getClass());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	@Override
	public Object deserialize(byte[] bytes, Type declaredType) {
		if (bytes.length == 0) {
			return declaredType == String.class ? "" : null;
		}
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			byte tag = in.readByte();
			return switch (tag) {
				case 'S' -> in.readUTF();
				case 'I' -> in.readInt();
				case 'L' -> in.readLong();
				case 'B' -> in.readNBytes(in.readInt());
				default -> throw new IllegalArgumentException("unknown tag " + tag);
			};
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
