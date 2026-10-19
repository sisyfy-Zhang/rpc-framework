package com.feynman.rpc.bench;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Markdown 只从 JSON 生成：用手写的 fixture JSON（开环、3 轮、断言失败）检查渲染结果。 */
class MarkdownReportTest {

	static final Path FIXTURE = Path.of("src", "test", "resources", "fixtures", "fixture-open-v1-20260101-000000.json");

	private static Map<?, ?> fixture() throws Exception {
		return (Map<?, ?>) Json.parse(Files.readString(FIXTURE, StandardCharsets.UTF_8));
	}

	@Test
	void rendersAllSectionsFromTheJson() throws Exception {
		String md = MarkdownReport.render(fixture(), FIXTURE.getFileName().toString());

		assertTrue(md.startsWith("# fixture-open-v1\n"), md);
		assertTrue(md.contains("标签：`fixture`"));
		assertTrue(md.contains("判定：**未通过**"));
		assertTrue(md.contains("**问题**：fixture question | with a pipe"));
		for (String heading : List.of("## 环境", "## 口径", "## 轮次", "## 断言", "## 声明")) {
			assertTrue(md.contains("\n" + heading + "\n"), "missing " + heading);
		}
		// 环境表
		assertTrue(md.contains("| WSL | 是 |"));
		assertTrue(md.contains("| CPU | Fixture CPU @ 3.00GHz |"));
		assertTrue(md.contains("| 内存 | 8.0 GiB |"));
		assertTrue(md.contains("| 可用核数 / 主机核数 | 5 / 12 |"), "available / host cores (no server environment in the fixture)");
		assertTrue(md.contains("| 指标 | 压测端 metrics_enabled=false · 服务端 metrics_enabled=— |"), md);
		assertTrue(md.contains("| git | 0123456（干净） |"));
		assertTrue(md.contains("`-Xms1g -Xmx1g`"));
		// 口径表
		assertTrue(md.contains("开环（open），1000 req/s 等间隔发出，在途上限 2000"));
		assertTrue(md.contains("统计窗口 [2s, 9s]，共 3 轮"));
		assertTrue(md.contains("计划发出时刻（开环，修正 coordinated omission）"));
	}

	/** 改名前的结果文件只有 logical_cpus（= 可用核数）：照常渲染，主机核数记为 —。 */
	@Test
	void legacyLogicalCpusIsRenderedAsAvailableCores() throws Exception {
		@SuppressWarnings("unchecked")
		Map<String, Object> json = (Map<String, Object>) fixture();
		@SuppressWarnings("unchecked")
		Map<String, Object> env = new java.util.LinkedHashMap<>((Map<String, Object>) json.get("environment"));
		env.remove("available_cpus");
		env.remove("host_cpus");
		env.put("logical_cpus", 8);
		Map<String, Object> legacy = new java.util.LinkedHashMap<>(json);
		legacy.put("environment", env);
		assertTrue(MarkdownReport.render(legacy, "legacy.json").contains("| 可用核数 / 主机核数 | 8 / — |"));
	}

	@Test
	void roundTableFollowsMeasureReportAndBoldsTheOfficialRound() throws Exception {
		String md = MarkdownReport.render(fixture(), "f.json");
		assertTrue(md.contains("| 轮 | 窗口内请求 | 吞吐 (req/s) | p50 (ms) | p99 (ms) | 错误（窗口内） | 服务时间 p99 (ms) | 发出滞后 p99 (ms) | 客户端核 CPU | 服务端核 CPU | GC 停顿 客户端/服务端 (ms) | 有效 |"),
				"columns follow measure.report (+ open-loop extras):\n" + md);
		assertFalse(md.contains("p95 (ms)"), "p95 is not in measure.report");
		assertTrue(md.contains("| 1 | 7000 | 1000.0 | 0.410 | 0.950 | 0 | 0.900 | 0.004 | 42.5% | 13.0% | 84 / 7 | 是 |"), md);
		assertTrue(md.contains("| **2（正式）** | **7001** | **999.7** | **0.420** | **1.250** | **3（timeout×3）** | **1.200** | **0.005** | **n/a** | **n/a** | **0 / unknown** | **是** |"), md);
		assertTrue(md.contains("| 0.006 | — | — | — | 是 |"), "a result file without rounds[].cpu / rounds[].gc renders —");
		assertTrue(md.contains("正式轮：第 2 轮（开环：窗口 p99 的中位数那一轮）"));
		assertTrue(md.contains("p99_ms 0.800（84.2%）"));
	}

	@Test
	void assertionsAndStatementsAreRendered() throws Exception {
		String md = MarkdownReport.render(fixture(), "f.json");
		assertTrue(md.contains("| `errors_max` | 0 | 3 | 全部轮次窗口内错误之和 | **未通过** |"), md);
		assertTrue(md.contains("| `p99_max` | 1ms | 1.25ms | 正式轮 | **未通过** |"), md);
		assertTrue(md.contains("不可外推"));
		assertTrue(md.contains("- 拓扑：fixture topology sentence"));
		assertTrue(md.contains("- fixture statement about open loop"));
	}

	@Test
	void reportCommandGroupsRunsByProfile(@TempDir Path dir) throws Exception {
		Files.copy(FIXTURE, dir.resolve("a.json"));
		Files.copy(FIXTURE, dir.resolve("b.json"));
		Files.writeString(dir.resolve("other.json"), "{\"schema_version\": 99}");
		Files.writeString(dir.resolve("broken.json"), "{ not json");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		Path target = dir.resolve("out").resolve("summary.md");
		int code = Bench.execute(new String[] { "report", "--in", dir.toString(), "--out", target.toString() },
				new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, err.toString(StandardCharsets.UTF_8));
		String md = Files.readString(target, StandardCharsets.UTF_8);
		assertTrue(md.startsWith("# 压测报告汇总"));
		assertTrue(md.contains("| [fixture-open-v1](#fixture-open-v1) | 2 |"), md);
		assertTrue(md.contains("\n## fixture-open-v1\n"));
		assertTrue(md.contains("\n### a.json\n") && md.contains("\n### b.json\n"), "one subsection per run of the same card");
		assertTrue(md.contains("\n#### 轮次\n"));
		String errors = err.toString(StandardCharsets.UTF_8);
		assertTrue(errors.contains("other.json") && errors.contains("broken.json"), errors);
	}

	@Test
	void reportCommandAddsTheCrossValidationVerdicts(@TempDir Path dir) throws Exception {
		Files.copy(FIXTURE, dir.resolve("a.json"));
		Files.writeString(dir.resolve("crossval-open-20261019-115413.json"), """
				{"schema": "crossval/2", "mode": "open", "kind": "measurement", "created_at": "2026-10-19T11:54:13+11:00",
				 "question": "fixture crossval question",
				 "harness": {"file": "crossval-http-open-v1-x.json", "git_sha": "77d04f2abcdef", "git_dirty": false},
				 "tool": {"name": "wrk2", "command": "wrk2 -R 5000 -U"},
				 "checks": [
				   {"group": "a. 达成吞吐", "criterion": "harness 相对目标 rate 偏差 ≤ 2%", "measured": "5000 vs 5000 req/s",
				    "rel_dev_pct": 0.0, "passed": true},
				   {"group": "b. 测量一致性", "criterion": "p99 a|b", "measured": "0.534 vs 0.367 ms", "rel_dev_pct": 45.504,
				    "passed": false}
				 ],
				 "verdict": "FAIL"}
				""");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		Path target = dir.resolve("summary.md");
		int code = Bench.execute(new String[] { "report", "--in", dir.toString(), "--out", target.toString() },
				new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, err.toString(StandardCharsets.UTF_8));
		String md = Files.readString(target, StandardCharsets.UTF_8);
		assertTrue(md.contains("| [fixture-open-v1](#fixture-open-v1) | 1 |"), "bench results are still summarized:\n" + md);
		assertTrue(md.contains("\n## 交叉验证判定\n"), md);
		assertTrue(md.contains("| [crossval-open-20261019-115413.json](#crossval-open-20261019-115413json) | open (measurement) | wrk2 | "
				+ "crossval-http-open-v1-x.json | **FAIL** |"), md);
		assertTrue(md.contains("| b. 测量一致性 | p99 a\\|b | 0.534 vs 0.367 ms | 45.5% | **未通过** |"), md);
		assertTrue(md.contains("| a. 达成吞吐 | harness 相对目标 rate 偏差 ≤ 2% | 5000 vs 5000 req/s | 0.0% | 通过 |"), md);
		assertFalse(err.toString(StandardCharsets.UTF_8).contains("crossval-open"), "crossval results are not skipped");
		assertTrue(out.toString(StandardCharsets.UTF_8).contains("1 crossval result(s)"));
	}
}
