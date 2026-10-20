package com.feynman.rpc.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileLoaderTest {

	/** 任务简报里的示例卡，原样（含 closed 卡上多写的 rate 与行尾注释）：必须通过校验。 */
	static final String BRIEF_EXAMPLE = """
			profile: loopback-throughput-v1      # 必填，须与文件名（去扩展名）一致，否则拒绝
			question: 同机 loopback、1KB、单提供者的吞吐与延迟量级
			target:
			  transport: rpc                     # 本任务只实现 rpc；http 留接口（任务 7）
			  service: BenchService#echo         # echo | echoOrder
			  payload: { kind: bytes, size: 1024 }
			  serializer: protobuf
			  providers: 1
			load:
			  model: closed                      # closed | open
			  concurrency: 64                    # closed 必填
			  rate: 10000                        # open 必填，req/s
			  duration: 60s
			  warmup: 20s
			  steady_window: [20s, 55s]          # 相对本轮开始；须落在 warmup 之后、duration 之内
			  rounds: 3
			  timeout: 1s
			isolation:
			  client_process: separate_jvm       # 只做记录与校验（run 子命令本身就是独立 JVM）
			  server_process: separate_jvm
			  same_host: true
			  registry: docker-compose/zookeeper # 自由文本
			measure:
			  histogram: { significant_digits: 3, max: 10s }
			  report: [throughput, p50, p95, p99, p999, max, errors_by_status]
			assert:                              # 可选
			  errors_max: 0
			  p99_max: 20ms
			""";

	/** transport: http 的卡：没有 serializer / providers，目标是 target.http.url。 */
	static final String HTTP_CARD = """
			profile: http-card
			question: HTTP 目标的校验
			target:
			  transport: http
			  service: BenchService#echo
			  payload: { kind: bytes, size: 1024 }
			  http: { url: "http://127.0.0.1:19180/echo", connections: 32 }
			load:
			  model: open
			  rate: 5000
			  duration: 60s
			  warmup: 20s
			  steady_window: [20s, 60s]
			  rounds: 3
			  timeout: 1s
			isolation:
			  client_process: separate_jvm
			  server_process: separate_jvm
			  same_host: true
			  registry: none
			measure:
			  histogram: { significant_digits: 3, max: 10s }
			  report: [throughput, p50, p99]
			""";

	private static List<String> problems(String yaml, String expectedId) {
		ProfileException e = assertThrows(ProfileException.class, () -> ProfileLoader.parse(yaml, expectedId, "test.yaml"));
		return e.problems();
	}

	private static void assertProblem(List<String> problems, String pathPrefix, String fragment) {
		assertTrue(problems.stream().anyMatch(p -> p.startsWith(pathPrefix) && p.contains(fragment)),
				"expected a problem at " + pathPrefix + " mentioning \"" + fragment + "\", got " + problems);
	}

	@Test
	void theBriefExampleParsesToTheExpectedStructure() {
		Profile p = ProfileLoader.parse(BRIEF_EXAMPLE, "loopback-throughput-v1", "brief");
		assertEquals("loopback-throughput-v1", p.id());
		assertEquals("echo", p.target().method());
		assertEquals(1024, p.target().payloadSize());
		assertEquals("closed", p.load().model());
		assertEquals(64, p.load().concurrency());
		assertEquals(10000, p.load().rate(), "the other model's field is kept (recorded) but not used");
		assertEquals(Duration.ofSeconds(20), p.load().windowStart());
		assertEquals(Duration.ofSeconds(55), p.load().windowEnd());
		assertEquals(3, p.measure().significantDigits());
		assertEquals(Duration.ofSeconds(10), p.measure().histogramMax());
		assertEquals(2, p.assertions().size());
		assertEquals("p99", p.assertions().get(1).metric());
		assertEquals(20.0, p.assertions().get(1).threshold());
		assertEquals(BRIEF_EXAMPLE, p.rawYaml(), "the raw YAML is kept verbatim for the JSON");
	}

	@Test
	void idMustMatchTheFileName(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("some-other-name.yaml");
		Files.writeString(file, BRIEF_EXAMPLE);
		ProfileException e = assertThrows(ProfileException.class, () -> ProfileLoader.load(file));
		assertProblem(e.problems(), "profile:", "must equal the file name");

		Path matching = dir.resolve("loopback-throughput-v1.yaml");
		Files.writeString(matching, BRIEF_EXAMPLE);
		assertEquals("loopback-throughput-v1", ProfileLoader.load(matching).id());
	}

	@Test
	void windowMustStartAfterWarmupAndEndWithinDuration() {
		assertProblem(problems(BRIEF_EXAMPLE.replace("[20s, 55s]", "[10s, 55s]"), null), "load.steady_window[0]", "inside the warmup");
		assertProblem(problems(BRIEF_EXAMPLE.replace("[20s, 55s]", "[20s, 61s]"), null), "load.steady_window[1]", "after load.duration");
		assertProblem(problems(BRIEF_EXAMPLE.replace("[20s, 55s]", "[30s, 25s]"), null), "load.steady_window", "must be before end");
		assertProblem(problems(BRIEF_EXAMPLE.replace("[20s, 55s]", "[20s]"), null), "load.steady_window", "two durations");
		assertProblem(problems(BRIEF_EXAMPLE.replace("warmup: 20s", "warmup: 60s"), null), "load.warmup", "shorter than");
	}

	@Test
	void openModelRequiresRateAndClosedRequiresConcurrency() {
		String open = BRIEF_EXAMPLE.replace("model: closed", "model: open").replace("  rate: 10000                        # open 必填，req/s\n", "");
		assertProblem(problems(open, null), "load.rate", "required when load.model = open");
		String closed = BRIEF_EXAMPLE.replace("  concurrency: 64                    # closed 必填\n", "");
		assertProblem(problems(closed, null), "load.concurrency", "required when load.model = closed");
		// open 卡不写 concurrency 是合法的
		String openOk = BRIEF_EXAMPLE.replace("model: closed", "model: open").replace("  concurrency: 64                    # closed 必填\n", "");
		Profile p = ProfileLoader.parse(openOk, null, "x");
		assertNull(p.load().concurrency());
		assertEquals(20_000, p.load().inflightCap(), "rate x timeout x 2");
	}

	@Test
	void durationsNeedUnitsAndTypesAreChecked() {
		assertProblem(problems(BRIEF_EXAMPLE.replace("duration: 60s", "duration: 60"), null), "load.duration", "unit");
		assertProblem(problems(BRIEF_EXAMPLE.replace("timeout: 1s", "timeout: 0ms"), null), "load.timeout", "greater than zero");
		assertProblem(problems(BRIEF_EXAMPLE.replace("rounds: 3", "rounds: three"), null), "load.rounds", "integer");
		assertProblem(problems(BRIEF_EXAMPLE.replace("same_host: true", "same_host: maybe"), null), "isolation.same_host", "true or false");
		assertProblem(problems(BRIEF_EXAMPLE.replace("max: 10s", "max: 500ms"), null), "measure.histogram.max", "must exceed load.timeout");
	}

	@Test
	void enumsUnknownFieldsAndCrossFieldRules() {
		List<String> problems = problems(BRIEF_EXAMPLE
				.replace("model: closed", "model: burst")
				.replace("serializer: protobuf", "serializer: kryo")
				.replace("  rounds: 3\n", "  rounds: 3\n  warm_up: 5s\n")
				.replace("report: [throughput,", "report: [tps, throughput,"), null);
		assertProblem(problems, "load.model", "not one of");
		assertProblem(problems, "target.serializer", "not one of");
		assertProblem(problems, "load.warm_up", "unknown field");
		assertProblem(problems, "measure.report[0]", "not one of");
		assertTrue(problems.size() >= 4, "all problems are reported at once: " + problems);

		assertProblem(problems(BRIEF_EXAMPLE.replace("kind: bytes, size: 1024", "kind: order"), null), "target.payload.kind",
				"does not match");
		assertProblem(problems(BRIEF_EXAMPLE.replace("service: BenchService#echo ", "service: BenchService#ping "), null),
				"target.service", "not one of");
		assertProblem(problems(BRIEF_EXAMPLE.replace("  errors_max: 0\n", "  errors_max: 0\n  p42_max: 1s\n"), null),
				"assert.p42_max", "unknown field");
	}

	@Test
	void missingSectionsAndNonMappingRootAreReported() {
		assertProblem(problems("profile: x\nquestion: q\n", null), "target", "required");
		assertProblem(problems("- just\n- a list\n", null), "(root)", "mapping");
		assertProblem(problems("profile: [unclosed\n", null), "(yaml)", "");
		assertProblem(problems(BRIEF_EXAMPLE.replace("profile: loopback-throughput-v1", "profile: Bad Id"), null), "profile", "must match");
	}

	@Test
	void unsafeYamlTagsAreRejected() {
		String evil = BRIEF_EXAMPLE.replace("question: 同机", "question: !!javax.script.ScriptEngineManager [] #");
		ProfileException e = assertThrows(ProfileException.class, () -> ProfileLoader.parse(evil, null, "evil"));
		assertFalse(e.problems().isEmpty());
	}

	@Test
	void httpCardParsesWithUrlAndConnections() {
		Profile p = ProfileLoader.parse(HTTP_CARD, "http-card", "http");
		assertTrue(p.target().http());
		assertEquals("http://127.0.0.1:19180/echo", p.target().httpUrl());
		assertEquals(32, p.target().httpConnections());
		assertEquals(1, p.target().providers(), "one URL");
		assertNull(p.target().serializer(), "no serializer on the HTTP path");
		Object http = ((java.util.Map<?, ?>) p.toJson().get("target")).get("http");
		assertEquals(java.util.Map.of("url", "http://127.0.0.1:19180/echo", "connections", 32), http);

		Profile defaults = ProfileLoader.parse(HTTP_CARD.replace(", connections: 32", ""), null, "http");
		assertEquals(Profile.DEFAULT_HTTP_CONNECTIONS, defaults.target().httpConnections(), "connections defaults to 64");
	}

	@Test
	void httpCardNeedsAnHttpSectionWithAUrl() {
		assertProblem(problems(HTTP_CARD.replace("  http: { url: \"http://127.0.0.1:19180/echo\", connections: 32 }\n", ""), null),
				"target.http", "required when transport = http");
		assertProblem(problems(HTTP_CARD.replace("url: \"http://127.0.0.1:19180/echo\", ", ""), null), "target.http.url", "required");
		assertProblem(problems(HTTP_CARD.replace("http://127.0.0.1:19180/echo", "https://127.0.0.1:19180/echo"), null),
				"target.http.url", "must start with http://");
		assertProblem(problems(HTTP_CARD.replace("http://127.0.0.1:19180/echo", "http://127.0.0.1:19180"), null), "target.http.url",
				"has no path");
		assertProblem(problems(HTTP_CARD.replace("connections: 32", "connections: 0"), null), "target.http.connections", "out of range");
		assertProblem(problems(HTTP_CARD.replace("connections: 32", "connections: 32, pipelining: 2"), null), "target.http.pipelining",
				"unknown field");
	}

	@Test
	void rpcCardsMayNotCarryAnHttpSection() {
		String rpcWithHttp = BRIEF_EXAMPLE.replace("  providers: 1\n", "  providers: 1\n  http: { url: \"http://127.0.0.1:1/echo\" }\n");
		assertProblem(problems(rpcWithHttp, null), "target.http", "only applies to transport = http");
	}

	@Test
	void httpCardsOnlyTakeBytesAndRejectRpcOnlyFields() {
		String order = HTTP_CARD.replace("service: BenchService#echo", "service: BenchService#echoOrder")
				.replace("payload: { kind: bytes, size: 1024 }", "payload: { kind: order }");
		assertProblem(problems(order, null), "target.payload.kind", "transport = http only takes bytes");
		String rpcFields = HTTP_CARD.replace("  payload: { kind: bytes, size: 1024 }\n",
				"  payload: { kind: bytes, size: 1024 }\n  serializer: protobuf\n  providers: 1\n");
		List<String> problems = problems(rpcFields, null);
		assertProblem(problems, "target.serializer", "does not apply to transport = http");
		assertProblem(problems, "target.providers", "does not apply to transport = http");
	}

	/** target.connections：可选，1 ~ 64；不写 = 各框架缺省（null）；http 卡不接受（它的连接池是 target.http.connections）。 */
	@Test
	void targetConnectionsIsOptionalAndBounded() {
		String base = BRIEF_EXAMPLE;
		assertEquals(null, ProfileLoader.parse(base, null, "test").target().connections());
		String four = base.replace("  providers: 1\n", "  providers: 1\n  connections: 4\n");
		Profile parsed = ProfileLoader.parse(four, null, "test");
		assertEquals(4, parsed.target().connections());
		assertEquals(4, ((java.util.Map<?, ?>) parsed.toJson().get("target")).get("connections"));
		assertProblem(problems(base.replace("  providers: 1\n", "  providers: 1\n  connections: 0\n"), null), "target.connections",
				"out of range [1, 64]");
		assertProblem(problems(HTTP_CARD.replace("  payload: { kind: bytes, size: 1024 }\n", "  payload: { kind: bytes, size: 1024 }\n  connections: 2\n"),
				null), "target.connections", "does not apply to transport = http");
	}

	/** target.server_executor：只用于 rpc 卡，取值 fixed | virtual | direct，缺省 fixed；对比对象与 http 卡不接受。 */
	@Test
	void serverExecutorIsAnRpcOnlyEnumDefaultingToFixed() {
		Profile defaults = ProfileLoader.parse(BRIEF_EXAMPLE, null, "test");
		assertEquals("fixed", defaults.target().serverExecutor());
		assertEquals("fixed", ((java.util.Map<?, ?>) defaults.toJson().get("target")).get("server_executor"));
		for (String kind : List.of("fixed", "virtual", "direct")) {
			Profile p = ProfileLoader.parse(BRIEF_EXAMPLE.replace("  providers: 1\n", "  providers: 1\n  server_executor: " + kind + "\n"), null,
					"test");
			assertEquals(kind, p.target().serverExecutor());
		}
		assertProblem(problems(BRIEF_EXAMPLE.replace("  providers: 1\n", "  providers: 1\n  server_executor: grpc-default\n"), null),
				"target.server_executor", "grpc-default");
		assertProblem(problems(HTTP_CARD.replace("  payload: { kind: bytes, size: 1024 }\n",
				"  payload: { kind: bytes, size: 1024 }\n  server_executor: direct\n"), null), "target.server_executor",
				"does not apply to transport = http");
		assertNull(ProfileLoader.parse(HTTP_CARD, null, "test").target().serverExecutor());
	}

	@Test
	void theCrossValidationCardsAreValid() throws Exception {
		Path profiles = Path.of("..", "bench", "profiles");
		Profile open = ProfileLoader.load(profiles.resolve("crossval-http-open-v1.yaml"));
		Profile closed = ProfileLoader.load(profiles.resolve("crossval-http-closed-capacity-v1.yaml"));
		assertEquals("open", open.load().model());
		assertEquals(5000, open.load().rate());
		assertEquals("closed", closed.load().model());
		assertEquals(64, closed.load().concurrency());
		assertEquals("crossval-http-closed-capacity-v1", closed.id());
		assertEquals("校验 harness 开环的达成吞吐、服务时间与节拍质量", open.question());
		assertEquals("标定 harness 闭环发压上限与服务端上限（64 连接、1KB）", closed.question());
		for (Profile p : List.of(open, closed)) {
			assertTrue(p.target().http());
			assertEquals("http://127.0.0.1:19180/echo", p.target().httpUrl());
			assertEquals(64, p.target().httpConnections());
			assertEquals(1024, p.target().payloadSize());
			assertEquals(Duration.ofSeconds(60), p.load().duration());
			assertEquals(Duration.ofSeconds(20), p.load().warmup());
			assertEquals(Duration.ofSeconds(20), p.load().windowStart());
			assertEquals(Duration.ofSeconds(60), p.load().windowEnd());
			assertEquals(3, p.load().rounds());
		}
	}

	@Test
	void theRepositoryCardsAreValid() throws Exception {
		Path profiles = Path.of("..", "bench", "profiles");
		Profile throughput = ProfileLoader.load(profiles.resolve("loopback-throughput-v1.yaml"));
		assertEquals("closed", throughput.load().model());
		assertEquals(64, throughput.load().concurrency());
		Profile latency = ProfileLoader.load(profiles.resolve("loopback-latency-v1.yaml"));
		assertEquals("open", latency.load().model());
		assertEquals(10000, latency.load().rate());
		for (Profile p : List.of(throughput, latency)) {
			assertEquals(Duration.ofSeconds(60), p.load().duration());
			assertEquals(Duration.ofSeconds(20), p.load().warmup());
			assertEquals(Duration.ofSeconds(20), p.load().windowStart());
			assertEquals(Duration.ofSeconds(55), p.load().windowEnd());
			assertEquals(3, p.load().rounds());
		}
	}
}
