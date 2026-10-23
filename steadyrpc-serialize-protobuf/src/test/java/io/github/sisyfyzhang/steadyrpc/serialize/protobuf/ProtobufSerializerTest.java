package io.github.sisyfyzhang.steadyrpc.serialize.protobuf;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.AllowlistIdStrategy;
import io.protostuff.runtime.RuntimeSchema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufSerializerTest {

	public static class User {
		public String name;
		public int age;

		public User() {
		}

		User(String name, int age) {
			this.name = name;
			this.age = age;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof User u && Objects.equals(name, u.name) && age == u.age;
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, age);
		}

		@Override
		public String toString() {
			return "User(" + name + "," + age + ")";
		}
	}

	public static class Holder {
		public Object value;
	}

	public static class OnlyRefs {
		public String a;
		public List<String> b;
	}

	public interface Animal {
	}

	public static class Dog implements Animal {
		public String name;
	}

	public static class Zoo {
		public Animal pet;
	}

	/** java.time 作为 POJO 字段与 Object 多态位置。 */
	public static class Times {
		public Instant instant;
		public LocalDate date;
		public LocalTime time;
		public LocalDateTime dateTime;
		public Duration duration;
		public OffsetDateTime offsetDateTime;
		public ZonedDateTime zonedDateTime;
		public Object anyTime;
		public List<Instant> history;
	}

	public enum Color {
		RED, GREEN
	}

	public record Point(int x, int y) {
	}

	/** 各种声明类型的 holder 方法，只为了拿到带泛型实参的 Type。 */
	@SuppressWarnings("unused")
	private interface Types {
		List<User> users();

		Map<String, List<User>> catalog();

		Set<Long> ids();

		User[] userArray();

		int[] ints();

		List<List<String>> nested();

		Map<User, Color> keyedByPojo();

		TreeMap<String, Integer> sorted();

		LinkedHashSet<String> linkedSet();

		List<? extends User> wildcard();

		Object anything();
	}

	private static Type type(String method) {
		try {
			return Types.class.getMethod(method).getGenericReturnType();
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static Object roundTrip(ProtobufSerializer serializer, Object value, Type type) {
		return serializer.deserialize(serializer.serialize(value, type), type);
	}

	private final ProtobufSerializer serializer = new ProtobufSerializer();

	@Test
	void roundTripsGenericCollectionsMapsAndArraysByDeclaredType() {
		List<User> users = List.of(new User("alice", 30), new User("bob", 40));
		Object decodedUsers = roundTrip(serializer, users, type("users"));
		assertEquals(users, decodedUsers);
		assertInstanceOf(User.class, ((List<?>) decodedUsers).get(0));

		Map<String, List<User>> catalog = new HashMap<>();
		catalog.put("g1", users);
		catalog.put("empty", List.of());
		assertEquals(catalog, roundTrip(serializer, catalog, type("catalog")));

		assertEquals(Set.of(1L, 2L, 3L), roundTrip(serializer, Set.of(1L, 2L, 3L), type("ids")));

		User[] array = { new User("c", 1), null, new User("d", 2) };
		assertArrayEquals(array, (User[]) roundTrip(serializer, array, User[].class));
		assertArrayEquals(new int[] { 1, -2, 3 }, (int[]) roundTrip(serializer, new int[] { 1, -2, 3 }, type("ints")));

		List<List<String>> nested = List.of(List.of("a", "b"), List.of(), List.of("c"));
		assertEquals(nested, roundTrip(serializer, nested, type("nested")));

		Map<User, Color> keyed = Map.of(new User("k", 1), Color.GREEN);
		assertEquals(keyed, roundTrip(serializer, keyed, type("keyedByPojo")));

		TreeMap<String, Integer> sorted = new TreeMap<>(Map.of("b", 2, "a", 1));
		Object decodedSorted = roundTrip(serializer, sorted, type("sorted"));
		assertInstanceOf(TreeMap.class, decodedSorted);
		assertEquals(sorted, decodedSorted);

		assertInstanceOf(LinkedHashSet.class, roundTrip(serializer, new LinkedHashSet<>(List.of("x")), type("linkedSet")));
		assertEquals(users, roundTrip(serializer, users, type("wildcard")));
	}

	@Test
	void topLevelCollectionsCarryNoClassNames() {
		byte[] bytes = serializer.serialize(List.of(new User("alice", 30), new User("bob", 40)), type("users"));
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		assertFalse(text.contains("User") || text.contains("java.util"), "declared element types are not written: " + text);
	}

	@Test
	void nullElementsAndNullMapValuesArePreserved() {
		List<User> withNull = Arrays.asList(new User("a", 1), null, new User("b", 2));
		assertEquals(withNull, roundTrip(serializer, withNull, type("users")));
		Map<String, List<User>> nullValue = new HashMap<>();
		nullValue.put("none", null);
		assertEquals(nullValue, roundTrip(serializer, nullValue, type("catalog")));
	}

	@Test
	void roundTripsScalarsAtTopLevel() {
		assertEquals("hello 世界", roundTrip(serializer, "hello 世界", String.class));
		assertEquals("", roundTrip(serializer, "", String.class));
		assertEquals(42, roundTrip(serializer, 42, int.class));
		assertEquals(0, roundTrip(serializer, 0, Integer.class));
		assertEquals(-7L, roundTrip(serializer, -7L, long.class));
		assertEquals(true, roundTrip(serializer, true, boolean.class));
		assertEquals(1.5d, roundTrip(serializer, 1.5d, Double.class));
		assertEquals('x', roundTrip(serializer, 'x', char.class));
		assertEquals((short) 3, roundTrip(serializer, (short) 3, Short.class));
		assertEquals(new BigDecimal("12.3400"), roundTrip(serializer, new BigDecimal("12.3400"), BigDecimal.class));
		assertEquals(new Date(123456789L), roundTrip(serializer, new Date(123456789L), Date.class));
		assertEquals(Color.GREEN, roundTrip(serializer, Color.GREEN, Color.class));
		assertArrayEquals(new byte[] { 1, 2, 3 }, (byte[]) roundTrip(serializer, new byte[] { 1, 2, 3 }, byte[].class));
		assertArrayEquals(new byte[0], (byte[]) roundTrip(serializer, new byte[0], byte[].class));
	}

	/**
	 * java.time 七个类型：作为根、作为 POJO 字段、作为 Object 多态值、作为集合元素都按 ISO-8601 文本编码；
	 * Instant 的纳秒、ZonedDateTime 的区域 ID 与偏移都原样保留。
	 */
	@Test
	void javaTimeTypesAreIsoScalarsEverywhere() {
		Instant instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
		LocalDate date = LocalDate.of(2024, 2, 29);
		LocalTime time = LocalTime.of(23, 59, 59, 1);
		LocalDateTime dateTime = LocalDateTime.of(date, time);
		Duration duration = Duration.ofHours(26).plusMinutes(3).plusNanos(5);
		OffsetDateTime offset = OffsetDateTime.of(dateTime, ZoneOffset.ofHours(8));
		// 夏令时回拨的那一小时：同一墙上时间有两个偏移，取较晚的那个，验证偏移不被"规范化"掉
		ZonedDateTime zoned = ZonedDateTime.of(LocalDateTime.of(2024, 11, 3, 1, 30), ZoneId.of("America/New_York"))
				.withLaterOffsetAtOverlap();

		for (Object value : List.of(instant, date, time, dateTime, duration, offset, zoned)) {
			Object decoded = roundTrip(serializer, value, value.getClass());
			assertEquals(value, decoded, value.getClass().getSimpleName());
		}
		assertEquals(123_456_789, ((Instant) roundTrip(serializer, instant, Instant.class)).getNano());
		ZonedDateTime zonedBack = (ZonedDateTime) roundTrip(serializer, zoned, ZonedDateTime.class);
		assertEquals(ZoneOffset.ofHours(-5), zonedBack.getOffset(), "the later offset at the overlap survives");
		assertEquals(ZoneId.of("America/New_York"), zonedBack.getZone());

		// 线上是 ISO 文本：根 = Box{1: string}
		String wire = new String(serializer.serialize(instant, Instant.class), StandardCharsets.UTF_8);
		assertTrue(wire.contains("2023-11-14T22:13:20.123456789Z"), wire);

		Times times = new Times();
		times.instant = instant;
		times.date = date;
		times.time = time;
		times.dateTime = dateTime;
		times.duration = duration;
		times.offsetDateTime = offset;
		times.zonedDateTime = zoned;
		times.anyTime = duration;
		times.history = Arrays.asList(instant, null, Instant.EPOCH);
		Times back = (Times) roundTrip(serializer, times, Times.class);
		assertEquals(instant, back.instant);
		assertEquals(date, back.date);
		assertEquals(time, back.time);
		assertEquals(dateTime, back.dateTime);
		assertEquals(duration, back.duration);
		assertEquals(offset, back.offsetDateTime);
		assertEquals(zoned, back.zonedDateTime);
		assertEquals(duration, back.anyTime, "Object position uses the registered delegate, not a POJO schema of Duration");
		assertEquals(times.history, back.history);
		String pojoWire = new String(serializer.serialize(times, Times.class), StandardCharsets.UTF_8);
		assertTrue(pojoWire.contains("2024-11-03T01:30:00-05:00[America/New_York]"), pojoWire);
	}

	@Test
	void emptyBytesMeanEmptyObjectNotNull() {
		OnlyRefs allNull = new OnlyRefs();
		byte[] bytes = serializer.serialize(allNull, OnlyRefs.class);
		assertEquals(0, bytes.length, "an object whose fields are all null encodes to 0 bytes (probe P9b2)");
		assertNotNull(serializer.deserialize(bytes, OnlyRefs.class), "present + 0 bytes must decode to newMessage(), not null");

		Object emptyList = serializer.deserialize(serializer.serialize(List.of(), type("users")), type("users"));
		assertEquals(List.of(), emptyList);
		assertEquals(Map.of(), serializer.deserialize(new byte[0], type("catalog")));
		assertEquals("", serializer.deserialize(new byte[0], String.class));
	}

	@Test
	void collectionFieldsInsidePojosKeepEmptyVersusNullAndNullElements() {
		OnlyRefs empty = new OnlyRefs();
		empty.b = new ArrayList<>();
		assertEquals(List.of(), ((OnlyRefs) roundTrip(serializer, empty, OnlyRefs.class)).b, "empty list field stays empty");

		OnlyRefs absent = new OnlyRefs();
		absent.a = "x";
		assertEquals(null, ((OnlyRefs) roundTrip(serializer, absent, OnlyRefs.class)).b, "null list field stays null");

		OnlyRefs withNull = new OnlyRefs();
		withNull.b = Arrays.asList("p", null, "q");
		assertEquals(withNull.b, ((OnlyRefs) roundTrip(serializer, withNull, OnlyRefs.class)).b);
	}

	@Test
	void polymorphicPositionsUseObjectHolderAndAllowlist() {
		ProtobufSerializer permissive = new ProtobufSerializer(List.of("io.github.sisyfyzhang.steadyrpc.serialize.protobuf."));
		Zoo zoo = new Zoo();
		Dog dog = new Dog();
		dog.name = "odie";
		zoo.pet = dog;
		byte[] bytes = permissive.serialize(zoo, Zoo.class);

		Zoo decoded = (Zoo) permissive.deserialize(bytes, Zoo.class);
		assertEquals("odie", ((Dog) decoded.pet).name);

		// 默认白名单不含业务包：接收方拒绝按名加载 Dog，异常里带类名
		AllowlistIdStrategy.ClassNotAllowedException e = assertThrows(AllowlistIdStrategy.ClassNotAllowedException.class,
				() -> serializer.deserialize(bytes, Zoo.class));
		assertEquals(Dog.class.getName(), e.className());
		assertTrue(e.getMessage().contains(Dog.class.getName()) && e.getMessage().contains(ProtobufSerializer.OPTION_ALLOWED_PACKAGES),
				e.getMessage());

		// 声明为 Object 的根：JDK 类型在默认白名单内
		Object anything = roundTrip(serializer, new ArrayList<>(List.of(1, 2)), type("anything"));
		assertEquals(List.of(1, 2), anything);
	}

	@Test
	void forgedClassNameOutsideAllowlistIsRejectedBeforeLoading() {
		// 手工构造 Holder{Object} 的线上字节，把类名换成白名单外、且根本不存在的类：必须在加载前就被拒绝
		ProtobufSerializer permissive = new ProtobufSerializer(List.of("io.github.sisyfyzhang.steadyrpc.serialize.protobuf."));
		Holder holder = new Holder();
		Dog dog = new Dog();
		dog.name = "x";
		holder.value = dog;
		byte[] original = permissive.serialize(holder, Holder.class);
		String text = new String(original, StandardCharsets.ISO_8859_1);
		String forged = text.replace(Dog.class.getName(), ("evil.gadget.Exploit" + "0".repeat(128)).substring(0, Dog.class.getName().length()));
		byte[] forgedBytes = forged.getBytes(StandardCharsets.ISO_8859_1);

		AllowlistIdStrategy.ClassNotAllowedException e = assertThrows(AllowlistIdStrategy.ClassNotAllowedException.class,
				() -> permissive.deserialize(forgedBytes, Holder.class));
		assertTrue(e.className().startsWith("evil.gadget."), e.className());
	}

	@Test
	void guardRefusesPojoSchemaForCollectionsAndKeepsStrategyClean() {
		ProtobufSerializer permissive = new ProtobufSerializer(List.of("io.github.sisyfyzhang.steadyrpc.serialize.protobuf."));
		AllowlistIdStrategy strategy = permissive.idStrategy();

		// 1. 守卫：对集合 / Map 实现类建 POJO schema 直接失败，而不是静默登记一份只编码 size 的 schema
		assertThrows(IllegalArgumentException.class, () -> RuntimeSchema.getSchema(ArrayList.class, strategy));
		assertThrows(IllegalArgumentException.class, () -> RuntimeSchema.getSchema(HashMap.class, strategy));
		assertThrows(IllegalArgumentException.class, () -> permissive.schemas().pojo(ArrayList.class));
		assertFalse(strategy.isRegistered(ArrayList.class));

		// 2. 守卫拦下之后，同一策略下 Object 位置里的 ArrayList 仍然正确往返（探针 P2c-2 的损坏没有发生）
		Holder holder = new Holder();
		holder.value = new ArrayList<>(List.of(new User("alice", 30), new User("bob", 40)));
		Holder decoded = (Holder) roundTrip(permissive, holder, Holder.class);
		assertEquals(holder.value, decoded.value);
		assertInstanceOf(User.class, ((List<?>) decoded.value).get(1));

		// 3. 序列化器从不碰全局策略：大量顶层集合往返之后，全局 ID_STRATEGY 下 Holder{Object}=ArrayList 依旧正确
		for (int i = 0; i < 3; i++) {
			roundTrip(serializer, List.of(new User("u", i)), type("users"));
			roundTrip(serializer, Map.of("k", List.of(new User("v", i))), type("catalog"));
		}
		Schema<Holder> global = RuntimeSchema.getSchema(Holder.class);
		byte[] bytes = ProtostuffIOUtil.toByteArray(holder, global, LinkedBuffer.allocate(256));
		Holder globalDecoded = global.newMessage();
		ProtostuffIOUtil.mergeFrom(bytes, globalDecoded, global);
		assertEquals(holder.value, globalDecoded.value);
	}

	@Test
	void recordsFailFastWithAClearMessage() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> serializer.serialize(new Point(1, 2), Point.class));
		assertTrue(e.getMessage().contains("record"), e.getMessage());
	}

	@Test
	void withOptionsAddsAllowedPrefixesAndReturnsANewInstance() {
		assertSame(serializer, serializer.withOptions(Map.of()));
		ProtobufSerializer configured = (ProtobufSerializer) serializer.withOptions(
				Map.of(ProtobufSerializer.OPTION_ALLOWED_PACKAGES, " com.acme.api. , com.acme.dto.Order"));
		assertEquals(List.of("java.util.", "java.lang.", "java.time.", "java.math.", "com.acme.api.", "com.acme.dto.Order"),
				configured.allowedPrefixes());
		assertTrue(configured.isAllowed("com.acme.api.User"));
		assertTrue(configured.isAllowed("com.acme.dto.OrderLine"));
		assertFalse(configured.isAllowed("com.acme.internal.Secret"));
		assertEquals(ProtobufSerializer.defaultAllowedPrefixes(), serializer.allowedPrefixes(), "original instance unchanged");
	}

	@Test
	void codeAndNameAreStable() {
		assertEquals((byte) 2, serializer.code());
		assertEquals("protobuf", serializer.name());
	}
}
