package io.github.sisyfyzhang.steadyrpc.it;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.sisyfyzhang.steadyrpc.core.RpcClient;
import io.github.sisyfyzhang.steadyrpc.core.RpcServer;
import io.github.sisyfyzhang.steadyrpc.core.config.ExecutorConfig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-4：多参数（基本 / 包装混用）、重载、{@code List<Item>}、{@code Map<String, List<Item>>}、{@code Set<Long>}、
 * {@code Item[]}、{@code byte[]}、{@code CompletableFuture<List<Item>>}、null 参数与 null 返回、空集合返回、
 * 全 null 字段对象返回非 null、java.time 七个类型（参数 / 返回值、集合元素、Map 值、POJO 字段；Instant 纳秒精度、
 * ZonedDateTime 的区域与夏令时偏移）——每一项对 Protostuff 与 fastjson2 各跑一遍。
 *
 * <p>一个服务端（两种序列化器都在 classpath 上）+ 每种序列化器一个客户端：客户端首选哪种，协商就选哪种，
 * 服务端按请求头里的序列化编码解码并用同一种回包。ZooKeeper 用进程内 TestingServer，整个类共享。
 */
class SerializationMatrixTest {

	public interface TypesService {
		String mix(int a, Integer b, long c, Long d, boolean e, String f);

		String overloaded(int x);

		String overloaded(Integer x);

		String overloaded(String s);

		String overloaded(String s, int times);

		List<Item> items(int n);

		Map<String, List<Item>> group(List<Item> items);

		Set<Long> ids(Set<Long> in);

		Item[] reverse(Item[] in);

		byte[] bytes(byte[] in);

		CompletableFuture<List<Item>> itemsAsync(int n);

		Item echo(Item item);

		String describeNull(String a, Item b, List<Item> c);

		List<Item> emptyItems();

		Map<String, List<Item>> emptyGroup();

		Item blankItem();

		Instant instant(Instant in);

		LocalDate localDate(LocalDate in);

		LocalTime localTime(LocalTime in);

		LocalDateTime localDateTime(LocalDateTime in);

		Duration duration(Duration in);

		OffsetDateTime offsetDateTime(OffsetDateTime in);

		ZonedDateTime zonedDateTime(ZonedDateTime in);

		List<Instant> instants(List<Instant> in);

		Map<String, LocalDate> dates(Map<String, LocalDate> in);

		TimeSlot slot(TimeSlot in);
	}

	/** java.time 作为 POJO 字段（Protostuff 走注册进策略的委托，fastjson2 走它自己的 ISO 编解码）。 */
	public static class TimeSlot {
		private Instant start;
		private Duration length;
		private LocalDate day;
		private ZonedDateTime local;

		public TimeSlot() {
		}

		TimeSlot(Instant start, Duration length, LocalDate day, ZonedDateTime local) {
			this.start = start;
			this.length = length;
			this.day = day;
			this.local = local;
		}

		public Instant getStart() {
			return start;
		}

		public void setStart(Instant start) {
			this.start = start;
		}

		public Duration getLength() {
			return length;
		}

		public void setLength(Duration length) {
			this.length = length;
		}

		public LocalDate getDay() {
			return day;
		}

		public void setDay(LocalDate day) {
			this.day = day;
		}

		public ZonedDateTime getLocal() {
			return local;
		}

		public void setLocal(ZonedDateTime local) {
			this.local = local;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof TimeSlot t && java.util.Objects.equals(start, t.start) && java.util.Objects.equals(length, t.length)
					&& java.util.Objects.equals(day, t.day) && java.util.Objects.equals(local, t.local);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(start, length, day, local);
		}

		@Override
		public String toString() {
			return "TimeSlot{" + start + "," + length + "," + day + "," + local + "}";
		}
	}

	static final class TypesServiceImpl implements TypesService {
		@Override
		public String mix(int a, Integer b, long c, Long d, boolean e, String f) {
			return a + "|" + b + "|" + c + "|" + d + "|" + e + "|" + f;
		}

		@Override
		public String overloaded(int x) {
			return "int:" + x;
		}

		@Override
		public String overloaded(Integer x) {
			return "Integer:" + x;
		}

		@Override
		public String overloaded(String s) {
			return "String:" + s;
		}

		@Override
		public String overloaded(String s, int times) {
			return "String*int:" + s.repeat(times);
		}

		@Override
		public List<Item> items(int n) {
			return java.util.stream.IntStream.rangeClosed(1, n).mapToObj(i -> new Item(i, "item-" + i, List.of("t" + i))).toList();
		}

		@Override
		public Map<String, List<Item>> group(List<Item> items) {
			Map<String, List<Item>> result = new LinkedHashMap<>();
			for (Item item : items) {
				// 参数元素必须已还原成 Item：否则这里 ClassCastException
				result.computeIfAbsent(item.getId() % 2 == 0 ? "even" : "odd", k -> new java.util.ArrayList<>()).add(item);
			}
			return result;
		}

		@Override
		public Set<Long> ids(Set<Long> in) {
			Set<Long> out = new java.util.HashSet<>();
			for (Long id : in) {
				out.add(id * 10);
			}
			return out;
		}

		@Override
		public Item[] reverse(Item[] in) {
			Item[] out = new Item[in.length];
			for (int i = 0; i < in.length; i++) {
				out[i] = in[in.length - 1 - i];
			}
			return out;
		}

		@Override
		public byte[] bytes(byte[] in) {
			byte[] out = in.clone();
			for (int i = 0; i < out.length; i++) {
				out[i] = (byte) (out[i] + 1);
			}
			return out;
		}

		@Override
		public CompletableFuture<List<Item>> itemsAsync(int n) {
			return CompletableFuture.supplyAsync(() -> items(n));
		}

		@Override
		public Item echo(Item item) {
			return item;
		}

		@Override
		public String describeNull(String a, Item b, List<Item> c) {
			return (a == null) + "," + (b == null) + "," + (c == null);
		}

		@Override
		public List<Item> emptyItems() {
			return List.of();
		}

		@Override
		public Map<String, List<Item>> emptyGroup() {
			return new HashMap<>();
		}

		@Override
		public Item blankItem() {
			return new Item();
		}

		@Override
		public Instant instant(Instant in) {
			return in.plusNanos(1);
		}

		@Override
		public LocalDate localDate(LocalDate in) {
			return in.plusDays(1);
		}

		@Override
		public LocalTime localTime(LocalTime in) {
			return in.plusNanos(1);
		}

		@Override
		public LocalDateTime localDateTime(LocalDateTime in) {
			return in.plusNanos(1);
		}

		@Override
		public Duration duration(Duration in) {
			return in.plusNanos(1);
		}

		@Override
		public OffsetDateTime offsetDateTime(OffsetDateTime in) {
			return in.plusNanos(1);
		}

		@Override
		public ZonedDateTime zonedDateTime(ZonedDateTime in) {
			return in;
		}

		@Override
		public List<Instant> instants(List<Instant> in) {
			return in;
		}

		@Override
		public Map<String, LocalDate> dates(Map<String, LocalDate> in) {
			return in;
		}

		@Override
		public TimeSlot slot(TimeSlot in) {
			return in;
		}
	}

	private static ZkFixture zk;
	private static RpcServer server;
	private static final Map<String, RpcClient> clients = new ConcurrentHashMap<>();

	@BeforeAll
	static void startServer() throws Exception {
		// 证据：用 -DargLine=-Dio.netty.leakDetection.level=paranoid 跑时，这里应打印 PARANOID
		System.out.println("[leak-detection] io.netty ResourceLeakDetector level = " + io.netty.util.ResourceLeakDetector.getLevel());
		zk = new ZkFixture();
		server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).executor(ExecutorConfig.fixed(4)).build();
		server.export(TypesService.class, new TypesServiceImpl());
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
	}

	@AfterAll
	static void stop() throws Exception {
		clients.values().forEach(RpcClient::close);
		clients.clear();
		server.close();
		zk.close();
	}

	static Stream<Arguments> serializers() {
		return Stream.of(Arguments.of("protobuf"), Arguments.of("fastjson2"));
	}

	private static TypesService service(String serializer) {
		RpcClient client = clients.computeIfAbsent(serializer, name -> RpcClient.builder()
				.registry(zk.registry())
				.serializer(name)
				.defaultTimeout(Duration.ofSeconds(10))
				.build());
		return client.reference(TypesService.class);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void multipleArgumentsMixingPrimitivesAndWrappers(String serializer) {
		assertEquals("1|2|3|4|true|six", service(serializer).mix(1, 2, 3L, 4L, true, "six"));
		assertEquals("-1|null|0|null|false|null", service(serializer).mix(-1, null, 0L, null, false, null));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void overloadsAreRoutedByParameterSignature(String serializer) {
		TypesService service = service(serializer);
		assertEquals("int:7", service.overloaded(7));
		assertEquals("Integer:7", service.overloaded(Integer.valueOf(7)));
		assertEquals("String:7", service.overloaded("7"));
		assertEquals("String*int:ababab", service.overloaded("ab", 3));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void genericListReturn(String serializer) {
		List<Item> items = service(serializer).items(3);
		assertEquals(3, items.size());
		assertInstanceOf(Item.class, items.get(0), "elements are restored as Item, not Map/JSONObject");
		assertEquals(new Item(2, "item-2", List.of("t2")), items.get(1));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void genericListParameterAndNestedMapReturn(String serializer) {
		List<Item> input = List.of(new Item(1, "a", List.of()), new Item(2, "b", List.of("x", "y")), new Item(3, "c", null));
		Map<String, List<Item>> grouped = service(serializer).group(input);
		assertEquals(Map.of("odd", List.of(input.get(0), input.get(2)), "even", List.of(input.get(1))), grouped);
		assertInstanceOf(Item.class, grouped.get("even").get(0));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void setOfLongs(String serializer) {
		Set<Long> result = service(serializer).ids(Set.of(1L, 2L, 30L));
		assertEquals(Set.of(10L, 20L, 300L), result);
		result.forEach(id -> assertInstanceOf(Long.class, id));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void objectArray(String serializer) {
		Item[] in = { new Item(1, "a", null), new Item(2, "b", List.of("t")) };
		assertArrayEquals(new Item[] { in[1], in[0] }, service(serializer).reverse(in));
		assertArrayEquals(new Item[0], service(serializer).reverse(new Item[0]));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void byteArray(String serializer) {
		assertArrayEquals(new byte[] { 2, 3, 4, 0 }, service(serializer).bytes(new byte[] { 1, 2, 3, -1 }));
		assertArrayEquals(new byte[0], service(serializer).bytes(new byte[0]));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void completableFutureOfGenericList(String serializer) throws Exception {
		CompletableFuture<List<Item>> future = service(serializer).itemsAsync(2);
		List<Item> items = future.get(10, TimeUnit.SECONDS);
		assertEquals(2, items.size());
		assertInstanceOf(Item.class, items.get(1));
		assertEquals("item-2", items.get(1).getName());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void nullArgumentsAndNullReturn(String serializer) {
		TypesService service = service(serializer);
		assertNull(service.echo(null), "null argument in, null return out");
		assertEquals("true,true,true", service.describeNull(null, null, null));
		assertEquals("false,true,false", service.describeNull("a", null, List.of()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void emptyCollectionsComeBackEmptyNotNull(String serializer) {
		List<Item> items = service(serializer).emptyItems();
		assertNotNull(items);
		assertTrue(items.isEmpty());
		Map<String, List<Item>> group = service(serializer).emptyGroup();
		assertNotNull(group);
		assertTrue(group.isEmpty());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void objectWithAllNullFieldsComesBackNonNull(String serializer) {
		Item blank = service(serializer).blankItem();
		assertNotNull(blank, "an all-null object must not turn into null (Protostuff encodes it as 0 bytes)");
		assertEquals(new Item(), blank);
		assertEquals(new Item(), service(serializer).echo(new Item()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void javaTimeScalarsRoundTripExactly(String serializer) {
		TypesService service = service(serializer);
		Instant instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
		Instant back = service.instant(instant);
		assertEquals(instant.plusNanos(1), back);
		assertEquals(123_456_790, back.getNano(), "Instant keeps nanosecond precision");
		assertEquals(Instant.ofEpochSecond(-1, 999_999_999).plusNanos(1), service.instant(Instant.ofEpochSecond(-1, 999_999_999)),
				"pre-epoch instants too");
		assertEquals(LocalDate.of(2024, 3, 1), service.localDate(LocalDate.of(2024, 2, 29)));
		assertEquals(LocalTime.of(0, 0, 0, 1), service.localTime(LocalTime.MIDNIGHT));
		assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 2), service.localDateTime(LocalDateTime.of(2024, 2, 29, 23, 59, 59, 1)));
		assertEquals(Duration.ofHours(26).plusNanos(6), service.duration(Duration.ofHours(26).plusNanos(5)));
		OffsetDateTime offset = OffsetDateTime.of(2024, 2, 29, 23, 59, 59, 0, ZoneOffset.ofHoursMinutes(5, 30));
		assertEquals(offset.plusNanos(1), service.offsetDateTime(offset));
		// 夏令时回拨的重叠小时取较晚的偏移：区域与偏移都必须原样回来
		ZonedDateTime zoned = ZonedDateTime.of(LocalDateTime.of(2024, 11, 3, 1, 30), ZoneId.of("America/New_York")).withLaterOffsetAtOverlap();
		ZonedDateTime zonedBack = service.zonedDateTime(zoned);
		assertEquals(zoned, zonedBack);
		assertEquals(ZoneOffset.ofHours(-5), zonedBack.getOffset());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void javaTimeInsideCollectionsAndPojos(String serializer) {
		TypesService service = service(serializer);
		List<Instant> instants = List.of(Instant.EPOCH, Instant.ofEpochSecond(1_700_000_000L, 1));
		List<Instant> instantsBack = service.instants(instants);
		assertEquals(instants, instantsBack);
		assertInstanceOf(Instant.class, instantsBack.get(1));
		Map<String, LocalDate> dates = new LinkedHashMap<>();
		dates.put("leap", LocalDate.of(2024, 2, 29));
		dates.put("epoch", LocalDate.EPOCH);
		assertEquals(dates, service.dates(dates));
		TimeSlot slot = new TimeSlot(Instant.ofEpochSecond(1_700_000_000L, 999_999_999), Duration.ofMillis(1500),
				LocalDate.of(2025, 1, 1), ZonedDateTime.of(LocalDateTime.of(2025, 1, 1, 8, 0), ZoneId.of("Asia/Shanghai")));
		assertEquals(slot, service.slot(slot));
		assertEquals(new TimeSlot(), service.slot(new TimeSlot()));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void nullElementsInsideCollectionsSurvive(String serializer) {
		Item[] in = { new Item(1, "a", null), null };
		assertEquals(Arrays.asList(null, in[0]), Arrays.asList(service(serializer).reverse(in)));
	}
}
