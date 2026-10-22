package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 执行器对照实验（任务 20）的能力：业务等待方式 {@code server --work-mode park|monitor|sync-park}（卡片 {@code target.server_work_mode}）、
 * 它在虚拟线程上的钉住行为、卡片字段的校验，以及仓库里 {@code exec-*} 卡"成对只差一处"。
 */
class ExecutorBlockingTest {

	private static final long WORK_NANOS = Duration.ofMillis(20).toNanos();

	private static BenchServiceImpl service(long workNanos, BenchServiceImpl.WorkMode mode) {
		return new BenchServiceImpl(Map.of(), Map::of, Map::of, workNanos, mode);
	}

	@Test
	void everyWorkModeWaitsAtLeastTheWorkOnPlatformAndVirtualThreads() throws Exception {
		for (BenchServiceImpl.WorkMode mode : BenchServiceImpl.WorkMode.values()) {
			BenchServiceImpl impl = service(WORK_NANOS, mode);
			long start = System.nanoTime();
			byte[] payload = { 1, 2, 3 };
			assertEquals(payload, impl.echo(payload), mode + ": echo returns its argument");
			assertTrue(System.nanoTime() - start >= WORK_NANOS, mode + " on a platform thread");

			long[] elapsed = new long[1];
			Thread vt = Thread.ofVirtual().start(() -> {
				long s = System.nanoTime();
				impl.work();
				elapsed[0] = System.nanoTime() - s;
			});
			vt.join();
			assertTrue(elapsed[0] >= WORK_NANOS, mode + " on a virtual thread: " + elapsed[0]);
		}
		// work = 0：任何方式都立即返回
		long start = System.nanoTime();
		service(0, BenchServiceImpl.WorkMode.MONITOR).work();
		assertTrue(System.nanoTime() - start < Duration.ofMillis(5).toNanos());
	}

	/**
	 * JDK 21：虚拟线程在 synchronized 内 park 会钉住载体线程且调度器不补偿，同时在等的数目被载体数（= 可用核数）封顶。
	 * 2 × 载体数个各等 50ms 的任务至少要排两批（≥ 100ms）——这是下限，CI 再慢也只会更长。
	 * （JDK 24 起 JEP 491 让 synchronized 内也能卸载，这条断言在那里不成立，故只在 JDK 21 ~ 23 上检查。）
	 */
	@Test
	void syncParkPinsTheCarrierThreadsOnJdk21() throws Exception {
		int feature = Runtime.version().feature();
		if (feature >= 24) {
			return;
		}
		int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors());
		long work = Duration.ofMillis(50).toNanos();
		BenchServiceImpl impl = service(work, BenchServiceImpl.WorkMode.SYNC_PARK);
		List<Thread> threads = new ArrayList<>();
		long start = System.nanoTime();
		for (int i = 0; i < 2 * carriers; i++) {
			threads.add(Thread.ofVirtual().start(impl::work));
		}
		for (Thread t : threads) {
			t.join();
		}
		long elapsed = System.nanoTime() - start;
		assertTrue(elapsed >= 2 * work - Duration.ofMillis(2).toNanos(),
				"2 x " + carriers + " pinned waits of 50ms need at least two batches, took " + elapsed / 1_000_000 + "ms");
	}

	@Test
	void workModeCardValues() {
		assertEquals(Set.of("park", "monitor", "sync-park"), BenchServiceImpl.WorkMode.cardValues());
		assertEquals(BenchServiceImpl.WorkMode.SYNC_PARK, BenchServiceImpl.WorkMode.of(" sync-park "));
		assertNull(BenchServiceImpl.WorkMode.of("sleep"));
		assertNull(BenchServiceImpl.WorkMode.of(null));
	}

	@Test
	void serverWorkModeParsesAndIsValidated() {
		Profile p = ProfileLoader.parse(FaultCardSchemaTest.card("""
				server_executor: virtual
				server_work: 10ms
				server_work_mode: monitor""", "  rate: 100"), null, "t.yaml");
		assertEquals("monitor", p.target().rpcOptions().serverWorkMode());
		assertEquals("monitor", p.target().rpcOptions().serverWorkModeOrDefault());
		Map<?, ?> target = (Map<?, ?>) p.toJson().get("target");
		assertEquals("monitor", target.get("server_work_mode"));
		assertEquals(10.0, target.get("server_work_ms"));

		// 不写 = park（原口径），JSON 里为 null
		Profile park = ProfileLoader.parse(FaultCardSchemaTest.card("server_work: 2ms", "  rate: 100"), null, "t.yaml");
		assertNull(park.target().rpcOptions().serverWorkMode());
		assertEquals("park", park.target().rpcOptions().serverWorkModeOrDefault());
		assertNull(((Map<?, ?>) park.toJson().get("target")).get("server_work_mode"));

		List<String> unknown = problems(FaultCardSchemaTest.card("server_work: 2ms\nserver_work_mode: sleep", "  rate: 100"));
		assertTrue(unknown.stream().anyMatch(s -> s.contains("target.server_work_mode") && s.contains("is not one of")), unknown.toString());
		List<String> alone = problems(FaultCardSchemaTest.card("server_work_mode: monitor", "  rate: 100"));
		assertTrue(alone.stream().anyMatch(s -> s.contains("target.server_work_mode: only applies together with target.server_work")),
				alone.toString());
	}

	@Test
	void serverWorkModeIsRejectedOnNonRpcCards() {
		String http = """
				profile: http-card
				question: http
				target:
				  transport: http
				  service: BenchService#echo
				  payload: { kind: bytes, size: 1024 }
				  http: { url: "http://127.0.0.1:19180/echo", connections: 8 }
				  server_work_mode: monitor
				load:
				  model: closed
				  concurrency: 8
				  duration: 60s
				  warmup: 20s
				  steady_window: [20s, 60s]
				  rounds: 1
				  timeout: 1s
				isolation:
				  client_process: separate_jvm
				  server_process: separate_jvm
				  same_host: true
				  registry: none
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p99]
				""";
		List<String> problems = problems(http);
		assertTrue(problems.stream().anyMatch(s -> s.contains("target.server_work_mode: only applies to transport = rpc")), problems.toString());
	}

	@Test
	void serverCommandRejectsABadWorkMode() {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		PrintStream o = new PrintStream(sink, true, StandardCharsets.UTF_8);
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "server", "--port", "0", "--no-registry", "--work-mode", "monitor" }, o, o),
				"--work-mode without --work");
		assertTrue(sink.toString(StandardCharsets.UTF_8).contains("--work-mode only applies together with --work"), sink.toString());
		sink.reset();
		assertEquals(Bench.ERROR,
				Bench.execute(new String[] { "server", "--port", "0", "--no-registry", "--work", "1ms", "--work-mode", "sleep" }, o, o));
		assertTrue(sink.toString(StandardCharsets.UTF_8).contains("--work-mode must be one of park, monitor, sync-park"), sink.toString());
	}

	@Test
	void serverEnvironmentRecordsTheWorkMode() throws Exception {
		try (BenchServer s = BenchServer.start(0, "127.0.0.1", null, "protobuf", null, 0, Bench.executor("virtual"), true, true, null, null,
				Duration.ofMillis(1).toNanos(), null, BenchServiceImpl.WorkMode.SYNC_PARK)) {
			assertEquals("sync-park", s.environment().get("server_work_mode"));
			assertEquals("virtual", s.environment().get("server_executor_kind"));
		}
		try (BenchServer s = BenchServer.start(0, "127.0.0.1", null, "protobuf")) {
			assertEquals("park", s.environment().get("server_work_mode"), "default");
		}
	}

	private static final Path PROFILES = Path.of("..", "bench", "profiles");

	/** 卡片解析后的 JSON，去掉 id 与问题句（它们本来就不同）。 */
	private static Map<String, Object> comparable(String id) throws Exception {
		Map<String, Object> json = new LinkedHashMap<>(ProfileLoader.load(PROFILES.resolve(id + ".yaml")).toJson());
		json.remove("profile");
		json.remove("question");
		Map<String, Object> target = new LinkedHashMap<>((Map<String, Object>) json.get("target"));
		json.put("target", target);
		return json;
	}

	@Test
	@SuppressWarnings("unchecked")
	void repositoryExecutorCardsDifferInExactlyOnePlace() throws Exception {
		for (String load : List.of("work2ms-closed64", "work10ms-closed256", "work2ms-open3k", "work10ms-closed1500")) {
			Map<String, Object> fixed = comparable("exec-fixed-" + load + "-v1");
			Map<String, Object> virtual = comparable("exec-virtual-" + load + "-v1");
			assertEquals("fixed", ((Map<String, Object>) fixed.get("target")).remove("server_executor"));
			assertEquals("virtual", ((Map<String, Object>) virtual.get("target")).remove("server_executor"));
			assertEquals(fixed, virtual, load + ": the pair differs only in target.server_executor");
			Profile card = ProfileLoader.load(PROFILES.resolve("exec-fixed-" + load + "-v1.yaml"));
			assertEquals(Duration.ofSeconds(1), card.load().timeout());
			assertEquals(3, card.load().rounds());
			assertTrue(card.assertions().isEmpty(), "errors are measured, not asserted (SERVER_OVERLOADED is a result here)");
		}
		Map<String, Object> park = comparable("exec-virtual-work10ms-closed256-v1");
		assertNull(((Map<String, Object>) park.get("target")).remove("server_work_mode"));
		for (String mode : List.of("monitor", "syncpark")) {
			Map<String, Object> pinned = comparable("exec-virtual-work10ms-closed256-" + mode + "-v1");
			Object declared = ((Map<String, Object>) pinned.get("target")).remove("server_work_mode");
			assertEquals("monitor".equals(mode) ? "monitor" : "sync-park", declared);
			assertEquals(park, pinned, mode + ": differs from exec-virtual-work10ms-closed256-v1 only in target.server_work_mode");
		}
	}

	private static List<String> problems(String yaml) {
		return assertThrows(ProfileException.class, () -> ProfileLoader.parse(yaml, null, "test.yaml")).problems();
	}
}
