package com.feynman.rpc.serialize.protobuf;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

import io.protostuff.Input;
import io.protostuff.Output;
import io.protostuff.Pipe;
import io.protostuff.WireFormat.FieldType;
import io.protostuff.runtime.Delegate;

/**
 * java.time 七个常用类型的标量编码：每个值编码成<b>一个 protobuf string 字段</b>，内容是 ISO-8601 文本。
 *
 * <table>
 *   <caption>类型 → 线上文本</caption>
 *   <tr><th>类型</th><th>格式</th><th>示例</th></tr>
 *   <tr><td>Instant</td><td>{@link DateTimeFormatter#ISO_INSTANT}（UTC，"Z" 结尾）</td><td>2023-11-14T22:13:20.123456789Z</td></tr>
 *   <tr><td>LocalDate</td><td>ISO_LOCAL_DATE</td><td>2024-02-29</td></tr>
 *   <tr><td>LocalTime</td><td>ISO_LOCAL_TIME</td><td>23:59:59.000000001</td></tr>
 *   <tr><td>LocalDateTime</td><td>ISO_LOCAL_DATE_TIME</td><td>2024-02-29T23:59:59.5</td></tr>
 *   <tr><td>Duration</td><td>ISO-8601 时长（{@link Duration#toString()}）</td><td>PT26H3M4.000000005S</td></tr>
 *   <tr><td>OffsetDateTime</td><td>ISO_OFFSET_DATE_TIME</td><td>2024-02-29T23:59:59+08:00</td></tr>
 *   <tr><td>ZonedDateTime</td><td>ISO_ZONED_DATE_TIME（偏移 + 区域 ID）</td><td>2024-11-03T01:30:00-05:00[America/New_York]</td></tr>
 * </table>
 *
 * <p>为什么选 ISO-8601 文本而不是二进制（例如 Instant 编成 seconds + nanos 两个整数）：
 * <ul>
 *   <li><b>精确</b>：纳秒精度完整保留（Instant / LocalTime / Duration 的 toString 都输出全部有效的小数位）；</li>
 *   <li><b>与时区无歧义</b>：Instant 固定写成 UTC 的 "Z"；本地类型（LocalDate / LocalTime / LocalDateTime）本来就没有时区，
 *       原样传递"墙上时间"而不做任何换算——两端时区不同也不会被悄悄平移；OffsetDateTime 带偏移；ZonedDateTime 同时带
 *       偏移和区域 ID：偏移消除了夏令时回拨那一小时的歧义（同一墙上时间对应两个瞬间），区域 ID 保留了"按哪个地区的
 *       规则继续计算"的语义；</li>
 *   <li><b>稳定</b>：ISO-8601 是标准格式，不依赖 JDK 内部字段布局（Protostuff 默认会把 Instant 当 POJO、按私有字段
 *       seconds / nanos 编码，那是实现细节），两端 JDK 版本不同也能互通，抓包可读；</li>
 *   <li><b>与 fastjson2 一致</b>：fastjson2 对这些类型也输出 ISO 文本，同一个值在两种序列化器下语义相同。</li>
 * </ul>
 * 代价是体积：Instant 约 20 ~ 30 字节（二进制约 12 字节）。相对一次 RPC 的帧头与信封，这点差异可以接受。
 * {@code java.util.Date} 保持 TypeSchemas 里原有的毫秒 fixed64 编码不变（兼容已有线上数据）。
 *
 * <p>这些 {@link Delegate} 同时注册进每个 ProtobufSerializer 自己的 IdStrategy：POJO 字段、集合元素、Object
 * 多态位置上的 java.time 值都走同一套编码，而不是被 Protostuff 当成 POJO 处理。
 */
final class JavaTimeDelegates {

	private JavaTimeDelegates() {
	}

	/** 全部委托；每次调用新建列表（不持有静态可变容器）。 */
	static List<Delegate<?>> all() {
		return List.of(
				new IsoDelegate<>(Instant.class, DateTimeFormatter.ISO_INSTANT::format, Instant::parse),
				new IsoDelegate<>(LocalDate.class, DateTimeFormatter.ISO_LOCAL_DATE::format, LocalDate::parse),
				new IsoDelegate<>(LocalTime.class, DateTimeFormatter.ISO_LOCAL_TIME::format, LocalTime::parse),
				new IsoDelegate<>(LocalDateTime.class, DateTimeFormatter.ISO_LOCAL_DATE_TIME::format, LocalDateTime::parse),
				new IsoDelegate<>(Duration.class, Duration::toString, Duration::parse),
				new IsoDelegate<>(OffsetDateTime.class, DateTimeFormatter.ISO_OFFSET_DATE_TIME::format, OffsetDateTime::parse),
				new IsoDelegate<>(ZonedDateTime.class, DateTimeFormatter.ISO_ZONED_DATE_TIME::format, ZonedDateTime::parse));
	}

	/** {@code raw} 对应的委托；不是这七个类型之一时返回 null。 */
	static Delegate<?> forClass(Class<?> raw) {
		for (Delegate<?> delegate : all()) {
			if (delegate.typeClass() == raw) {
				return delegate;
			}
		}
		return null;
	}

	/** 值 ↔ ISO 文本，线上是一个 string 字段。 */
	static final class IsoDelegate<T> implements Delegate<T> {
		private final Class<T> type;
		private final Function<T, String> format;
		private final Function<String, T> parse;

		IsoDelegate(Class<T> type, Function<T, String> format, Function<String, T> parse) {
			this.type = type;
			this.format = format;
			this.parse = parse;
		}

		@Override
		public FieldType getFieldType() {
			return FieldType.STRING;
		}

		@Override
		public T readFrom(Input input) throws IOException {
			String text = input.readString();
			try {
				return parse.apply(text);
			} catch (RuntimeException e) {
				throw new IllegalArgumentException("malformed " + type.getSimpleName() + " on the wire: '" + text + "'", e);
			}
		}

		@Override
		public void writeTo(Output output, int number, T value, boolean repeated) throws IOException {
			output.writeString(number, format.apply(value), repeated);
		}

		@Override
		public void transfer(Pipe pipe, Input input, Output output, int number, boolean repeated) throws IOException {
			input.transferByteRangeTo(output, true, number, repeated);
		}

		@Override
		public Class<?> typeClass() {
			return type;
		}
	}
}
