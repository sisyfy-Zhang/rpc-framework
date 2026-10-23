package io.github.sisyfyzhang.steadyrpc.core.codec;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * attachments 段的编解码，纯函数，直接读写 {@link ByteBuf}（不经过中间 byte[]）。
 *
 * <pre>
 * 空 map        → 0 字节（attachmentsLength = 0，连 count 都不写：绝大多数请求没有 attachments）
 * 非空          → count u16，随后 count 对 { keyLen u16, key UTF-8, valLen u16, value UTF-8 }
 * </pre>
 * 只允许字符串、不允许 null（对齐 gRPC Metadata）；单个键 / 值 UTF-8 后不超过 65535 字节；总长受调用方给的上限约束。
 *
 * <p>为什么不走序列化器：IO 线程要在不反序列化 body 的前提下读到 deadline / trace 等元数据，而且这段格式不能依赖
 * 可插拔、可能协商失败的组件（D-006）。解码对重复键直接判为格式错误：同一个 traceparent 出现两次时"后者覆盖"
 * 会成为请求走私的入口。
 */
public final class AttachmentsCodec {

	private static final int MAX_U16 = 0xFFFF;

	private AttachmentsCodec() {
	}

	/**
	 * 按上述格式的字节长度；同时完成合法性校验。
	 *
	 * @throws IllegalArgumentException 键 / 值为 null、单项超过 65535 字节、条目数超过 65535、总长超过 {@code maxLength}
	 */
	public static int encodedLength(Map<String, String> attachments, int maxLength) {
		if (attachments == null || attachments.isEmpty()) {
			return 0;
		}
		if (attachments.size() > MAX_U16) {
			throw new IllegalArgumentException("too many attachments: " + attachments.size());
		}
		long total = 2;
		for (Map.Entry<String, String> e : attachments.entrySet()) {
			total += 2L + strLength(e.getKey(), "key") + 2L + strLength(e.getValue(), "value of " + e.getKey());
		}
		if (total > maxLength) {
			throw new IllegalArgumentException("attachments too large: " + total + " bytes > limit " + maxLength);
		}
		return (int) total;
	}

	/**
	 * 写入 {@code out} 的当前写指针处，返回写入的字节数。先整体校验再写：校验失败时 {@code out} 不被改动。
	 */
	public static int encode(Map<String, String> attachments, ByteBuf out, int maxLength) {
		int length = encodedLength(attachments, maxLength);
		if (length == 0) {
			return 0;
		}
		out.ensureWritable(length);
		int start = out.writerIndex();
		out.writeShort(attachments.size());
		for (Map.Entry<String, String> e : attachments.entrySet()) {
			writeStr(out, e.getKey());
			writeStr(out, e.getValue());
		}
		return out.writerIndex() - start;
	}

	/**
	 * 解码 {@code in} 中从读指针开始的全部可读字节（调用方负责切出 attachments 段），不改变 {@code in} 的引用计数。
	 *
	 * @throws ProtocolException 长度字段越界、条目数与字节数不符、重复键、尾部有多余字节、总长超限
	 */
	public static Map<String, String> decode(ByteBuf in, int maxLength) {
		int length = in.readableBytes();
		if (length == 0) {
			return Collections.emptyMap();
		}
		if (length > maxLength) {
			throw new ProtocolException("attachments too large: " + length + " bytes > limit " + maxLength);
		}
		ByteBuf buf = in.duplicate();
		if (buf.readableBytes() < 2) {
			throw new ProtocolException("attachments truncated: missing count");
		}
		int count = buf.readUnsignedShort();
		// 每对至少 4 字节（两个长度字段）：先用它挡住"count 很大、字节很少"的输入，避免按 count 预分配
		if ((long) count * 4 > buf.readableBytes()) {
			throw new ProtocolException("attachments count " + count + " does not fit in " + buf.readableBytes() + " bytes");
		}
		Map<String, String> result = new LinkedHashMap<>(Math.max(4, count * 2));
		for (int i = 0; i < count; i++) {
			String key = readStr(buf, "key");
			String value = readStr(buf, "value");
			if (result.putIfAbsent(key, value) != null) {
				throw new ProtocolException("duplicate attachment key: " + key);
			}
		}
		if (buf.isReadable()) {
			throw new ProtocolException("attachments has " + buf.readableBytes() + " trailing bytes");
		}
		return Collections.unmodifiableMap(result);
	}

	private static int strLength(String s, String what) {
		if (s == null) {
			throw new IllegalArgumentException("attachment " + what + " must not be null");
		}
		int bytes = ByteBufUtil.utf8Bytes(s);
		if (bytes > MAX_U16) {
			throw new IllegalArgumentException("attachment " + what + " longer than 65535 UTF-8 bytes");
		}
		return bytes;
	}

	private static void writeStr(ByteBuf out, String s) {
		int lengthIndex = out.writerIndex();
		out.writeShort(0);
		// 只预留确切的字节数：writeUtf8 按最坏情况（每字符 3 字节）预留，在编码器按确切长度分配的整帧里，
		// 2 字节字符 / 代理对多的 attachments 会因预留超出容量而写失败（后面的 body 不够垫底时）
		int written = ByteBufUtil.reserveAndWriteUtf8(out, s, ByteBufUtil.utf8Bytes(s));
		out.setShort(lengthIndex, written);
	}

	private static String readStr(ByteBuf buf, String what) {
		if (buf.readableBytes() < 2) {
			throw new ProtocolException("attachments truncated: missing " + what + " length");
		}
		int len = buf.readUnsignedShort();
		if (len > buf.readableBytes()) {
			throw new ProtocolException("attachment " + what + " length " + len + " exceeds remaining " + buf.readableBytes());
		}
		String s = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);
		buf.skipBytes(len);
		return s;
	}
}
