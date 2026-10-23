package io.github.sisyfyzhang.steadyrpc.serialize.fastjson2;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import com.alibaba.fastjson2.writer.ObjectWriter;
import com.alibaba.fastjson2.writer.ObjectWriterProvider;
import io.github.sisyfyzhang.steadyrpc.core.serialize.Serializer;

/**
 * fastjson2 实现。按调用方给的<b>声明类型</b>反序列化（{@code List<User>} 的元素还原成 User，而不是 JSONObject），
 * 因此不需要也不开启 autoType：线上不写类名、反序列化不按类名加载类，fastjson 系列历史上的 autoType 反序列化漏洞
 * 在这条路径上不存在。多态位置（声明为 Object / 接口）的值会还原成 JSONObject / JSONArray，需要多态请用 Protostuff
 * 并配置白名单。
 *
 * <p><b>ZonedDateTime</b>：fastjson2 2.0.60 默认把它写成 {@code 2024-11-03T01:30:00[America/New_York]}——只有区域、
 * 没有偏移。夏令时回拨的那一小时里同一墙上时间对应两个瞬间，读回时总是取较早的偏移，较晚那个瞬间被悄悄平移一小时
 * （A0-4 矩阵用例实测）。这里给 ZonedDateTime 注册 ISO_ZONED_DATE_TIME（偏移 + 区域）的读写器，与 Protostuff 实现的
 * 编码一致。读写器注册在本实例自己的 provider 上（{@link ObjectWriterProvider} / {@link ObjectReaderProvider}），
 * 不碰 fastjson2 的全局默认 provider：全局注册会影响同一 JVM 里所有用 fastjson2 的代码（全仓禁止可变全局状态）。
 * 其余 java.time 类型 fastjson2 默认即输出 ISO 文本并保留纳秒，不需要额外处理。
 */
public class Fastjson2Serializer implements Serializer {

	private final ObjectWriterProvider writerProvider = new ObjectWriterProvider();
	private final ObjectReaderProvider readerProvider = new ObjectReaderProvider();

	public Fastjson2Serializer() {
		writerProvider.register(ZonedDateTime.class, new ZonedDateTimeWriter());
		readerProvider.register(ZonedDateTime.class, new ZonedDateTimeReader());
	}

	@Override
	public byte code() {
		return 1;
	}

	@Override
	public String name() {
		return "fastjson2";
	}

	@Override
	public byte[] serialize(Object value, Type declaredType) {
		// 等价于 JSON.toJSONBytes(value)，只是换成本实例的 provider（JSON 门面没有接受 Context 的 toJSONBytes 重载）
		try (JSONWriter writer = JSONWriter.ofUTF8(new JSONWriter.Context(writerProvider))) {
			if (value == null) {
				writer.writeNull();
			} else {
				Class<?> valueClass = value.getClass();
				ObjectWriter<?> objectWriter = writer.getContext().getObjectWriter(valueClass, valueClass);
				objectWriter.write(writer, value, null, null, 0);
			}
			return writer.getBytes();
		}
	}

	/**
	 * 0 字节表示"空对象"（框架约定；本实现自己不会产出 0 字节）：集合 / 数组按 {@code []}、其余按 {@code {}} 解析，
	 * 得到空集合或无参实例，而不是 null。
	 */
	@Override
	public Object deserialize(byte[] bytes, Type declaredType) {
		if (bytes.length == 0) {
			Class<?> raw = raw(declaredType);
			String empty = raw != null && (raw.isArray() || Collection.class.isAssignableFrom(raw)) ? "[]" : "{}";
			if (raw == String.class) {
				return "";
			}
			return JSON.parseObject(empty, declaredType, new JSONReader.Context(readerProvider));
		}
		return JSON.parseObject(bytes, declaredType, new JSONReader.Context(readerProvider));
	}

	private static Class<?> raw(Type type) {
		if (type instanceof Class<?> clazz) {
			return clazz;
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
			return clazz;
		}
		return Map.class;
	}

	/** ZonedDateTime → ISO_ZONED_DATE_TIME 文本（偏移 + 区域 ID）。 */
	private static final class ZonedDateTimeWriter implements ObjectWriter<ZonedDateTime> {
		@Override
		public void write(JSONWriter writer, Object object, Object fieldName, Type fieldType, long features) {
			if (object == null) {
				writer.writeNull();
			} else {
				writer.writeString(DateTimeFormatter.ISO_ZONED_DATE_TIME.format((ZonedDateTime) object));
			}
		}
	}

	/** ISO_ZONED_DATE_TIME 文本 → ZonedDateTime：偏移在区域规则下有效时原样保留（重叠小时不再丢失较晚的偏移）。 */
	private static final class ZonedDateTimeReader implements ObjectReader<ZonedDateTime> {
		@Override
		public ZonedDateTime readObject(JSONReader reader, Type fieldType, Object fieldName, long features) {
			if (reader.nextIfNull()) {
				return null;
			}
			return ZonedDateTime.parse(reader.readString());
		}
	}
}
