package com.feynman.rpc.serialize.protobuf;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import io.protostuff.CollectionSchema;
import io.protostuff.Input;
import io.protostuff.LinkedBuffer;
import io.protostuff.MapSchema;
import io.protostuff.Output;
import io.protostuff.Pipe;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.Delegate;
import io.protostuff.runtime.IdStrategy;
import io.protostuff.runtime.RuntimeSchema;

/**
 * 按<b>声明类型</b>（含泛型实参）递归构造 Protostuff 的编码方式（D-005）。
 *
 * <table>
 *   <caption>Type → 编码</caption>
 *   <tr><th>声明类型</th><th>作为字段 / 元素</th><th>作为根（整个参数 / 返回值）</th></tr>
 *   <tr><td>POJO（具体类）</td><td>嵌套消息，RuntimeSchema</td><td>RuntimeSchema 直接做根</td></tr>
 *   <tr><td>{@code Collection<E>} / List / Set / Queue 及具体 JDK 实现</td>
 *       <td>嵌套消息，CollectionSchema（元素按 E 递归），保留 null 元素</td><td>CollectionSchema 直接做根</td></tr>
 *   <tr><td>{@code Map<K,V>} 及具体 JDK 实现</td><td>嵌套消息，MapSchema（K、V 递归）</td><td>MapSchema 直接做根</td></tr>
 *   <tr><td>数组 {@code T[]}（除 byte[]）、基本类型数组</td><td>按 {@code List<T>} 编码，读回再转成数组</td><td>同左</td></tr>
 *   <tr><td>byte[]、String、8 种基本类型及包装、BigDecimal、BigInteger、Date、枚举（按名）、
 *       java.time 的 Instant / LocalDate / LocalTime / LocalDateTime / Duration / OffsetDateTime / ZonedDateTime
 *       （ISO-8601 文本，见 {@link JavaTimeDelegates}）</td>
 *       <td>protobuf 标量字段</td><td>单字段包装消息（field 1）</td></tr>
 *   <tr><td>Object、接口、抽象类、无界类型变量 / 通配符</td>
 *       <td colspan="2">框架的 {@code ObjectHolder{Object value}}：走 Protostuff 的多态编码，线上写类名，
 *       读取时按名加载受 {@link io.protostuff.runtime.AllowlistIdStrategy} 白名单约束</td></tr>
 * </table>
 *
 * <p>为什么不对集合运行时类调 {@code RuntimeSchema.getSchema}：那会把集合当 POJO，只编码 size，并永久污染策略；
 * 集合必须由声明类型驱动走集合 schema。{@link #pojo} 与策略里各有一道守卫。
 *
 * <p>空对象：根为消息形态且收到 0 字节时返回 {@code schema.newMessage()}（全 null 字段的 POJO 编码就是 0 字节，
 * 空集合同理），不返回 null——null 由信封的 present 标志表达。
 *
 * <p>Pipe（protostuff 的格式转换管道）不支持，相关方法抛 UnsupportedOperationException：RPC 路径只做字节往返。
 */
final class TypeSchemas {

	private final IdStrategy strategy;
	private final Map<Type, Codec> codecs = new ConcurrentHashMap<>();
	private final Map<Type, Root> roots = new ConcurrentHashMap<>();

	TypeSchemas(IdStrategy strategy) {
		this.strategy = strategy;
	}

	Root root(Type declaredType) {
		Root root = roots.get(declaredType);
		if (root == null) {
			Codec codec = codec(declaredType);
			root = codec instanceof MessageCodec message ? new MessageRoot(message) : new BoxRoot((ScalarCodec) codec);
			Root previous = roots.putIfAbsent(declaredType, root);
			if (previous != null) {
				root = previous;
			}
		}
		return root;
	}

	/** 不用 computeIfAbsent：构造过程会递归查询元素类型，ConcurrentHashMap 不允许在 computeIfAbsent 里修改自身。 */
	Codec codec(Type type) {
		Codec codec = codecs.get(type);
		if (codec == null) {
			codec = build(type);
			Codec previous = codecs.putIfAbsent(type, codec);
			if (previous != null) {
				codec = previous;
			}
		}
		return codec;
	}

	private Codec build(Type type) {
		if (type instanceof Class<?> clazz) {
			return buildForClass(clazz, null);
		}
		if (type instanceof ParameterizedType parameterized) {
			return buildForClass((Class<?>) parameterized.getRawType(), parameterized.getActualTypeArguments());
		}
		if (type instanceof GenericArrayType array) {
			Type component = array.getGenericComponentType();
			return arrayCodec(erase(component), codec(component));
		}
		if (type instanceof WildcardType wildcard) {
			return codec(wildcard.getLowerBounds().length > 0 ? Object.class : wildcard.getUpperBounds()[0]);
		}
		if (type instanceof TypeVariable<?> variable) {
			Type[] bounds = variable.getBounds();
			return codec(bounds.length == 0 ? Object.class : bounds[0]);
		}
		throw new IllegalArgumentException("unsupported declared type: " + type);
	}

	private Codec buildForClass(Class<?> raw, Type[] typeArgs) {
		ScalarCodec scalar = ScalarCodec.forClass(raw);
		if (scalar != null) {
			return scalar;
		}
		if (raw.isArray()) {
			return arrayCodec(raw.getComponentType(), codec(raw.getComponentType()));
		}
		if (Collection.class.isAssignableFrom(raw)) {
			Type element = typeArgs != null && typeArgs.length == 1 ? typeArgs[0] : Object.class;
			ElementCollectionSchema schema = new ElementCollectionSchema(collectionFactory(raw), codec(element));
			return MessageCodec.of(schema);
		}
		if (Map.class.isAssignableFrom(raw)) {
			Type key = typeArgs != null && typeArgs.length == 2 ? typeArgs[0] : Object.class;
			Type value = typeArgs != null && typeArgs.length == 2 ? typeArgs[1] : Object.class;
			EntryMapSchema schema = new EntryMapSchema(mapFactory(raw), codec(key), codec(value));
			return MessageCodec.of(schema);
		}
		if (raw == Object.class || raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
			Schema<ObjectHolder> holder = RuntimeSchema.getSchema(ObjectHolder.class, strategy);
			return new MessageCodec(cast(holder), value -> new ObjectHolder(value), message -> ((ObjectHolder) message).value);
		}
		return MessageCodec.of(pojo(raw));
	}

	/** POJO schema 的唯一入口：集合 / Map / record 在这里就被拒绝，不会走到 RuntimeSchema。 */
	Schema<Object> pojo(Class<?> raw) {
		if (Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
			throw new IllegalArgumentException("collection/map type " + raw.getName()
					+ " must not get a POJO RuntimeSchema (it would silently drop the elements)");
		}
		if (raw.isRecord()) {
			throw new IllegalArgumentException("records are not supported by Protostuff 1.8 (cannot restore final fields): "
					+ raw.getName() + "; use a class with a no-arg constructor and mutable fields");
		}
		return cast(RuntimeSchema.getSchema(raw, strategy));
	}

	private Codec arrayCodec(Class<?> component, Codec componentCodec) {
		ElementCollectionSchema list = new ElementCollectionSchema(CollectionSchema.MessageFactories.ArrayList, componentCodec);
		return new MessageCodec(cast(list), array -> {
			int length = Array.getLength(array);
			List<Object> values = new ArrayList<>(length);
			for (int i = 0; i < length; i++) {
				values.add(Array.get(array, i));
			}
			return values;
		}, message -> {
			Collection<?> values = (Collection<?>) message;
			Object array = Array.newInstance(component, values.size());
			int i = 0;
			for (Object value : values) {
				Array.set(array, i++, value);
			}
			return array;
		});
	}

	private static CollectionSchema.MessageFactory collectionFactory(Class<?> raw) {
		if (raw.getName().startsWith("java.util")) {
			try {
				return CollectionSchema.MessageFactories.valueOf(raw.getSimpleName());
			} catch (IllegalArgumentException notAStandardType) {
				// 落到下面的具体类分支
			}
		}
		if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
			throw new IllegalArgumentException("unsupported collection type " + raw.getName() + "; declare List/Set/Collection or a concrete class");
		}
		Supplier<Object> constructor = noArgConstructor(raw);
		return new CollectionSchema.MessageFactory() {
			@Override
			@SuppressWarnings("unchecked")
			public <V> Collection<V> newMessage() {
				return (Collection<V>) constructor.get();
			}

			@Override
			public Class<?> typeClass() {
				return raw;
			}
		};
	}

	private static MapSchema.MessageFactory mapFactory(Class<?> raw) {
		if (raw.getName().startsWith("java.util")) {
			try {
				return MapSchema.MessageFactories.valueOf(raw.getSimpleName());
			} catch (IllegalArgumentException notAStandardType) {
				// 落到下面的具体类分支
			}
		}
		if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) {
			throw new IllegalArgumentException("unsupported map type " + raw.getName() + "; declare Map or a concrete class");
		}
		Supplier<Object> constructor = noArgConstructor(raw);
		return new MapSchema.MessageFactory() {
			@Override
			@SuppressWarnings("unchecked")
			public <K, V> Map<K, V> newMessage() {
				return (Map<K, V>) constructor.get();
			}

			@Override
			public Class<?> typeClass() {
				return raw;
			}
		};
	}

	private static Supplier<Object> noArgConstructor(Class<?> raw) {
		try {
			java.lang.reflect.Constructor<?> constructor = raw.getDeclaredConstructor();
			constructor.setAccessible(true);
			return () -> {
				try {
					return constructor.newInstance();
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("cannot instantiate " + raw.getName(), e);
				}
			};
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException(raw.getName() + " has no no-arg constructor", e);
		}
	}

	static Class<?> erase(Type type) {
		if (type instanceof Class<?> clazz) {
			return clazz;
		}
		if (type instanceof ParameterizedType parameterized) {
			return (Class<?>) parameterized.getRawType();
		}
		if (type instanceof GenericArrayType array) {
			return Array.newInstance(erase(array.getGenericComponentType()), 0).getClass();
		}
		if (type instanceof WildcardType wildcard) {
			return wildcard.getLowerBounds().length > 0 ? Object.class : erase(wildcard.getUpperBounds()[0]);
		}
		if (type instanceof TypeVariable<?> variable) {
			return variable.getBounds().length == 0 ? Object.class : erase(variable.getBounds()[0]);
		}
		return Object.class;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Schema<Object> cast(Schema<?> schema) {
		return (Schema) schema;
	}

	// ====================================================================== 编码单元

	/** 一个声明类型作为字段 / 元素时的读写方式。 */
	interface Codec {
		void write(Output output, int number, Object value, boolean repeated) throws IOException;

		Object read(Input input) throws IOException;
	}

	/** 有消息形态的类型：作为字段时是嵌套消息，作为根时 schema 直接做根。可带一层值 ↔ 消息的转换（数组、多态包装）。 */
	static final class MessageCodec implements Codec {
		final Schema<Object> schema;
		final Function<Object, Object> toMessage;
		final Function<Object, Object> fromMessage;

		MessageCodec(Schema<Object> schema, Function<Object, Object> toMessage, Function<Object, Object> fromMessage) {
			this.schema = schema;
			this.toMessage = toMessage;
			this.fromMessage = fromMessage;
		}

		static MessageCodec of(Schema<?> schema) {
			return new MessageCodec(cast(schema), Function.identity(), Function.identity());
		}

		@Override
		public void write(Output output, int number, Object value, boolean repeated) throws IOException {
			output.writeObject(number, toMessage.apply(value), schema, repeated);
		}

		@Override
		public Object read(Input input) throws IOException {
			return fromMessage.apply(input.mergeObject(null, schema));
		}
	}

	/** 标量：protobuf 原生字段类型。 */
	static final class ScalarCodec implements Codec {

		interface Writer {
			void write(Output output, int number, Object value, boolean repeated) throws IOException;
		}

		interface Reader {
			Object read(Input input) throws IOException;
		}

		final Writer writer;
		final Reader reader;
		final Supplier<Object> empty;

		ScalarCodec(Writer writer, Reader reader, Supplier<Object> empty) {
			this.writer = writer;
			this.reader = reader;
			this.empty = empty;
		}

		@Override
		public void write(Output output, int number, Object value, boolean repeated) throws IOException {
			writer.write(output, number, value, repeated);
		}

		@Override
		public Object read(Input input) throws IOException {
			return reader.read(input);
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		static ScalarCodec forClass(Class<?> raw) {
			if (raw == String.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeString(n, (String) v, r), Input::readString, () -> "");
			}
			if (raw == byte[].class) {
				return new ScalarCodec((o, n, v, r) -> o.writeByteArray(n, (byte[]) v, r), Input::readByteArray, () -> new byte[0]);
			}
			if (raw == Integer.class || raw == int.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeInt32(n, (Integer) v, r), Input::readInt32, () -> 0);
			}
			if (raw == Long.class || raw == long.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeInt64(n, (Long) v, r), Input::readInt64, () -> 0L);
			}
			if (raw == Boolean.class || raw == boolean.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeBool(n, (Boolean) v, r), Input::readBool, () -> false);
			}
			if (raw == Double.class || raw == double.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeDouble(n, (Double) v, r), Input::readDouble, () -> 0d);
			}
			if (raw == Float.class || raw == float.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeFloat(n, (Float) v, r), Input::readFloat, () -> 0f);
			}
			if (raw == Short.class || raw == short.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeInt32(n, (Short) v, r), i -> (short) i.readInt32(), () -> (short) 0);
			}
			if (raw == Byte.class || raw == byte.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeInt32(n, (Byte) v, r), i -> (byte) i.readInt32(), () -> (byte) 0);
			}
			if (raw == Character.class || raw == char.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeUInt32(n, (Character) v, r), i -> (char) i.readUInt32(), () -> (char) 0);
			}
			if (raw == BigDecimal.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeString(n, ((BigDecimal) v).toString(), r),
						i -> new BigDecimal(i.readString()), () -> BigDecimal.ZERO);
			}
			if (raw == BigInteger.class) {
				return new ScalarCodec((o, n, v, r) -> o.writeByteArray(n, ((BigInteger) v).toByteArray(), r),
						i -> new BigInteger(i.readByteArray()), () -> BigInteger.ZERO);
			}
			if (raw == Date.class) {
				// Date 的数据在 transient 字段里，当 POJO 处理会得到空对象；按毫秒时间戳编码
				return new ScalarCodec((o, n, v, r) -> o.writeFixed64(n, ((Date) v).getTime(), r),
						i -> new Date(i.readFixed64()), () -> new Date(0));
			}
			if (raw.getName().startsWith("java.time.")) {
				// 与注册进 IdStrategy 的委托是同一套编码：作为根 / 集合元素（这里）与作为 POJO 字段（策略里的委托）线上一致
				Delegate delegate = JavaTimeDelegates.forClass(raw);
				if (delegate != null) {
					return new ScalarCodec((o, n, v, r) -> delegate.writeTo(o, n, v, r), delegate::readFrom, () -> null);
				}
			}
			if (raw.isEnum()) {
				Class<? extends Enum> enumType = (Class<? extends Enum>) raw;
				// 按名编码：枚举常量调整顺序不会让两端错位
				return new ScalarCodec((o, n, v, r) -> o.writeString(n, ((Enum<?>) v).name(), r),
						i -> Enum.valueOf(enumType, i.readString()), () -> null);
			}
			return null;
		}
	}

	// ====================================================================== 根

	/** 一个声明类型作为整个参数 / 返回值时的字节编码。 */
	interface Root {
		byte[] write(Object value);

		Object read(byte[] bytes);
	}

	static final class MessageRoot implements Root {
		private final MessageCodec codec;

		MessageRoot(MessageCodec codec) {
			this.codec = codec;
		}

		@Override
		public byte[] write(Object value) {
			LinkedBuffer buffer = LinkedBuffer.allocate(512);
			try {
				return ProtostuffIOUtil.toByteArray(codec.toMessage.apply(value), codec.schema, buffer);
			} finally {
				buffer.clear();
			}
		}

		@Override
		public Object read(byte[] bytes) {
			Object message = codec.schema.newMessage();
			if (bytes.length > 0) {
				ProtostuffIOUtil.mergeFrom(bytes, message, codec.schema);
			}
			return codec.fromMessage.apply(message);
		}
	}

	/** 标量做根：包一层只有 field 1 的消息（protobuf 的顶层必须是消息）。 */
	static final class BoxRoot implements Root {
		private final ScalarCodec codec;
		private final Schema<Object[]> schema;

		BoxRoot(ScalarCodec codec) {
			this.codec = codec;
			this.schema = new BoxSchema(codec);
		}

		@Override
		public byte[] write(Object value) {
			LinkedBuffer buffer = LinkedBuffer.allocate(LinkedBuffer.MIN_BUFFER_SIZE);
			try {
				return ProtostuffIOUtil.toByteArray(new Object[] { value }, schema, buffer);
			} finally {
				buffer.clear();
			}
		}

		@Override
		public Object read(byte[] bytes) {
			Object[] box = schema.newMessage();
			if (bytes.length > 0) {
				ProtostuffIOUtil.mergeFrom(bytes, box, schema);
			}
			return box[0] != null ? box[0] : codec.empty.get();
		}
	}

	private static final class BoxSchema implements Schema<Object[]> {
		private final Codec codec;

		BoxSchema(Codec codec) {
			this.codec = codec;
		}

		@Override
		public String getFieldName(int number) {
			return number == 1 ? "v" : null;
		}

		@Override
		public int getFieldNumber(String name) {
			return "v".equals(name) ? 1 : 0;
		}

		@Override
		public boolean isInitialized(Object[] message) {
			return true;
		}

		@Override
		public Object[] newMessage() {
			return new Object[1];
		}

		@Override
		public String messageName() {
			return "Box";
		}

		@Override
		public String messageFullName() {
			return TypeSchemas.class.getName() + ".Box";
		}

		@Override
		public Class<? super Object[]> typeClass() {
			return Object[].class;
		}

		@Override
		public void mergeFrom(Input input, Object[] message) throws IOException {
			for (int number = input.readFieldNumber(this); number != 0; number = input.readFieldNumber(this)) {
				if (number == 1) {
					message[0] = codec.read(input);
				} else {
					input.handleUnknownField(number, this);
				}
			}
		}

		@Override
		public void writeTo(Output output, Object[] message) throws IOException {
			if (message[0] != null) {
				codec.write(output, 1, message[0], false);
			}
		}
	}

	// ====================================================================== 集合与 Map

	/** 元素按声明类型编码的集合 schema；preserveNull=true，null 元素按位置保留。 */
	static final class ElementCollectionSchema extends CollectionSchema<Object> {
		private final Codec element;

		ElementCollectionSchema(CollectionSchema.MessageFactory factory, Codec element) {
			super(factory, true);
			this.element = element;
		}

		@Override
		protected void addValueFrom(Input input, Collection<Object> collection) throws IOException {
			collection.add(element.read(input));
		}

		@Override
		protected void writeValueTo(Output output, int fieldNumber, Object value, boolean repeated) throws IOException {
			element.write(output, fieldNumber, value, repeated);
		}

		@Override
		protected void transferValue(Pipe pipe, Input input, Output output, int number, boolean repeated) {
			throw new UnsupportedOperationException("protostuff pipes are not supported");
		}
	}

	/** key / value 按声明类型编码的 Map schema；null key / value 由 MapSchema 自身保留。 */
	static final class EntryMapSchema extends MapSchema<Object, Object> {
		private final Codec key;
		private final Codec value;

		EntryMapSchema(MapSchema.MessageFactory factory, Codec key, Codec value) {
			super(factory);
			this.key = key;
			this.value = value;
		}

		@Override
		protected Object readKeyFrom(Input input, MapWrapper<Object, Object> wrapper) throws IOException {
			return key.read(input);
		}

		@Override
		protected void putValueFrom(Input input, MapWrapper<Object, Object> wrapper, Object k) throws IOException {
			wrapper.put(k, value.read(input));
		}

		@Override
		protected void writeKeyTo(Output output, int fieldNumber, Object k, boolean repeated) throws IOException {
			key.write(output, fieldNumber, k, repeated);
		}

		@Override
		protected void writeValueTo(Output output, int fieldNumber, Object v, boolean repeated) throws IOException {
			value.write(output, fieldNumber, v, repeated);
		}

		@Override
		protected void transferKey(Pipe pipe, Input input, Output output, int number, boolean repeated) {
			throw new UnsupportedOperationException("protostuff pipes are not supported");
		}

		@Override
		protected void transferValue(Pipe pipe, Input input, Output output, int number, boolean repeated) {
			throw new UnsupportedOperationException("protostuff pipes are not supported");
		}
	}

	/** 多态位置的包装：唯一字段声明为 Object，由 Protostuff 写入具体类名并按名加载（受白名单约束）。 */
	static final class ObjectHolder {
		Object value;

		ObjectHolder() {
		}

		ObjectHolder(Object value) {
			this.value = value;
		}
	}
}
