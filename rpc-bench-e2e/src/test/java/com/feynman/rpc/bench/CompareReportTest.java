package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code compare} 子命令：用合成的结果 JSON（fixture）验证分组、对比轴识别、行序、"吞吐 / 首行"与各列的取数；
 * {@code report} 汇总里也有同一份对比表。
 */
class CompareReportTest {

	/** 一份最小但结构完整的结果 JSON（只含对比表与汇总会读的字段）。 */
	static Map<String, Object> result(String id, String transport, String serializer, String model, int loadValue, double throughput,
			double p99, double clientCpu, double serverCpu, boolean clientBound, String startedAt) {
		boolean closed = "closed".equals(model);
		Map<String, Object> target = new LinkedHashMap<>();
		target.put("transport", transport);
		target.put("service", "BenchService#echo");
		target.put("method", "echo");
		target.put("payload", Map.of("kind", "bytes", "size", 1024));
		target.put("serializer", serializer);
		target.put("providers", 1);
		Map<String, Object> load = new LinkedHashMap<>();
		load.put("model", model);
		load.put("concurrency", closed ? loadValue : null);
		load.put("rate", closed ? null : loadValue);
		load.put("duration", "60s");
		load.put("duration_ms", 60000.0);
		load.put("warmup", "20s");
		load.put("warmup_ms", 20000.0);
		load.put("steady_window", List.of("20s", "55s"));
		load.put("steady_window_ms", List.of(20000.0, 55000.0));
		load.put("rounds", 3);
		load.put("timeout", "1s");
		load.put("timeout_ms", 1000.0);
		Map<String, Object> parsed = new LinkedHashMap<>();
		parsed.put("profile", id);
		parsed.put("question", "fixture");
		parsed.put("target", target);
		parsed.put("load", load);
		parsed.put("isolation", Map.of("client_process", "separate_jvm", "server_process", "separate_jvm", "same_host", true,
				"registry", "none (--direct)"));
		parsed.put("measure", Map.of("histogram", Map.of("significant_digits", 3, "max", "10s"), "report", List.of("throughput", "p99")));
		Map<String, Object> latency = new LinkedHashMap<>();
		latency.put("p50", p99 / 4);
		latency.put("p99", p99);
		latency.put("p999", p99 * 2);
		latency.put("max", p99 * 10);
		Map<String, Object> round = new LinkedHashMap<>();
		round.put("round", 2);
		round.put("valid", true);
		round.put("throughput_rps", throughput);
		round.put("latency_ms", latency);
		round.put("service_time_ms", Map.of("p99", p99 / 2));
		round.put("window", Map.of("requests", 1000, "successes", 1000, "errors", 0));
		round.put("cpu", Map.of("client_busy_pct", clientCpu, "server_busy_pct", serverCpu));
		round.put("gc", Map.of("client", Map.of("pause_ms", 3, "pause_pct", 0.01), "server", Map.of("pause_ms", 1, "pause_pct", 0.0)));
		Map<String, Object> other = new LinkedHashMap<>(round);
		other.put("round", 1);
		other.put("throughput_rps", throughput * 0.9);
		other.put("cpu", Map.of("client_busy_pct", 1.0, "server_busy_pct", 1.0));
		Map<String, Object> official = new LinkedHashMap<>();
		official.put("throughput_rps", throughput);
		official.put("p50_ms", p99 / 4);
		official.put("p99_ms", p99);
		official.put("p999_ms", p99 * 2);
		official.put("max_ms", p99 * 10);
		official.put("window_requests", 1000);
		official.put("window_errors", 0);
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("schema_version", 1);
		json.put("label", "fixture");
		json.put("profile", Map.of("id", id, "parsed", parsed, "raw_yaml", "profile: " + id));
		json.put("run", Map.of("target", Map.of("transport", transport, "connection_model", transport + " connection model"),
				"harness_options", Map.of("io_threads", "default (Netty: 2 x available cores = 10)")));
		json.put("environment", Map.of("started_at", startedAt, "hostname", "fixture-host"));
		json.put("server_environment", Map.of("server_io_threads", "default (Netty: 2 x available cores = 10)", "server_executor",
				transport + " executor"));
		json.put("rounds", List.of(other, round));
		json.put("summary", Map.of("official_round", 2, "official", official));
		json.put("validity", Map.of("passed", true, "client_bound", clientBound, "statements", List.of(), "asserts", List.of()));
		return json;
	}

	private static void write(Path dir, String name, Map<String, Object> json) throws Exception {
		Files.writeString(dir.resolve(name), Json.write(json), StandardCharsets.UTF_8);
	}

	private static Path fixtures(Path dir) throws Exception {
		Files.createDirectories(dir);
		write(dir, "compare-closed64-rpc-v1-20261019-100000.json",
				result("compare-closed64-rpc-v1", "rpc", "protobuf", "closed", 64, 40000.0, 4.0, 95.0, 60.0, true, "2026-10-19T10:00:00Z"));
		write(dir, "compare-closed64-grpc-v1-20261019-100500.json",
				result("compare-closed64-grpc-v1", "grpc", "protobuf", "closed", 64, 30000.0, 5.0, 80.0, 70.0, false, "2026-10-19T10:05:00Z"));
		write(dir, "sweep-closed256-rpc-v1-20261019-101500.json",
				result("sweep-closed256-rpc-v1", "rpc", "protobuf", "closed", 256, 80000.0, 9.0, 70.0, 99.0, false, "2026-10-19T10:15:00Z"));
		write(dir, "sweep-closed128-rpc-v1-20261019-101000.json",
				result("sweep-closed128-rpc-v1", "rpc", "protobuf", "closed", 128, 60000.0, 6.0, 80.0, 90.0, false, "2026-10-19T10:10:00Z"));
		write(dir, "compare-open10k-grpc-v1-20261019-102500.json",
				result("compare-open10k-grpc-v1", "grpc", "protobuf", "open", 10000, 10000.0, 1.2, 40.0, 30.0, false, "2026-10-19T10:25:00Z"));
		write(dir, "compare-open10k-rpc-v1-20261019-102000.json",
				result("compare-open10k-rpc-v1", "rpc", "protobuf", "open", 10000, 10000.0, 1.5, 45.0, 25.0, false, "2026-10-19T10:20:00Z"));
		// 只有一份的口径（不同的 payload 大小）不出表；交叉验证结果与坏文件跳过
		Map<String, Object> lonely = result("lonely-v1", "rpc", "protobuf", "closed", 64, 1.0, 1.0, 1.0, 1.0, false, "2026-10-19T09:00:00Z");
		@SuppressWarnings("unchecked")
		Map<String, Object> target = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) lonely.get("profile")).get("parsed"))
				.get("target");
		target.put("payload", Map.of("kind", "bytes", "size", 16));
		write(dir, "lonely-v1-20261019-090000.json", lonely);
		Files.writeString(dir.resolve("crossval-open-20261019-110000.json"), "{\"schema\": \"crossval/3\", \"verdict\": \"PASS\"}",
				StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);
		return dir;
	}

	@Test
	void sideBySideTablesPerVaryingAxis(@TempDir Path dir) throws Exception {
		Path in = fixtures(dir.resolve("in"));
		Path md = dir.resolve("compare.md");
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = Bench.execute(new String[] { "compare", "--in", in.toString(), "--out", md.toString() },
				new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, stderr.toString(StandardCharsets.UTF_8));
		assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("7 result(s), 2 skipped"), stdout.toString(StandardCharsets.UTF_8));
		String text = Files.readString(md, StandardCharsets.UTF_8);
		System.out.println(text);

		assertTrue(text.startsWith("# 对比表\n"));
		assertTrue(text.contains("## 闭环 · 1024 B · `BenchService#echo` · 每轮 60s（预热 20s，窗口 [20s, 55s]）× 3 轮 · 超时 1s"), text);
		assertTrue(text.contains("## 开环 · 1024 B"), text);
		assertFalse(text.contains("lonely-v1"), "a profile with no comparable partner gets no table");

		// 闭环组里 transport 与 concurrency 两个轴都在变：transport 一张（64 并发），concurrency 一张（rpc）
		assertTrue(text.contains("> 对比轴：`transport`（concurrency = 64）。"), text);
		assertTrue(text.contains("> 对比轴：`concurrency`（transport = rpc（protobuf））。"), text);
		assertFalse(text.contains("transport = grpc（protobuf））"), "grpc has a single concurrency: no concurrency table for it");
		// 开环组只有 transport 在变
		assertTrue(text.contains("> 对比轴：`transport`。"), text);

		String transportTable = section(text, "> 对比轴：`transport`（concurrency = 64）。");
		assertTrue(transportTable.indexOf("**rpc（protobuf）**") < transportTable.indexOf("**grpc（protobuf）**"), "rpc row first");
		assertTrue(transportTable.contains("| **rpc（protobuf）** | `compare-closed64-rpc-v1` | 40000.0 | 1.00× | 1.000 | 4.000 | 8.000 | 40.000 | 95.0% | 60.0% | **是** | 3 / 1 | — | — | — | 0 | 通过 | rpc connection model |"),
				transportTable);
		assertTrue(transportTable.contains("| **grpc（protobuf）** | `compare-closed64-grpc-v1` | 30000.0 | 0.75× |"), transportTable);
		assertTrue(transportTable.contains("执行器 grpc executor"), "server configuration column comes from server_environment");

		String sweep = section(text, "> 对比轴：`concurrency`（transport = rpc（protobuf））。");
		int r64 = sweep.indexOf("| **64** |");
		int r128 = sweep.indexOf("| **128** |");
		int r256 = sweep.indexOf("| **256** |");
		assertTrue(r64 >= 0 && r64 < r128 && r128 < r256, "numeric order, not file order: " + sweep);
		assertTrue(sweep.contains("| **256** | rpc（protobuf） | `sweep-closed256-rpc-v1` | 80000.0 | 2.00× |"), sweep);
		assertTrue(sweep.contains("| 70.0% | 99.0% | 否 |"), "the official round's CPU, not round 1's");

		String open = section(text, "> 对比轴：`transport`。");
		assertTrue(open.contains("服务时间 p99 (ms)"), "open-loop tables add the service time");
		assertTrue(open.contains("开环延迟从计划发出时刻算起"), open);
	}

	@Test
	void reportIncludesTheCompareTables(@TempDir Path dir) throws Exception {
		Path in = fixtures(dir.resolve("in"));
		Path md = dir.resolve("README.md");
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = Bench.execute(new String[] { "report", "--in", in.toString(), "--out", md.toString() },
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, stderr.toString(StandardCharsets.UTF_8));
		String text = Files.readString(md, StandardCharsets.UTF_8);
		int compare = text.indexOf("## 对比表");
		assertTrue(compare > 0, text);
		assertTrue(text.indexOf("### 闭环 · 1024 B") > compare, "groups are one level below the section");
		assertTrue(text.indexOf("> 对比轴：`concurrency`（transport = rpc（protobuf））。") > compare);
	}

	@Test
	void repeatedRunsOfOneCardFormARepeatTable(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir);
		write(dir, "a.json", result("same-v1", "rpc", "protobuf", "closed", 64, 100.0, 1.0, 10.0, 10.0, false, "2026-10-19T10:00:00Z"));
		write(dir, "b.json", result("same-v1", "rpc", "protobuf", "closed", 64, 110.0, 1.0, 10.0, 10.0, false, "2026-10-19T11:00:00Z"));
		String text = CompareReport.document(List.of(
				Map.entry("a.json", (Map<?, ?>) Json.parse(Files.readString(dir.resolve("a.json")))),
				Map.entry("b.json", (Map<?, ?>) Json.parse(Files.readString(dir.resolve("b.json"))))));
		assertTrue(text.contains("> 同一口径的重复运行（没有字段不同）。"), text);
		assertTrue(text.contains("| 1.10× |"), text);
		assertTrue(CompareReport.document(List.of()).contains("没有可以并排的结果"));
	}

	/** 各 transport 的缺省 IO 线程写法不同，不算对比轴；命令行显式给的 --io-threads 才算。 */
	@Test
	void ioThreadsIsAnAxisOnlyWhenGivenOnTheCommandLine() {
		Map<String, Object> a = result("io-a", "rpc", "protobuf", "closed", 64, 100.0, 1.0, 10.0, 10.0, false, "2026-10-19T10:00:00Z");
		Map<String, Object> b = result("io-b", "grpc", "protobuf", "closed", 64, 90.0, 1.0, 10.0, 10.0, false, "2026-10-19T10:01:00Z");
		b.put("run", Map.of("target", Map.of("transport", "grpc"), "harness_options",
				Map.of("io_threads", "default (Netty: 2 x available cores = 8)", "io_source", "default")));
		String defaults = CompareReport.document(List.of(Map.entry("a.json", a), Map.entry("b.json", b)));
		assertTrue(defaults.contains("> 对比轴：`transport`。"), defaults);
		assertFalse(defaults.contains("io_threads ="), defaults);

		Map<String, Object> one = result("io-1", "rpc", "protobuf", "closed", 64, 100.0, 1.0, 10.0, 10.0, false, "2026-10-19T10:00:00Z");
		one.put("run", Map.of("target", Map.of("transport", "rpc"), "harness_options", Map.of("io_threads", 1, "io_source", "command line")));
		Map<String, Object> two = result("io-2", "rpc", "protobuf", "closed", 64, 120.0, 1.0, 10.0, 10.0, false, "2026-10-19T10:01:00Z");
		two.put("run", Map.of("target", Map.of("transport", "rpc"), "harness_options", Map.of("io_threads", 2, "io_source", "command line")));
		String axis = CompareReport.document(List.of(Map.entry("1.json", one), Map.entry("2.json", two)));
		assertTrue(axis.contains("> 对比轴：`io_threads`。"), axis);
		assertTrue(axis.contains("| **2** | rpc（protobuf） | `io-2` | 120.0 | 1.20× |"), axis);
	}

	/** 给 fixture 加上实际连接数（run.target.connections_per_node）与卡片是否显式写了 target.connections。 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> connections(Map<String, Object> json, int actual, boolean explicit) {
		String transport = String.valueOf(MarkdownReport.path(json, "profile", "parsed", "target", "transport"));
		json.put("run", Map.of("target", Map.of("transport", transport, "connections_per_node", actual, "connection_model", actual + " conn"),
				"harness_options", Map.of("io_threads", "default (x)", "io_source", "default")));
		if (explicit) {
			Map<String, Object> target = (Map<String, Object>) MarkdownReport.path(json, "profile", "parsed", "target");
			target.put("connections", actual);
		}
		return json;
	}

	/** 开箱即用（各框架缺省连接数）与连接数对齐两种配置各一张 transport 表，另有每个对比对象的连接数表。 */
	@Test
	void outOfTheBoxAndConnectionAlignedTablesAreSeparate() {
		Map<String, Object> rpc = connections(result("c-rpc", "rpc", "protobuf", "closed", 64, 80000.0, 2.0, 50.0, 50.0, false, "t1"), 2, false);
		Map<String, Object> grpc = connections(result("c-grpc", "grpc", "protobuf", "closed", 64, 70000.0, 2.0, 50.0, 20.0, false, "t2"), 1, false);
		Map<String, Object> grpc2 = connections(result("c-grpc-c2", "grpc", "protobuf", "closed", 64, 90000.0, 2.0, 50.0, 40.0, false, "t3"), 2, true);
		Map<String, Object> dubbo = connections(result("c-dubbo", "dubbo", "hessian2", "closed", 64, 14000.0, 8.0, 25.0, 40.0, false, "t4"), 1, false);
		Map<String, Object> dubbo2 = connections(result("c-dubbo-c2", "dubbo", "hessian2", "closed", 64, 28000.0, 4.0, 30.0, 60.0, false, "t5"), 2, true);
		String text = CompareReport.document(List.of(Map.entry("rpc.json", rpc), Map.entry("grpc.json", grpc), Map.entry("grpc2.json", grpc2),
				Map.entry("dubbo.json", dubbo), Map.entry("dubbo2.json", dubbo2)));
		System.out.println(text);
		String outOfBox = section(text, "> 对比轴：`transport`（开箱即用：各框架缺省连接数）。");
		assertTrue(outOfBox.contains("`c-rpc`") && outOfBox.contains("`c-grpc`") && outOfBox.contains("`c-dubbo`"), outOfBox);
		assertFalse(outOfBox.contains("c2`"), "explicit-connection cards are not out-of-the-box");
		String aligned = section(text, "> 对比轴：`transport`（连接数对齐；connections = 2）。");
		assertTrue(aligned.contains("`c-rpc`") && aligned.contains("`c-grpc-c2`") && aligned.contains("`c-dubbo-c2`"), aligned);
		assertFalse(aligned.contains("`c-grpc`") || aligned.contains("`c-dubbo`"), aligned);
		String grpcConnections = section(text, "> 对比轴：`connections`（transport = grpc（protobuf））。");
		assertTrue(grpcConnections.contains("| **1（缺省）** | grpc（protobuf） | `c-grpc` |"), grpcConnections);
		assertTrue(grpcConnections.contains("| **2** | grpc（protobuf） | `c-grpc-c2` | 90000.0 | 1.29× |"), grpcConnections);
		assertTrue(text.contains("> 对比轴：`connections`（transport = dubbo（hessian2））。"), text);
		assertFalse(text.contains("transport = rpc（protobuf））"), "rpc has a single connection count here");
	}

	/** 给 fixture 加上服务端执行器（server_executor_kind）与两端 flush 合并（{@code flush} 为 null 时不写：对比对象没有这项）。 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> serverConfig(Map<String, Object> json, String executorKind, String flush) {
		Map<String, Object> env = new LinkedHashMap<>((Map<String, Object>) json.get("server_environment"));
		env.put("server_executor_kind", executorKind);
		env.put("server_executor", executorKind + " executor");
		if (flush != null) {
			env.put("server_flush_consolidation", flush);
			Map<String, Object> run = new LinkedHashMap<>((Map<String, Object>) json.get("run"));
			Map<String, Object> target = new LinkedHashMap<>((Map<String, Object>) run.get("target"));
			target.put("flush_consolidation", flush);
			run.put("target", target);
			json.put("run", run);
		}
		json.put("server_environment", env);
		return json;
	}

	/** 给 fixture 的正式轮加上出站背压统计（rpc 服务端：窗口内暂停读次数；对比对象记 n/a）。 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> backpressure(Map<String, Object> json, Object value) {
		Map<String, Object> official = (Map<String, Object>) ((List<Object>) json.get("rounds")).get(1);
		official.put("backpressure", value);
		return json;
	}

	/**
	 * 回归（20261022 归档时发现）：执行器与 flush 合并是各 transport 的固有属性（rpc fixed / on、gRPC grpc-default、Dubbo 两项都没有），
	 * 以前参与 transport 表的分区，于是每个 transport 各落一个分区，"开箱即用"与"连接数对齐"两类 transport 表一张都不出。
	 * 现在它们不参与分区；本框架的非缺省配置（direct 执行器、flush 合并关）不进 transport 表，只进各自轴的表。
	 */
	@Test
	void transportTablesIgnoreIntrinsicExecutorAndFlushAndLeaveOutNonDefaultRpc() {
		Map<String, Object> pauses = Map.of("window", Map.of("auto_read_pauses", 3, "outbound_overload_rejections", 0));
		Map<String, Object> rpc = backpressure(serverConfig(
				connections(result("c-rpc", "rpc", "protobuf", "closed", 64, 100000.0, 1.5, 57.0, 49.0, false, "t1"), 2, false), "fixed", "on"),
				pauses);
		Map<String, Object> rpcDirect = backpressure(serverConfig(
				connections(result("c-rpc-direct", "rpc", "protobuf", "closed", 64, 140000.0, 1.0, 60.0, 27.0, false, "t2"), 2, false), "direct",
				"on"), pauses);
		Map<String, Object> rpcFlushOff = serverConfig(
				connections(result("c-rpc-off", "rpc", "protobuf", "closed", 64, 90000.0, 1.6, 60.0, 58.0, false, "t3"), 2, false), "fixed", "off");
		Map<String, Object> grpc = backpressure(serverConfig(
				connections(result("c-grpc", "grpc", "protobuf", "closed", 64, 14000.0, 7.0, 21.0, 39.0, false, "t4"), 1, false), "grpc-default",
				null), "n/a");
		Map<String, Object> grpc2 = serverConfig(
				connections(result("c-grpc-c2", "grpc", "protobuf", "closed", 64, 35000.0, 4.7, 52.0, 64.0, false, "t5"), 2, true), "grpc-default",
				null);
		Map<String, Object> dubbo = connections(result("c-dubbo", "dubbo", "hessian2", "closed", 64, 14000.0, 7.3, 24.0, 42.0, false, "t6"), 1, false);
		Map<String, Object> dubbo2 = connections(result("c-dubbo-c2", "dubbo", "hessian2", "closed", 64, 26000.0, 5.8, 56.0, 68.0, false, "t7"), 2,
				true);
		List<Map.Entry<String, Map<?, ?>>> results = List.of(Map.entry("rpc.json", rpc), Map.entry("rpc-direct.json", rpcDirect),
				Map.entry("rpc-off.json", rpcFlushOff), Map.entry("grpc.json", grpc), Map.entry("grpc2.json", grpc2), Map.entry("dubbo.json", dubbo),
				Map.entry("dubbo2.json", dubbo2));
		String text = CompareReport.document(results);
		System.out.println(text);

		String outOfBox = section(text, "> 对比轴：`transport`（开箱即用：各框架缺省连接数）。");
		assertTrue(outOfBox.contains("`c-rpc`") && outOfBox.contains("`c-grpc`") && outOfBox.contains("`c-dubbo`"), outOfBox);
		assertFalse(outOfBox.contains("`c-rpc-direct`") || outOfBox.contains("`c-rpc-off`"), "non-default rpc configurations stay out: " + outOfBox);
		assertFalse(outOfBox.contains("c2`"), outOfBox);
		assertTrue(outOfBox.contains(CompareReport.TRANSPORT_NOTE), outOfBox);
		String aligned = section(text, "> 对比轴：`transport`（连接数对齐；connections = 2）。");
		assertTrue(aligned.contains("`c-rpc`") && aligned.contains("`c-grpc-c2`") && aligned.contains("`c-dubbo-c2`"), aligned);
		assertFalse(aligned.contains("`c-rpc-direct`") || aligned.contains("`c-rpc-off`"), aligned);
		assertFalse(aligned.contains("`c-grpc`") || aligned.contains("`c-dubbo`"), aligned);
		assertFalse(text.contains("> 对比轴：`transport`（server_executor"), "the executor is not a partition key of transport tables");

		// 非缺省配置在各自轴的表里
		String executors = section(text,
				"> 对比轴：`server_executor`（transport = rpc（protobuf），connections = 2，flush_consolidation = client on / server on）。");
		assertTrue(executors.contains("| **direct** | rpc（protobuf） | `c-rpc-direct` |") && executors.contains("| **fixed** | rpc（protobuf） | `c-rpc` |"),
				executors);
		String flush = section(text, "> 对比轴：`flush_consolidation`（transport = rpc（protobuf），connections = 2，server_executor = fixed）。");
		assertTrue(flush.contains("`c-rpc`") && flush.contains("`c-rpc-off`") && !flush.contains("`c-rpc-direct`"), flush);

		// 暂停读次数列：rpc 取窗口值，对比对象（n/a）与没有这项的结果记 —
		assertTrue(outOfBox.contains("暂停读次数"), outOfBox);
		assertTrue(outOfBox.contains("| 57.0% | 49.0% | 否 | 3 / 1 | — | — | 3 | 0 | 通过 |"), outOfBox);
		assertTrue(outOfBox.contains("| 21.0% | 39.0% | 否 | 3 / 1 | — | — | — | 0 | 通过 |"), outOfBox);

		// report 汇总里的对比表一节走同一段逻辑
		String summary = CompareReport.render(results, 3);
		assertTrue(summary.contains("> 对比轴：`transport`（开箱即用：各框架缺省连接数）。"), summary);
		assertTrue(summary.contains("> 对比轴：`transport`（连接数对齐；connections = 2）。"), summary);
	}

	/** 从标记行开始到下一个空行之后的表格结束（下一个以 "> " 或 "#" 开头的行）为止。 */
	private static String section(String text, String marker) {
		int start = text.indexOf(marker);
		assertTrue(start >= 0, "missing " + marker);
		int next = text.length();
		for (String stop : List.of("\n> ", "\n#")) {
			int i = text.indexOf(stop, start + marker.length());
			if (i >= 0) {
				next = Math.min(next, i);
			}
		}
		return text.substring(start, next);
	}
}
