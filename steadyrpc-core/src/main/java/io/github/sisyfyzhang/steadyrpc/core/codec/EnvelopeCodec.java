package io.github.sisyfyzhang.steadyrpc.core.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.sisyfyzhang.steadyrpc.core.protocol.OutboundBody;
import io.github.sisyfyzhang.steadyrpc.core.protocol.ProtocolException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

/**
 * body 信封的编解码，纯函数，直接读写 {@link ByteBuf}。信封由框架自编码，序列化器只负责其中每个参数 / 返回值的字节
 * （D-006）：路由身份是类型化字段，换序列化器不改线上格式，错误路径也不依赖序列化器。
 *
 * <pre>
 * str      = len u16 + UTF-8
 * value    = present u8 (0|1) [+ len i32 + bytes]        present=0 表示 null；present=1 且 len=0 表示"空对象"
 *
 * 请求 body           = service str, version str, group str, method str, paramSig str, argCount u8, value × argCount
 * 响应 body（OK）      = value                            （void 方法写 present=0）
 * 响应 body（其他状态） = className str, message str, remoteStack str
 * </pre>
 * 错误信封用于所有非 OK 状态：BIZ_EXCEPTION / SERVER_ERROR 填异常类名与（可选的）远端堆栈；框架错误
 * （BAD_REQUEST、DEADLINE_EXCEEDED 等）className 与 remoteStack 为空串，只用 message。null 字符串按空串编码；
 * 错误信封里超过 65535 字节的 message / remoteStack 在字符边界处截断（堆栈很深时不该让回包失败）。
 *
 * <p>出站有两种写法，线上字节相同：{@code write*} 直接写进调用方给的缓冲区；{@link #request} / {@link #value} /
 * {@link #error} 只备好字节、算出确切长度，返回不持缓冲区的 {@link OutboundBody}，由编码器在事件循环上写入——
 * 框架自己的出站路径（客户端请求、服务端回包）都用后者，业务线程 / 调用方线程不碰池化分配器。
 *
 * <p>解码函数读取 {@code in} 的全部可读字节并要求恰好用完；返回的参数 / 返回值是 {@code in} 上的 slice
 * （不 retain、不拷贝），生命周期跟随 {@code in}。任何越界、多余字节、非法 present 标志都抛 {@link ProtocolException}。
 */
public final class EnvelopeCodec {

	/** argCount 是 u8。 */
	public static final int MAX_ARGS = 255;

	private static final int MAX_U16 = 0xFFFF;

	private EnvelopeCodec() {
	}

	/** 解码后的请求信封。{@code args} 中 null 表示该参数为 null；非 null 元素是 body 上的 slice（可能 0 字节）。 */
	public record RequestEnvelope(String service, String version, String group, String method, String paramSig, List<ByteBuf> args) {
	}

	/** 解码后的错误信封。三个字段都不为 null（缺省为空串）。 */
	public record ErrorEnvelope(String className, String message, String remoteStack) {
	}

	// ------------------------------------------------------------------ 请求

	/**
	 * @param args 每个参数序列化后的字节；元素为 null 表示参数为 null
	 * @throws IllegalArgumentException 参数超过 255 个、路由字段超过 65535 字节
	 */
	public static void writeRequest(ByteBuf out, String service, String version, String group, String method,
			String paramSig, List<byte[]> args) {
		if (args.size() > MAX_ARGS) {
			throw new IllegalArgumentException("too many arguments: " + args.size() + " > " + MAX_ARGS);
		}
		writeStr(out, service);
		writeStr(out, version);
		writeStr(out, group);
		writeStr(out, method);
		writeStr(out, paramSig);
		out.writeByte(args.size());
		for (byte[] arg : args) {
			writeValue(out, arg);
		}
	}

	/**
	 * 备好一个请求信封（不分配缓冲区）：校验并算出编码后的确切长度，字节在编码器里（事件循环上）才写入缓冲区。
	 * 线上格式与 {@link #writeRequest} 相同。
	 *
	 * @param args 每个参数序列化后的字节；元素为 null 表示参数为 null。列表与数组之后不得再修改
	 * @throws IllegalArgumentException 参数超过 255 个、路由字段超过 65535 字节
	 */
	public static OutboundRequest request(String service, String version, String group, String method, String paramSig,
			List<byte[]> args) {
		if (args.size() > MAX_ARGS) {
			throw new IllegalArgumentException("too many arguments: " + args.size() + " > " + MAX_ARGS);
		}
		long length = strLength(service) + strLength(version) + strLength(group) + strLength(method) + strLength(paramSig) + 1L;
		for (byte[] arg : args) {
			length += arg == null ? 1 : 1 + 4 + (long) arg.length;
		}
		if (length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("request envelope too large: " + length + " bytes");
		}
		return new OutboundRequest(service, version, group, method, paramSig, args, (int) length);
	}

	/** {@link #request} 的结果：路由字段 + 已序列化的参数 + 编码后的长度。不持有缓冲区。 */
	public static final class OutboundRequest implements OutboundBody {
		private final String service;
		private final String version;
		private final String group;
		private final String method;
		private final String paramSig;
		private final List<byte[]> args;
		private final int length;

		private OutboundRequest(String service, String version, String group, String method, String paramSig,
				List<byte[]> args, int length) {
			this.service = service;
			this.version = version;
			this.group = group;
			this.method = method;
			this.paramSig = paramSig;
			this.args = args;
			this.length = length;
		}

		@Override
		public int length() {
			return length;
		}

		@Override
		public void writeTo(ByteBuf out) {
			int start = out.writerIndex();
			writeRequest(out, service, version, group, method, paramSig, args);
			int written = out.writerIndex() - start;
			if (written != length) {
				throw new IllegalStateException("request envelope wrote " + written + " bytes, expected " + length);
			}
		}

		@Override
		public String toString() {
			return "OutboundRequest{" + service + "#" + method + "(" + paramSig + "), " + length + "B}";
		}
	}

	/** {@code str} 单元编码后的字节数（2 字节长度 + UTF-8）。 */
	private static int strLength(String s) {
		int bytes = ByteBufUtil.utf8Bytes(s == null ? "" : s);
		if (bytes > MAX_U16) {
			throw new IllegalArgumentException("string field longer than 65535 UTF-8 bytes");
		}
		return 2 + bytes;
	}

	public static RequestEnvelope readRequest(ByteBuf in) {
		ByteBuf buf = in.duplicate();
		String service = readStr(buf, "service");
		String version = readStr(buf, "version");
		String group = readStr(buf, "group");
		String method = readStr(buf, "method");
		String paramSig = readStr(buf, "paramSig");
		if (!buf.isReadable()) {
			throw new ProtocolException("request envelope truncated: missing argCount");
		}
		int argCount = buf.readUnsignedByte();
		// 每个参数至少 1 字节（present 标志）
		if (argCount > buf.readableBytes()) {
			throw new ProtocolException("argCount " + argCount + " does not fit in " + buf.readableBytes() + " bytes");
		}
		List<ByteBuf> args = new ArrayList<>(argCount);
		for (int i = 0; i < argCount; i++) {
			args.add(readValueSlice(buf, "argument " + i));
		}
		requireFullyRead(buf, "request envelope");
		return new RequestEnvelope(service, version, group, method, paramSig, Collections.unmodifiableList(args));
	}

	// ------------------------------------------------------------------ 响应

	/** OK 响应的 body：单个 value。{@code value} 为 null 表示返回 null（或 void）。 */
	public static void writeValue(ByteBuf out, byte[] value) {
		if (value == null) {
			out.writeByte(0);
			return;
		}
		out.writeByte(1);
		out.writeInt(value.length);
		out.writeBytes(value);
	}

	/**
	 * 备好一个 OK 响应 body（不分配缓冲区），线上格式与 {@link #writeValue} 相同。
	 *
	 * @param value 返回值序列化后的字节；null 表示返回 null（或 void）。之后不得再修改
	 */
	public static OutboundBody value(byte[] value) {
		return new OutboundValue(value);
	}

	/** {@link #value} 的结果。 */
	private static final class OutboundValue implements OutboundBody {
		private final byte[] value;

		OutboundValue(byte[] value) {
			this.value = value;
		}

		@Override
		public int length() {
			// byte[] 最长约 Integer.MAX_VALUE - 8，加 5 字节不溢出
			return value == null ? 1 : 1 + 4 + value.length;
		}

		@Override
		public void writeTo(ByteBuf out) {
			writeValue(out, value);
		}

		@Override
		public String toString() {
			return "OutboundValue{" + length() + "B}";
		}
	}

	/** 解码 OK 响应 body；返回 null 表示 present=0，否则为 {@code in} 上的 slice（可能 0 字节）。 */
	public static ByteBuf readValue(ByteBuf in) {
		ByteBuf buf = in.duplicate();
		ByteBuf value = readValueSlice(buf, "value");
		requireFullyRead(buf, "response value");
		return value;
	}

	public static void writeError(ByteBuf out, String className, String message, String remoteStack) {
		writeTruncatedStr(out, className);
		writeTruncatedStr(out, message);
		writeTruncatedStr(out, remoteStack);
	}

	/**
	 * 备好一个错误信封（不分配缓冲区），线上格式与 {@link #writeError} 相同：超长字段在这里（调用方线程上）就截断好，
	 * 编码器只按确切长度写入。
	 */
	public static OutboundBody error(String className, String message, String remoteStack) {
		return new OutboundError(truncated(className), truncated(message), truncated(remoteStack));
	}

	/** {@link #error} 的结果：三个字段已截断到 65535 个 UTF-8 字节以内。 */
	private static final class OutboundError implements OutboundBody {
		private final String className;
		private final String message;
		private final String remoteStack;
		private final int length;

		OutboundError(String className, String message, String remoteStack) {
			this.className = className;
			this.message = message;
			this.remoteStack = remoteStack;
			this.length = strLength(className) + strLength(message) + strLength(remoteStack);
		}

		@Override
		public int length() {
			return length;
		}

		@Override
		public void writeTo(ByteBuf out) {
			int start = out.writerIndex();
			writeStr(out, className);
			writeStr(out, message);
			writeStr(out, remoteStack);
			int written = out.writerIndex() - start;
			if (written != length) {
				throw new IllegalStateException("error envelope wrote " + written + " bytes, expected " + length);
			}
		}

		@Override
		public String toString() {
			return "OutboundError{" + className + ", " + length + "B}";
		}
	}

	public static ErrorEnvelope readError(ByteBuf in) {
		ByteBuf buf = in.duplicate();
		String className = readStr(buf, "className");
		String message = readStr(buf, "message");
		String remoteStack = readStr(buf, "remoteStack");
		requireFullyRead(buf, "error envelope");
		return new ErrorEnvelope(className, message, remoteStack);
	}

	// ------------------------------------------------------------------ 基本单元

	private static void writeStr(ByteBuf out, String s) {
		String value = s == null ? "" : s;
		int bytes = ByteBufUtil.utf8Bytes(value);
		if (bytes > MAX_U16) {
			throw new IllegalArgumentException("string field longer than 65535 UTF-8 bytes");
		}
		int lengthIndex = out.writerIndex();
		out.writeShort(0);
		// 只预留确切的字节数：writeUtf8 会按最坏情况（每字符 3 字节）预留，在按确切长度分配的缓冲区里放不下、在其它缓冲区里白白扩容
		int written = ByteBufUtil.reserveAndWriteUtf8(out, value, bytes);
		out.setShort(lengthIndex, written);
	}

	private static void writeTruncatedStr(ByteBuf out, String s) {
		writeStr(out, truncated(s));
	}

	/** null 按空串；超过 65535 个 UTF-8 字节时在字符边界截断。 */
	private static String truncated(String s) {
		String value = s == null ? "" : s;
		if (ByteBufUtil.utf8Bytes(value) > MAX_U16) {
			value = truncateUtf8(value, MAX_U16);
		}
		return value;
	}

	/** 截到 UTF-8 编码后不超过 {@code maxBytes} 的最长前缀，不切开代理对。 */
	public static String truncateUtf8(String s, int maxBytes) {
		int bytes = 0;
		int i = 0;
		while (i < s.length()) {
			char c = s.charAt(i);
			int charBytes;
			int width = 1;
			if (c < 0x80) {
				charBytes = 1;
			} else if (c < 0x800) {
				charBytes = 2;
			} else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
				charBytes = 4;
				width = 2;
			} else if (Character.isSurrogate(c)) {
				charBytes = 1; // 孤立代理按 Netty 写成 '?'
			} else {
				charBytes = 3;
			}
			if (bytes + charBytes > maxBytes) {
				break;
			}
			bytes += charBytes;
			i += width;
		}
		return s.substring(0, i);
	}

	private static String readStr(ByteBuf buf, String what) {
		if (buf.readableBytes() < 2) {
			throw new ProtocolException("envelope truncated: missing " + what + " length");
		}
		int len = buf.readUnsignedShort();
		if (len > buf.readableBytes()) {
			throw new ProtocolException(what + " length " + len + " exceeds remaining " + buf.readableBytes());
		}
		String s = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);
		buf.skipBytes(len);
		return s;
	}

	private static ByteBuf readValueSlice(ByteBuf buf, String what) {
		if (!buf.isReadable()) {
			throw new ProtocolException("envelope truncated: missing " + what + " present flag");
		}
		byte present = buf.readByte();
		if (present == 0) {
			return null;
		}
		if (present != 1) {
			throw new ProtocolException("illegal present flag " + present + " for " + what);
		}
		if (buf.readableBytes() < 4) {
			throw new ProtocolException("envelope truncated: missing " + what + " length");
		}
		int len = buf.readInt();
		if (len < 0 || len > buf.readableBytes()) {
			throw new ProtocolException(what + " length " + len + " out of range (remaining " + buf.readableBytes() + ")");
		}
		return buf.readSlice(len);
	}

	private static void requireFullyRead(ByteBuf buf, String what) {
		if (buf.isReadable()) {
			throw new ProtocolException(what + " has " + buf.readableBytes() + " trailing bytes");
		}
	}
}
