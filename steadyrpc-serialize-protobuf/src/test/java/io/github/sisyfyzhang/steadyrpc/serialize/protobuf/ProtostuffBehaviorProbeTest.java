package io.github.sisyfyzhang.steadyrpc.serialize.protobuf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.Tag;
import io.protostuff.runtime.DefaultIdStrategy;
import io.protostuff.runtime.Field;
import io.protostuff.runtime.RuntimeEnv;
import io.protostuff.runtime.RuntimeSchema;

/**
 * 阶段 0 探针：记录 Protostuff 1.8.0 在本机 JDK 上的实际运行时行为（泛型、多态、record、字段演进、
 * IdStrategy）。探针的目的是记录事实而不是校验期望：每个探针捕获所有异常并登记结果，测试本身总是通过。
 * 结果表在 {@code @AfterAll} 中打印并写到 {@code target/probe-protostuff-<unsafe|reflect>.md}。
 */
@org.junit.jupiter.api.Tag("probe")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProtostuffBehaviorProbeTest {

	private static final String NS = ProtostuffBehaviorProbeTest.class.getName() + "$";
	private static final Map<String, String[]> ROWS = new ConcurrentSkipListMap<>();

	// ---------------------------------------------------------------- 探针用的类型

	static class User {
		String name;
		int age;

		User() {
		}

		User(String name, int age) {
			this.name = name;
			this.age = age;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof User u && u.age == age && Objects.equals(u.name, name);
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

	static class Team {
		String name;
		List<User> members;
	}

	static class Holder {
		Object value;
	}

	interface Animal {
		String sound();
	}

	static class Dog implements Animal {
		String name;

		@Override
		public String sound() {
			return "woof";
		}
	}

	static abstract class AbstractAnimal {
		String name;
	}

	static class Cat extends AbstractAnimal {
		int lives;
	}

	static class BaseAnimal {
		String name;
	}

	static class Puppy extends BaseAnimal {
		int age;
	}

	static class Zoo {
		Animal pet;
	}

	static class AbsZoo {
		AbstractAnimal pet;
	}

	static class ConcreteZoo {
		BaseAnimal pet;
	}

	record Point(int x, int y) {
	}

	record Order(long id, List<User> items) {
	}

	static class V1 {
		int a;
		int b;
	}

	static class V2 {
		int a;
		int c;
		int b;
	}

	static class V1Tag {
		@Tag(1)
		int a;
		@Tag(2)
		int b;
	}

	static class V2Tag {
		@Tag(1)
		int a;
		@Tag(3)
		int c;
		@Tag(2)
		int b;
	}

	static class Catalog {
		Map<String, List<User>> byGroup;
	}

	static class Blob {
		String name;
		byte[] data;
	}

	// ---------------------------------------------------------------- 工具

	private static <T> byte[] ser(T obj, Schema<T> schema) {
		LinkedBuffer buf = LinkedBuffer.allocate(512);
		try {
			return ProtostuffIOUtil.toByteArray(obj, schema, buf);
		} finally {
			buf.clear();
		}
	}

	private static <T> T de(byte[] bytes, Schema<T> schema) {
		T m = schema.newMessage();
		ProtostuffIOUtil.mergeFrom(bytes, m, schema);
		return m;
	}

	private static final String[] NAME_TOKENS = {"java.util", NS + "User", NS + "Dog", NS + "Cat", NS + "Puppy",
			"ArrayList", "HashMap"};

	/** 字节里出现的类名字符串（ISO_8859_1 解读）。 */
	private static String names(byte[] b) {
		String s = new String(b, StandardCharsets.ISO_8859_1);
		List<String> found = new ArrayList<>();
		for (String t : NAME_TOKENS) {
			if (s.contains(t)) {
				found.add(t.startsWith(NS) ? "<FQCN>$" + t.substring(NS.length()) : t);
			}
		}
		return found.isEmpty() ? "否" : "是: " + String.join(", ", found);
	}

	private static String printable(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (byte x : b) {
			int c = x & 0xff;
			sb.append(c >= 0x20 && c < 0x7f ? (char) c : '.');
		}
		return sb.toString();
	}

	private static String ex(Throwable t) {
		StringBuilder sb = new StringBuilder();
		Throwable cur = t;
		int depth = 0;
		while (cur != null && depth < 4) {
			if (depth > 0) {
				sb.append(" <- caused by ");
			}
			sb.append(cur.getClass().getName()).append(": ").append(cur.getMessage());
			cur = cur.getCause() == cur ? null : cur.getCause();
			depth++;
		}
		return sb.toString();
	}

	private static String safe(Supplier<Object> s) {
		try {
			return String.valueOf(s.get());
		} catch (Throwable t) {
			return "<描述时抛出 " + t.getClass().getSimpleName() + ": " + t.getMessage() + ">";
		}
	}

	/** 比较往返结果；损坏的集合在 equals 时会抛 ConcurrentModificationException，单独标记。 */
	private static String eq(Object expected, Object actual) {
		try {
			return Objects.deepEquals(expected, actual) ? "成功" : "往返不等";
		} catch (Throwable t) {
			return "对象损坏(反序列化无异常，equals 抛 " + t.getClass().getSimpleName() + ")";
		}
	}

	private static String cls(Object o) {
		return o == null ? "null" : o.getClass().getName().replace(NS, "");
	}

	private static String fields(RuntimeSchema<?> s) {
		StringBuilder sb = new StringBuilder("[");
		for (Field<?> f : s.getFields()) {
			sb.append(f.number).append('=').append(f.name).append(' ');
		}
		return sb.toString().trim() + "]";
	}

	private static void record(String id, String result, Throwable e, byte[] bytes, String note) {
		if (e != null) {
			System.out.println("==== " + id + " exception:");
			e.printStackTrace(System.out);
		}
		String[] row = {id, result, e == null ? "-" : ex(e), bytes == null ? "-" : String.valueOf(bytes.length),
				bytes == null ? "-" : names(bytes), note == null ? "" : note};
		ROWS.put(id, row);
		System.out.println("PROBE | " + String.join(" | ", row)
				+ (bytes == null ? "" : " | printable=" + printable(bytes)));
	}

	private static String md(String s) {
		return s.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
	}

	@AfterAll
	static void dump() throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("JVM: ").append(System.getProperty("java.version")).append(" / USE_SUN_MISC_UNSAFE=")
				.append(RuntimeEnv.USE_SUN_MISC_UNSAFE).append(" / ID_STRATEGY=")
				.append(RuntimeEnv.ID_STRATEGY.getClass().getName()).append("\n\n");
		sb.append("| 探针 | 结果 | 异常 | 字节数 | 含类名 | 备注 |\n|---|---|---|---|---|---|\n");
		for (String[] r : ROWS.values()) {
			sb.append('|');
			for (String c : r) {
				sb.append(' ').append(md(c)).append(" |");
			}
			sb.append('\n');
		}
		System.out.println(sb);
		Path out = Path.of("target",
				"probe-protostuff-" + (RuntimeEnv.USE_SUN_MISC_UNSAFE ? "unsafe" : "reflect") + ".md");
		Files.createDirectories(out.getParent());
		Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
	}

	private static ArrayList<User> users() {
		return new ArrayList<>(List.of(new User("alice", 30), new User("bob", 40)));
	}

	// ---------------------------------------------------------------- P1

	@Test
	void p1PojoWithListField() {
		Team t = new Team();
		t.name = "core";
		t.members = users();
		byte[] b = null;
		try {
			Schema<Team> s = RuntimeSchema.getSchema(Team.class);
			b = ser(t, s);
			Team d = de(b, s);
			record("P1 POJO{List<User>}", eq(t.members, d.members), null, b, "name=" + d.name + "，members 类型="
					+ cls(d.members) + "，元素类型=" + safe(() -> cls(d.members.get(0))) + "，值=" + safe(() -> d.members));
		} catch (Throwable e) {
			record("P1 POJO{List<User>}", "失败", e, b, "");
		}
	}

	// ---------------------------------------------------------------- P2

	@Test
	@org.junit.jupiter.api.Order(Integer.MAX_VALUE - 10)
	void p2TopLevelArrayList() {
		ArrayList<User> list = users();
		byte[] b = null;
		String id = "P2a ProtobufSerializer 顶层 ArrayList<User>";
		try {
			LegacyProtobufSerializer ps = new LegacyProtobufSerializer();
			b = ps.serialize(list);
			@SuppressWarnings("unchecked")
			ArrayList<Object> d = ps.deserialize(b, ArrayList.class);
			String desc = "schema 字段=" + safe(() -> fields((RuntimeSchema<?>) RuntimeSchema.getSchema(ArrayList.class)))
					+ "；反序列化 size()=" + safe(() -> d == null ? "null(对象为null)" : d.size())
					+ "；get(0)=" + safe(() -> d.get(0)) + "；toString=" + safe(() -> d);
			record(id, eq(list, d), null, b, desc);
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	@Test
	@org.junit.jupiter.api.Order(Integer.MAX_VALUE - 10)
	void p2TopLevelHashMap() {
		HashMap<String, User> map = new HashMap<>();
		map.put("u1", new User("alice", 30));
		byte[] b = null;
		String id = "P2b ProtobufSerializer 顶层 HashMap<String,User>(附加)";
		try {
			LegacyProtobufSerializer ps = new LegacyProtobufSerializer();
			b = ps.serialize(map);
			@SuppressWarnings("unchecked")
			HashMap<Object, Object> d = ps.deserialize(b, HashMap.class);
			String desc = "schema 字段=" + safe(() -> fields((RuntimeSchema<?>) RuntimeSchema.getSchema(HashMap.class)))
					+ "；反序列化 size()=" + safe(() -> d == null ? "null(对象为null)" : d.size())
					+ "；get(u1)=" + safe(() -> d.get("u1")) + "；toString=" + safe(() -> d);
			record(id, eq(map, d), null, b, desc);
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	/** 在独立的 DefaultIdStrategy 上确定性复现：对容器类调用 getSchema 之后，同一策略下的 Object/集合字段行为是否改变。 */
	@Test
	void p2cSchemaRegistrationSideEffect() {
		DefaultIdStrategy st = new DefaultIdStrategy();
		Schema<Holder> hs = RuntimeSchema.createFrom(Holder.class, st);
		Schema<Catalog> cs = RuntimeSchema.createFrom(Catalog.class, st);
		Schema<Team> ts = RuntimeSchema.createFrom(Team.class, st);
		sideEffectRound("P2c-1 独立策略 getSchema(ArrayList) 之前 Holder{Object}=ArrayList<User>", hs, cs, ts, true);
		RuntimeSchema.getSchema(ArrayList.class, st);
		RuntimeSchema.getSchema(HashMap.class, st);
		RuntimeSchema.getSchema(Integer.class, st);
		sideEffectRound("P2c-2 独立策略 getSchema(ArrayList/HashMap/Integer) 之后", hs, cs, ts, false);
	}

	private static void sideEffectRound(String prefix, Schema<Holder> hs, Schema<Catalog> cs, Schema<Team> ts,
			boolean before) {
		String tag = before ? "前" : "后";
		Holder h = new Holder();
		h.value = users();
		byte[] b = null;
		String id = prefix.substring(0, 6) + "a " + tag + ": Holder{Object}=ArrayList<User>";
		try {
			b = ser(h, hs);
			Holder d = de(b, hs);
			record(id, eq(h.value, d.value), null, b, "解码 size=" + safe(() -> ((List<?>) d.value).size())
					+ "，值=" + safe(() -> d.value));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
		HashMap<String, User> map = new HashMap<>();
		map.put("u1", new User("alice", 30));
		Holder hm = new Holder();
		hm.value = map;
		b = null;
		id = prefix.substring(0, 6) + "b " + tag + ": Holder{Object}=HashMap<String,User>";
		try {
			b = ser(hm, hs);
			Holder d = de(b, hs);
			record(id, eq(hm.value, d.value), null, b, "值=" + safe(() -> d.value));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
		Catalog c = new Catalog();
		c.byGroup = new HashMap<>();
		c.byGroup.put("g1", users());
		b = null;
		id = prefix.substring(0, 6) + "c " + tag + ": Catalog{Map<String,List<User>>}";
		try {
			b = ser(c, cs);
			Catalog d = de(b, cs);
			record(id, eq(c.byGroup, d.byGroup), null, b, "值=" + safe(() -> d.byGroup));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
		Holder hi = new Holder();
		hi.value = Integer.valueOf(42);
		b = null;
		id = prefix.substring(0, 6) + "e " + tag + ": Holder{Object}=Integer 42";
		try {
			b = ser(hi, hs);
			Holder d = de(b, hs);
			record(id, eq(hi.value, d.value), null, b, "解码类型=" + cls(d.value) + "，值=" + safe(() -> d.value));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
		Team t = new Team();
		t.name = "core";
		t.members = users();
		b = null;
		id = prefix.substring(0, 6) + "d " + tag + ": Team{List<User>}";
		try {
			b = ser(t, ts);
			Team d = de(b, ts);
			record(id, eq(t.members, d.members), null, b, "值=" + safe(() -> d.members));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	/** P2a/P2b 已经通过 ProtobufSerializer 在全局 ID_STRATEGY 上对 ArrayList/HashMap 调过 getSchema，此时重跑全局路径。 */
	@Test
	@org.junit.jupiter.api.Order(Integer.MAX_VALUE)
	void p2dGlobalStrategyAfterP2() {
		Schema<Holder> s = RuntimeSchema.getSchema(Holder.class);
		Holder h = new Holder();
		h.value = users();
		byte[] b = null;
		String id = "P2d 全局策略(P2a/P2b 之后) Holder{Object}=ArrayList<User>";
		try {
			b = ser(h, s);
			Holder d = de(b, s);
			record(id, eq(h.value, d.value), null, b, "对照 P3-1；解码 size=" + safe(() -> ((List<?>) d.value).size()));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	// ---------------------------------------------------------------- P3

	private static void holderProbe(String id, Object value, Schema<Holder> s) {
		Holder h = new Holder();
		h.value = value;
		byte[] b = null;
		try {
			b = ser(h, s);
			Holder d = de(b, s);
			String elem = "";
			if (d.value instanceof List<?> l) {
				elem = "，元素类型=" + safe(() -> cls(l.get(0)));
			} else if (d.value instanceof Map<?, ?> m) {
				elem = "，value 类型=" + safe(() -> cls(m.values().iterator().next()));
			}
			String shown = d.value instanceof int[] arr ? Arrays.toString(arr) : safe(() -> d.value);
			record(id, eq(value, d.value), null, b, "解码类型=" + cls(d.value) + elem + "，值=" + shown);
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	@Test
	void p3HolderObjectField() {
		Schema<Holder> s = RuntimeSchema.getSchema(Holder.class);
		HashMap<String, User> map = new HashMap<>();
		map.put("u1", new User("alice", 30));
		holderProbe("P3-1 Holder{Object}=ArrayList<User>", users(), s);
		holderProbe("P3-2 Holder{Object}=HashMap<String,User>", map, s);
		holderProbe("P3-3 Holder{Object}=User", new User("alice", 30), s);
		holderProbe("P3-4 Holder{Object}=int[]", new int[] {1, 2, 3}, s);
		holderProbe("P3-5 Holder{Object}=null", null, s);
	}

	// ---------------------------------------------------------------- P4

	@Test
	void p4PolymorphicInterfaceField() {
		Zoo z = new Zoo();
		Dog dog = new Dog();
		dog.name = "rex";
		z.pet = dog;
		byte[] b = null;
		String id = "P4a 接口字段 Animal pet=Dog";
		try {
			Schema<Zoo> s = RuntimeSchema.getSchema(Zoo.class);
			b = ser(z, s);
			Zoo d = de(b, s);
			boolean ok = d.pet instanceof Dog dd && "rex".equals(dd.name);
			record(id, ok ? "成功" : "类型/值丢失", null, b, "解码类型=" + cls(d.pet));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	@Test
	void p4PolymorphicAbstractField() {
		AbsZoo z = new AbsZoo();
		Cat cat = new Cat();
		cat.name = "tom";
		cat.lives = 9;
		z.pet = cat;
		byte[] b = null;
		String id = "P4b 抽象类字段 AbstractAnimal pet=Cat";
		try {
			Schema<AbsZoo> s = RuntimeSchema.getSchema(AbsZoo.class);
			b = ser(z, s);
			AbsZoo d = de(b, s);
			boolean ok = d.pet instanceof Cat c && "tom".equals(c.name) && c.lives == 9;
			record(id, ok ? "成功" : "类型/值丢失", null, b, "解码类型=" + cls(d.pet));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	@Test
	void p4PolymorphicConcreteNonFinalField() {
		ConcreteZoo z = new ConcreteZoo();
		Puppy p = new Puppy();
		p.name = "odie";
		p.age = 2;
		z.pet = p;
		byte[] b = null;
		String id = "P4c 具体非 final 父类字段 BaseAnimal pet=Puppy(附加)";
		try {
			Schema<ConcreteZoo> s = RuntimeSchema.getSchema(ConcreteZoo.class);
			b = ser(z, s);
			ConcreteZoo d = de(b, s);
			boolean ok = d.pet instanceof Puppy pp && pp.age == 2;
			record(id, ok ? "成功" : "子类型丢失", null, b,
					"解码类型=" + cls(d.pet) + "，name=" + (d.pet == null ? null : d.pet.name)
							+ "，MORPH_NON_FINAL_POJOS=" + RuntimeEnv.MORPH_NON_FINAL_POJOS);
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	// ---------------------------------------------------------------- P5

	private static <T> void recordProbe(String id, Class<T> type, T value) {
		String stage = "RuntimeSchema.getSchema";
		byte[] b = null;
		try {
			Schema<T> s = RuntimeSchema.getSchema(type);
			stage = "serialize";
			b = ser(value, s);
			stage = "newMessage";
			T m = s.newMessage();
			stage = "mergeFrom";
			ProtostuffIOUtil.mergeFrom(b, m, s);
			boolean ok = value.equals(m);
			record(id, ok ? "成功" : "往返不等", null, b,
					"解码值=" + m + "，USE_SUN_MISC_UNSAFE=" + RuntimeEnv.USE_SUN_MISC_UNSAFE);
		} catch (Throwable e) {
			record(id, "失败(阶段: " + stage + ")", e, b, "USE_SUN_MISC_UNSAFE=" + RuntimeEnv.USE_SUN_MISC_UNSAFE);
		}
	}

	@Test
	void p5Records() {
		recordProbe("P5a record Point(int x,int y)", Point.class, new Point(3, 4));
		recordProbe("P5b record Order(long id,List<User> items)", Order.class, new Order(7L, users()));
	}

	// ---------------------------------------------------------------- P6

	@Test
	void p6FieldEvolutionUntagged() {
		byte[] b = null;
		try {
			RuntimeSchema<V1> s1 = (RuntimeSchema<V1>) RuntimeSchema.getSchema(V1.class);
			RuntimeSchema<V2> s2 = (RuntimeSchema<V2>) RuntimeSchema.getSchema(V2.class);
			V1 v1 = new V1();
			v1.a = 11;
			v1.b = 22;
			b = ser(v1, s1);
			V2 d = de(b, s2);
			boolean ok = d.a == 11 && d.b == 22 && d.c == 0;
			record("P6a 无@Tag V1{a=11,b=22} -> V2{a,c,b}", ok ? "正确" : "错位", null, b,
					"a=" + d.a + " c=" + d.c + " b=" + d.b + "；V1 tags=" + fields(s1) + " V2 tags=" + fields(s2));

			V2 v2 = new V2();
			v2.a = 11;
			v2.c = 33;
			v2.b = 22;
			byte[] b2 = ser(v2, s2);
			V1 d1 = de(b2, s1);
			boolean ok2 = d1.a == 11 && d1.b == 22;
			record("P6b 无@Tag V2{a=11,c=33,b=22} -> V1{a,b}(附加反向)", ok2 ? "正确" : "错位", null, b2,
					"a=" + d1.a + " b=" + d1.b);
		} catch (Throwable e) {
			record("P6a 无@Tag V1 -> V2", "失败", e, b, "");
		}
	}

	@Test
	void p6FieldEvolutionTagged() {
		byte[] b = null;
		try {
			RuntimeSchema<V1Tag> s1 = (RuntimeSchema<V1Tag>) RuntimeSchema.getSchema(V1Tag.class);
			RuntimeSchema<V2Tag> s2 = (RuntimeSchema<V2Tag>) RuntimeSchema.getSchema(V2Tag.class);
			V1Tag v1 = new V1Tag();
			v1.a = 11;
			v1.b = 22;
			b = ser(v1, s1);
			V2Tag d = de(b, s2);
			boolean ok = d.a == 11 && d.b == 22 && d.c == 0;
			record("P6c @Tag V1{a@1=11,b@2=22} -> V2{a@1,c@3,b@2}", ok ? "正确" : "错位", null, b,
					"a=" + d.a + " c=" + d.c + " b=" + d.b + "；V1 tags=" + fields(s1) + " V2 tags=" + fields(s2));

			V2Tag v2 = new V2Tag();
			v2.a = 11;
			v2.c = 33;
			v2.b = 22;
			byte[] b2 = ser(v2, s2);
			V1Tag d1 = de(b2, s1);
			boolean ok2 = d1.a == 11 && d1.b == 22;
			record("P6d @Tag V2{a=11,c=33,b=22} -> V1(附加反向)", ok2 ? "正确(未知 tag 3 被跳过)" : "错位", null, b2,
					"a=" + d1.a + " b=" + d1.b);
		} catch (Throwable e) {
			record("P6c @Tag V1 -> V2", "失败", e, b, "");
		}
	}

	// ---------------------------------------------------------------- P7

	@Test
	void p7ExplicitIdStrategyAvailability() {
		for (String cn : new String[] {"io.protostuff.runtime.ExplicitIdStrategy",
				"io.protostuff.runtime.ExplicitIdStrategy$Registry"}) {
			try {
				Class.forName(cn);
				record("P7a 类可用性 " + cn, "存在", null, null, "");
			} catch (Throwable e) {
				record("P7a 类可用性 " + cn, "不存在", e, null,
						"该类属于 io.protostuff:protostuff-runtime-registry 构件，当前依赖未包含，按约束不引入");
			}
		}
	}

	@Test
	void p7DefaultIdStrategyWithRegistration() {
		DefaultIdStrategy strategy = new DefaultIdStrategy();
		strategy.registerPojo(User.class);
		strategy.registerPojo(Dog.class);
		Schema<Holder> hs = RuntimeSchema.createFrom(Holder.class, strategy);
		Holder h = new Holder();
		h.value = users();
		byte[] b = null;
		String id1 = "P7b 替代: DefaultIdStrategy.registerPojo 后 Holder{Object}=ArrayList<User>";
		try {
			b = ser(h, hs);
			Holder d = de(b, hs);
			record(id1, Objects.equals(h.value, d.value) ? "成功" : "往返不等", null, b,
					"对照 P3-1 字节数；解码类型=" + cls(d.value));
		} catch (Throwable e) {
			record(id1, "失败", e, b, "");
		}
		Schema<Zoo> zs = RuntimeSchema.createFrom(Zoo.class, strategy);
		Zoo z = new Zoo();
		Dog dog = new Dog();
		dog.name = "rex";
		z.pet = dog;
		b = null;
		String id2 = "P7c 替代: DefaultIdStrategy.registerPojo 后 Zoo{Animal}=Dog";
		try {
			b = ser(z, zs);
			Zoo d = de(b, zs);
			record(id2, d.pet instanceof Dog ? "成功" : "类型丢失", null, b, "对照 P4a 字节数；解码类型=" + cls(d.pet));
		} catch (Throwable e) {
			record(id2, "失败", e, b, "");
		}
	}

	@Test
	void p7UnknownClassNameOnWire() {
		// 模拟接收方没有发送方类型：把字节中的 Dog 全限定名替换成同长度、不存在的类名
		Schema<Zoo> s = RuntimeSchema.getSchema(Zoo.class);
		Zoo z = new Zoo();
		Dog dog = new Dog();
		dog.name = "rex";
		z.pet = dog;
		byte[] b = null;
		String id = "P7d 未知类名(线上 Dog 改为不存在的 Dox) 反序列化";
		try {
			byte[] orig = ser(z, s);
			String str = new String(orig, StandardCharsets.ISO_8859_1).replace(NS + "Dog", NS + "Dox");
			b = str.getBytes(StandardCharsets.ISO_8859_1);
			Zoo d = de(b, s);
			record(id, "未抛异常", null, b, "解码类型=" + cls(d.pet));
		} catch (Throwable e) {
			record(id, "失败", e, b, "DefaultIdStrategy 下接收方无此类的行为；ExplicitIdStrategy 的未注册行为无法验证(类不存在)");
		}
	}

	// ---------------------------------------------------------------- P8

	@Test
	void p8NestedGenericMapOfLists() {
		Catalog c = new Catalog();
		c.byGroup = new HashMap<>();
		c.byGroup.put("g1", users());
		c.byGroup.put("g2", new ArrayList<>(List.of(new User("carol", 50))));
		byte[] b = null;
		String id = "P8 POJO{Map<String,List<User>>}";
		try {
			Schema<Catalog> s = RuntimeSchema.getSchema(Catalog.class);
			b = ser(c, s);
			Catalog d = de(b, s);
			List<User> g1 = d.byGroup.get("g1");
			record(id, eq(c.byGroup, d.byGroup), null, b, "map 类型=" + cls(d.byGroup) + "，list 类型=" + cls(g1)
					+ "，元素类型=" + safe(() -> cls(g1.get(0))) + "，值=" + safe(() -> d.byGroup));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}

	// ---------------------------------------------------------------- P9

	@Test
	@org.junit.jupiter.api.Order(Integer.MAX_VALUE - 20)
	void p9NullAndEmpty() {
		LegacyProtobufSerializer ps = new LegacyProtobufSerializer();
		try {
			byte[] b = ps.serialize(null);
			User d0 = ps.deserialize(new byte[0], User.class);
			User dn = ps.deserialize(null, User.class);
			record("P9a serialize(null) / deserialize(byte[0] 或 null)", "无异常", null, b,
					"serialize(null) 长度=" + b.length + "；deserialize(byte[0])=" + d0 + "；deserialize(null)=" + dn);
		} catch (Throwable e) {
			record("P9a serialize(null) / deserialize(byte[0] 或 null)", "失败", e, null, "");
		}
		byte[] b = null;
		try {
			User empty = new User();
			b = ps.serialize(empty);
			User d = ps.deserialize(b, User.class);
			record("P9b 全默认值 POJO new User()", d == null ? "反序列化为 null(对象丢失)" : "成功", null, b,
					"解码=" + d + "；空对象与 null 在线上是否可区分见结果列");
		} catch (Throwable e) {
			record("P9b 全默认值 POJO new User()", "失败", e, b, "");
		}
		b = null;
		try {
			Holder empty = new Holder();
			b = ps.serialize(empty);
			Holder d = ps.deserialize(b, Holder.class);
			record("P9b2 全 null 引用字段 POJO new Holder()(附加)", d == null ? "反序列化为 null(对象丢失)" : "成功", null, b,
					"解码=" + (d == null ? "null" : "Holder{value=" + d.value + "}"));
		} catch (Throwable e) {
			record("P9b2 全 null 引用字段 POJO new Holder()(附加)", "失败", e, b, "");
		}
		blob(ps, "P9c Blob{byte[]}={1,2,3}", new byte[] {1, 2, 3});
		blob(ps, "P9d Blob{byte[]}=byte[0]", new byte[0]);
		blob(ps, "P9e Blob{byte[]}=null", null);
		b = null;
		try {
			b = ps.serialize(new byte[] {1, 2, 3});
			byte[] d = ps.deserialize(b, byte[].class);
			record("P9f 顶层 byte[]{1,2,3}(附加)", Arrays.equals(new byte[] {1, 2, 3}, d) ? "成功" : "不正确", null, b,
					"解码=" + Arrays.toString(d));
		} catch (Throwable e) {
			record("P9f 顶层 byte[]{1,2,3}(附加)", "失败", e, b, "");
		}
		b = null;
		try {
			b = ps.serialize(Integer.valueOf(42));
			Integer d = ps.deserialize(b, Integer.class);
			record("P9g 顶层 Integer 42(附加)", Integer.valueOf(42).equals(d) ? "成功" : "不正确", null, b, "解码=" + d);
		} catch (Throwable e) {
			record("P9g 顶层 Integer 42(附加)", "失败", e, b, "");
		}
		b = null;
		try {
			b = ps.serialize(Integer.valueOf(0));
			Integer d = ps.deserialize(b, Integer.class);
			record("P9h 顶层 Integer 0(附加)", Integer.valueOf(0).equals(d) ? "成功" : "不正确", null, b, "解码=" + d);
		} catch (Throwable e) {
			record("P9h 顶层 Integer 0(附加)", "失败", e, b, "");
		}
	}

	private static void blob(LegacyProtobufSerializer ps, String id, byte[] data) {
		Blob blob = new Blob();
		blob.name = "n";
		blob.data = data;
		byte[] b = null;
		try {
			b = ps.serialize(blob);
			Blob d = ps.deserialize(b, Blob.class);
			boolean ok = Arrays.equals(data, d.data);
			record(id, ok ? "成功" : "不一致", null, b, "解码 data=" + (d.data == null ? "null" : Arrays.toString(d.data)));
		} catch (Throwable e) {
			record(id, "失败", e, b, "");
		}
	}
}
