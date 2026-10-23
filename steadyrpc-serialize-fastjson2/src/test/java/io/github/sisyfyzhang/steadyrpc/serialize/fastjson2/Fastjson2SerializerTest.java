package io.github.sisyfyzhang.steadyrpc.serialize.fastjson2;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class Fastjson2SerializerTest {

	public static class User {
		private String name;
		private int age;

		public User() {
		}

		User(String name, int age) {
			this.name = name;
			this.age = age;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getAge() {
			return age;
		}

		public void setAge(int age) {
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
	}

	/** 只为取得带泛型实参的 Type。 */
	@SuppressWarnings("unused")
	private interface Types {
		List<User> users();

		Map<String, List<User>> catalog();

		Set<Long> ids();

		Object anything();
	}

	private static Type type(String method) throws NoSuchMethodException {
		return Types.class.getMethod(method).getGenericReturnType();
	}

	private final Fastjson2Serializer serializer = new Fastjson2Serializer();

	private Object roundTrip(Object value, Type type) {
		return serializer.deserialize(serializer.serialize(value, type), type);
	}

	@Test
	void deserializesByDeclaredGenericType() throws Exception {
		List<User> users = List.of(new User("alice", 30), new User("bob", 40));
		Object decoded = roundTrip(users, type("users"));
		assertEquals(users, decoded);
		assertInstanceOf(User.class, ((List<?>) decoded).get(0), "elements are restored as User, not JSONObject");

		Map<String, List<User>> catalog = Map.of("g", users);
		assertEquals(catalog, roundTrip(catalog, type("catalog")));
		assertEquals(Set.of(1L, 2L), roundTrip(Set.of(1L, 2L), type("ids")));
		assertArrayEquals(new byte[] { 1, 2, 3 }, (byte[]) roundTrip(new byte[] { 1, 2, 3 }, byte[].class));
		assertArrayEquals(new User[] { new User("x", 1) }, (User[]) roundTrip(new User[] { new User("x", 1) }, User[].class));
		assertEquals(7, roundTrip(7, int.class));
	}

	@Test
	void autoTypeStaysOff() throws Exception {
		// 线上不写类名；对端塞进来的 @type 不会被用来加载类
		byte[] bytes = serializer.serialize(new User("a", 1), User.class);
		assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("@type"));
		byte[] hostile = "{\"@type\":\"java.net.InetAddress\",\"val\":\"example.com\"}".getBytes(StandardCharsets.UTF_8);
		Object decoded = serializer.deserialize(hostile, type("anything"));
		assertInstanceOf(Map.class, decoded, "an Object-typed position stays a plain JSON object");
	}

	@Test
	void emptyBytesDecodeToEmptyObjects() throws Exception {
		assertNotNull(serializer.deserialize(new byte[0], User.class));
		assertEquals(List.of(), serializer.deserialize(new byte[0], type("users")));
		assertEquals("", serializer.deserialize(new byte[0], String.class));
	}

	public static class Meeting {
		private ZonedDateTime at;

		public ZonedDateTime getAt() {
			return at;
		}

		public void setAt(ZonedDateTime at) {
			this.at = at;
		}
	}

	/**
	 * fastjson2 默认把 ZonedDateTime 写成"本地时间 + 区域"、不带偏移，夏令时回拨那一小时里较晚的瞬间读回来会被平移一小时；
	 * 本实例的读写器写 ISO_ZONED_DATE_TIME（偏移 + 区域），顶层值与 POJO 字段都原样往返。也不污染全局：
	 * 直接用 fastjson2 的 JSON 门面序列化同一个值，仍是它自己的默认格式。
	 */
	@Test
	void zonedDateTimeKeepsTheOffsetInsideADaylightSavingOverlap() {
		ZonedDateTime later = ZonedDateTime.of(LocalDateTime.of(2024, 11, 3, 1, 30), ZoneId.of("America/New_York"))
				.withLaterOffsetAtOverlap();
		byte[] bytes = serializer.serialize(later, ZonedDateTime.class);
		assertEquals("\"2024-11-03T01:30:00-05:00[America/New_York]\"", new String(bytes, StandardCharsets.UTF_8));
		assertEquals(later, serializer.deserialize(bytes, ZonedDateTime.class));

		Meeting meeting = new Meeting();
		meeting.setAt(later);
		Meeting back = (Meeting) serializer.deserialize(serializer.serialize(meeting, Meeting.class), Meeting.class);
		assertEquals(ZoneOffset.ofHours(-5), back.getAt().getOffset());
		assertEquals(later, back.getAt());

		assertFalse(com.alibaba.fastjson2.JSON.toJSONString(later).contains("-05:00"),
				"the global fastjson2 default must be untouched by this serializer");
	}

	@Test
	void codeAndNameAreStable() {
		assertEquals((byte) 1, serializer.code());
		assertEquals("fastjson2", serializer.name());
	}
}
