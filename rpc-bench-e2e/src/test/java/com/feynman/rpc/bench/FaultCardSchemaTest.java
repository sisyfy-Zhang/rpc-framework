package com.feynman.rpc.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障注入实验新增的口径卡字段（{@code load.steps}、{@code load.faults}、{@code target.load_balance / cluster / retries /
 * server_queue_capacity / server_work}）的解析与校验；不写这些字段的卡与原口径完全相同。
 */
class FaultCardSchemaTest {

	static String card(String targetExtra, String loadBody) {
		return """
				profile: fault-card
				question: 故障卡校验
				target:
				  transport: rpc
				  service: BenchService#echo
				  payload: { kind: bytes, size: 1024 }
				  serializer: protobuf
				  providers: 3
				%s
				load:
				  model: open
				%s
				  duration: 60s
				  warmup: 10s
				  steady_window: [10s, 60s]
				  rounds: 1
				  timeout: 1s
				isolation:
				  client_process: separate_jvm
				  server_process: separate_jvm
				  same_host: true
				  registry: docker-compose/zookeeper
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p99, errors_by_status]
				""".formatted(nest(targetExtra), nest(loadBody));
	}

	/** 把一段 YAML 片段（文本块会剥掉公共缩进）缩进到 target / load 下一级（两个空格）。 */
	static String nest(String fragment) {
		if (fragment.isBlank()) {
			return "";
		}
		String indented = fragment.stripIndent().indent(2);
		return indented.endsWith("\n") ? indented.substring(0, indented.length() - 1) : indented;
	}

	private static List<String> problems(String yaml) {
		return assertThrows(ProfileException.class, () -> ProfileLoader.parse(yaml, null, "test.yaml")).problems();
	}

	private static boolean mentions(List<String> problems, String text) {
		return problems.stream().anyMatch(p -> p.contains(text));
	}

	@Test
	void stepsAndFaultsAndRpcOptionsParse() {
		Profile p = ProfileLoader.parse(card("""
				  load_balance: p2c
				  cluster: failover
				  retries: 2
				  server_queue_capacity: 100000
				  server_work: 2ms""", """
				  steps: [{ rate: 5000, duration: 20s }, { rate: 15000, duration: 20s }, { rate: 5000, duration: 20s }]
				  faults:
				    - { at: 20s, kind: toxic-latency, target: 2, latency: 20ms, jitter: 5ms }
				    - { at: 40s, kind: remove-toxic, target: 2 }
				    - { at: 45s, kind: toxic-timeout, target: 3 }
				    - { at: 50s, kind: kill-9, target: 1 }"""), null, "t.yaml");
		Profile.RpcOptions o = p.target().rpcOptions();
		assertEquals("p2c", o.loadBalance());
		assertEquals("failover", o.cluster());
		assertEquals(2, o.retries());
		assertEquals(100000, o.serverQueueCapacity());
		assertEquals(Duration.ofMillis(2), o.serverWork());
		assertEquals(3, p.load().steps().size());
		assertEquals(15000, p.load().rate(), "load.rate is the peak step (in-flight cap)");
		assertEquals(5000, p.load().initialRate());
		assertTrue(p.load().timeline());
		List<Profile.Fault> f = p.load().faults();
		assertEquals(new Profile.Fault(Duration.ofSeconds(20), "toxic-latency", 2, Duration.ofMillis(20), Duration.ofMillis(5), null, "downstream"),
				f.get(0));
		assertEquals(new Profile.Fault(Duration.ofSeconds(40), "remove-toxic", 2, null, null, null, null), f.get(1));
		assertEquals(new Profile.Fault(Duration.ofSeconds(45), "toxic-timeout", 3, null, null, Duration.ZERO, "both"), f.get(2),
				"timeout defaults to 0 (hold the connection), both directions");
		assertEquals("kill-9", f.get(3).kind());
		// JSON：新字段只在写了的时候出现
		Map<String, Object> json = p.toJson();
		Map<?, ?> load = (Map<?, ?>) json.get("load");
		assertEquals(3, ((List<?>) load.get("steps")).size());
		assertEquals(4, ((List<?>) load.get("faults")).size());
		assertEquals("p2c", ((Map<?, ?>) json.get("target")).get("load_balance"));
		assertEquals(2.0, ((Map<?, ?>) json.get("target")).get("server_work_ms"));
	}

	/** 仓库里的六张故障卡：每组实验卡与对照卡只差一处（负载均衡 / 容错策略与重试 / 服务端排队上限）。 */
	@Test
	void repositoryFaultCardsDifferInExactlyOnePlace() throws Exception {
		Path profiles = Path.of("..", "bench", "profiles");
		Profile rr = Profile.load(profiles.resolve("fault-slow-node-rr-v1.yaml"));
		Profile p2c = Profile.load(profiles.resolve("fault-slow-node-p2c-v1.yaml"));
		assertEquals("roundrobin", rr.target().rpcOptions().loadBalance());
		assertEquals("p2c", p2c.target().rpcOptions().loadBalance());
		assertEquals(rr.load(), p2c.load());
		assertEquals(2, rr.load().faults().size());
		assertEquals(Duration.ofMillis(20), rr.load().faults().get(0).latency());

		Profile failover = Profile.load(profiles.resolve("fault-kill-node-failover-v1.yaml"));
		Profile failfast = Profile.load(profiles.resolve("fault-kill-node-failfast-v1.yaml"));
		assertEquals("failover", failover.target().rpcOptions().cluster());
		assertEquals(2, failover.target().rpcOptions().retries());
		assertEquals("failfast", failfast.target().rpcOptions().cluster());
		assertEquals(0, failfast.target().rpcOptions().retries());
		assertEquals(failover.load(), failfast.load());
		assertEquals("kill-9", failover.load().faults().get(0).kind());

		Profile bounded = Profile.load(profiles.resolve("fault-burst3x-bounded-v1.yaml"));
		Profile unbounded = Profile.load(profiles.resolve("fault-burst3x-unbounded-v1.yaml"));
		assertNull(bounded.target().rpcOptions().serverQueueCapacity(), "bounded = the framework default queue (1000)");
		assertEquals(100000, unbounded.target().rpcOptions().serverQueueCapacity());
		assertEquals(Duration.ofMillis(1), bounded.target().rpcOptions().serverWork(), "1ms: capacity ~9k, the 5k base step ~55% busy");
		assertEquals(bounded.load(), unbounded.load());
		assertEquals(List.of(5000, 15000, 5000), bounded.load().steps().stream().map(Profile.Step::rate).toList());

		Profile grace = Profile.load(profiles.resolve("fault-sigterm-grace2s-failover-v1.yaml"));
		Profile graceFailfast = Profile.load(profiles.resolve("fault-sigterm-grace2s-failfast-v1.yaml"));
		assertEquals(Duration.ofSeconds(2), grace.target().rpcOptions().serverDeregisterGrace());
		assertEquals("sigterm", grace.load().faults().get(0).kind());
		assertEquals("failover", grace.target().rpcOptions().cluster());
		assertEquals("failfast", graceFailfast.target().rpcOptions().cluster());
		assertEquals(grace.load(), graceFailfast.load());
	}

	@Test
	void cardsWithoutTheNewFieldsAreUnchanged() {
		Profile p = ProfileLoader.parse(ProfileLoaderTest.BRIEF_EXAMPLE, null, "t.yaml");
		assertFalse(p.load().timeline());
		assertTrue(p.load().steps().isEmpty());
		assertTrue(p.load().faults().isEmpty());
		assertFalse(p.target().rpcOptions().declared());
		Map<?, ?> load = (Map<?, ?>) p.toJson().get("load");
		assertFalse(load.containsKey("steps"));
		assertFalse(load.containsKey("faults"));
		assertFalse(((Map<?, ?>) p.toJson().get("target")).containsKey("load_balance"));
	}

	@Test
	void stepsMustCoverTheDurationAndExcludeRate() {
		List<String> problems = problems(card("", """
				  rate: 5000
				  steps: [{ rate: 5000, duration: 20s }, { rate: 15000, duration: 30s }]"""));
		assertTrue(mentions(problems, "load.steps: durations add up to 50s but load.duration is 60s"), problems.toString());
		assertTrue(mentions(problems, "load.rate: do not combine with load.steps"), problems.toString());
		problems = problems(card("", """
				  steps: [{ rate: 0, duration: 60s }, { rat: 1, duration: 1s }, 5]"""));
		assertTrue(mentions(problems, "load.steps[0].rate: 0 is out of range"), problems.toString());
		assertTrue(mentions(problems, "load.steps[1].rat: unknown field"), problems.toString());
		assertTrue(mentions(problems, "load.steps[2]: expected a mapping"), problems.toString());
		assertTrue(mentions(problems, "load.rate: required when load.model = open (or give load.steps)") == false, problems.toString());
	}

	@Test
	void faultsAreValidated() {
		List<String> problems = problems(card("", """
				  rate: 5000
				  faults:
				    - { at: 70s, kind: toxic-latency, target: 4 }
				    - { at: 10s, kind: explode, target: 1 }
				    - { at: 5s, kind: kill-9, target: 1, latency: 3ms }"""));
		assertTrue(mentions(problems, "load.faults[0].latency: required"), problems.toString());
		assertTrue(mentions(problems, "load.faults[0].at: 70s is not inside the round"), problems.toString());
		assertTrue(mentions(problems, "load.faults[0].target: 4 but target.providers is 3"), problems.toString());
		assertTrue(mentions(problems, "load.faults[1].kind: \"explode\" is not one of"), problems.toString());
		assertTrue(mentions(problems, "load.faults[2].latency: unknown field"), problems.toString());
		assertTrue(mentions(problems, "list the faults in time order"), problems.toString());

		problems = problems(card("", """
				  rate: 5000
				  faults: [{ at: 20s, kind: kill-9, target: 2 }]""").replace("rounds: 1", "rounds: 3"));
		assertTrue(mentions(problems, "load.rounds: must be 1 when load.faults is given"), problems.toString());
	}

	@Test
	void rpcOptionsAreValidated() {
		List<String> problems = problems(card("""
				  load_balance: random
				  cluster: hedging
				  retries: 11
				  server_executor: virtual
				  server_queue_capacity: 10
				  server_work: 2s""", "  rate: 5000"));
		assertTrue(mentions(problems, "target.load_balance: \"random\" is not one of"), problems.toString());
		assertTrue(mentions(problems, "target.cluster: \"hedging\" is not one of"), problems.toString());
		assertTrue(mentions(problems, "target.retries: 11 is out of range"), problems.toString());
		assertTrue(mentions(problems, "target.server_queue_capacity: only applies to server_executor: fixed"), problems.toString());
		assertTrue(mentions(problems, "target.server_work: 2s is longer than 1s"), problems.toString());

		problems = problems(ProfileLoaderTest.HTTP_CARD.replace("  http: {", "  retries: 1\n  http: {"));
		assertTrue(mentions(problems, "target.retries: only applies to transport = rpc"), problems.toString());
	}

	@Test
	void faultPlanOptionCombinations(@TempDir Path dir) throws Exception {
		Profile withFaults = ProfileLoader.parse(card("", """
				  rate: 5000
				  faults: [{ at: 20s, kind: kill-9, target: 2 }]"""), null, "t.yaml");
		Profile without = ProfileLoader.parse(card("", "  rate: 5000"), null, "t.yaml");
		Path schedule = dir.resolve("schedule.txt");

		assertEquals(FaultPlan.MODE_NONE, FaultPlan.of(without, null, false, null, null).mode());
		assertThrows(IllegalArgumentException.class, () -> FaultPlan.of(without, schedule, false, null, null));
		assertThrows(IllegalArgumentException.class, () -> FaultPlan.of(withFaults, null, false, null, null),
				"a card with faults needs --fault-schedule or --no-faults");
		assertThrows(IllegalArgumentException.class, () -> FaultPlan.of(withFaults, schedule, false, "explode", null));

		FaultPlan control = FaultPlan.of(withFaults, null, true, null, null);
		assertEquals(FaultPlan.MODE_CONTROL, control.mode());
		assertFalse(control.inject());

		FaultPlan variant = FaultPlan.of(withFaults, schedule, false, "toxic-timeout", null);
		assertEquals("toxic-timeout", variant.faults().get(0).kind());
		assertEquals(Duration.ZERO, variant.faults().get(0).timeout());
		assertEquals("both", variant.faults().get(0).stream());
		assertEquals(2, variant.faults().get(0).target());

		Path topology = dir.resolve("topology.txt");
		Files.writeString(topology, "# idx advertised pid port proxy control\n1 127.0.0.1:19401 111 19401 - 19501\n2 127.0.0.1:20001 222 19402 p2 -\n");
		FaultPlan withTopology = FaultPlan.of(withFaults, schedule, false, null, topology);
		assertEquals(Map.of("1", "127.0.0.1:19401", "2", "127.0.0.1:20001"), withTopology.topology());
		assertEquals(Map.of("127.0.0.1:19401", 19501), withTopology.controlPorts(), "'-' = no control port");
	}

	@Test
	void scheduleFileAndExecutionLogRoundTrip(@TempDir Path dir) throws Exception {
		Profile p = ProfileLoader.parse(card("", """
				  rate: 5000
				  faults:
				    - { at: 20s, kind: toxic-latency, target: 2, latency: 20ms, jitter: 5ms }
				    - { at: 40s, kind: remove-toxic, target: 2 }"""), null, "t.yaml");
		Path schedule = dir.resolve("run").resolve("schedule.txt");
		FaultPlan plan = FaultPlan.of(p, schedule, false, null, null);
		plan.writeSchedule(1_000_000L);
		List<String> lines = Files.readAllLines(schedule);
		assertEquals(List.of(FaultPlan.HEADER, "mode inject", "round_start_epoch_ms 1000000",
				"fault 0 20000 toxic-latency 2 latency_ms=20 jitter_ms=5 stream=downstream", "fault 1 40000 remove-toxic 2"), lines);
		assertFalse(Files.exists(schedule.resolveSibling("schedule.txt.tmp")), "written via a temp file and an atomic rename");

		// 驱动脚本只执行了第 0 项
		Files.writeString(plan.executedFile(), "0 1020015 ok latency toxic added\n");
		List<Map<String, Object>> executed = plan.readExecuted(1_000_000L);
		assertEquals(20015.0, executed.get(0).get("executed_at_ms"));
		assertEquals("ok", executed.get(0).get("status"));
		assertEquals("latency toxic added", executed.get(0).get("detail"));
		assertEquals("not_executed", executed.get(1).get("status"));
		assertNull(executed.get(1).get("executed_at_ms"));
		plan.clearExecuted();
		assertFalse(Files.exists(plan.executedFile()));

		FaultPlan control = FaultPlan.of(p, schedule, true, null, null);
		control.writeSchedule(5L);
		assertEquals(List.of(FaultPlan.HEADER, "mode control", "round_start_epoch_ms 5"), Files.readAllLines(schedule),
				"a control run writes no fault lines: the driver does nothing");
	}

	@Test
	void clusterChoicePrefersTheCardAndRejectsConflicts() {
		Profile card = ProfileLoader.parse(card("  load_balance: p2c", "  rate: 5000"), null, "t.yaml");
		ClusterChoice c = ClusterChoice.resolve(card, HarnessOptions.DEFAULTS.withCluster(null, "failover", "1"));
		assertEquals("p2c", c.loadBalance());
		assertEquals("failover", c.cluster());
		assertEquals(1, c.retries());
		assertEquals(Map.of("load_balance", "card", "cluster", "command line", "retries", "command line"), c.sources());
		assertThrows(IllegalArgumentException.class, () -> ClusterChoice.resolve(card, HarnessOptions.DEFAULTS.withCluster("rr", null, null)));
		assertEquals("p2c", ClusterChoice.resolve(card, HarnessOptions.DEFAULTS.withCluster("p2c", null, null)).loadBalance(),
				"the same value on the command line is fine");
		Profile plain = ProfileLoader.parse(card("", "  rate: 5000"), null, "t.yaml");
		ClusterChoice d = ClusterChoice.resolve(plain, HarnessOptions.DEFAULTS);
		assertNull(d.loadBalance());
		assertEquals(0, d.retries());
		assertEquals("roundrobin", ClusterChoice.resolve(plain, HarnessOptions.DEFAULTS.withCluster("rr", null, null)).loadBalance());
		assertThrows(IllegalArgumentException.class, () -> HarnessOptions.DEFAULTS.withCluster(null, null, "11"));
	}
}
