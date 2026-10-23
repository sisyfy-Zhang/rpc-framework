package io.github.sisyfyzhang.steadyrpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sisyfyzhang.steadyrpc.metrics.micrometer.PrometheusEndpoint;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * {@code --metrics-port}：开启时两端的调用都进了指标（服务端 Timer 有计数），报告如实记录 {@code metrics_enabled}；
 * 不开时（缺省）记录为 false，压测路径上没有指标 Filter。
 */
class BenchMetricsTest {

	private static final String CARD = """
			profile: it-metrics
			question: 指标开关的端到端测试
			target:
			  transport: rpc
			  service: BenchService#echo
			  payload: { kind: bytes, size: 64 }
			  serializer: protobuf
			  providers: 1
			load:
			  model: closed
			  concurrency: 4
			  duration: 3s
			  warmup: 1s
			  steady_window: [1s, 2.5s]
			  rounds: 1
			  timeout: 1s
			isolation:
			  client_process: same_jvm
			  server_process: same_jvm
			  same_host: true
			  registry: none (in-process test)
			measure:
			  histogram: { significant_digits: 3, max: 10s }
			  report: [throughput, p50, p95, p99, p999, max, errors_by_status]
			assert:
			  errors_max: 0
			""";

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static Map<?, ?> run(Path dir, int serverPort, String... extra) throws Exception {
		Path card = dir.resolve("it-metrics.yaml");
		Files.writeString(card, CARD, StandardCharsets.UTF_8);
		Path out = dir.resolve("out-" + System.nanoTime());
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		String[] args = Stream.concat(Stream.of("run", "--profile", card.toString(), "--out", out.toString(), "--label", "it-metrics",
				"--direct", "127.0.0.1:" + serverPort), Stream.of(extra)).toArray(String[]::new);
		int code = Bench.execute(args, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8));
		try (Stream<Path> files = Files.list(out)) {
			Path json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
			return (Map<?, ?>) Json.parse(Files.readString(json, StandardCharsets.UTF_8));
		}
	}

	@Test
	void metricsPortInstrumentsBothSidesAndIsRecordedInTheReport(@TempDir Path dir) throws Exception {
		MeterRegistry serverMetrics = PrometheusEndpoint.newRegistry();
		try (BenchServer server = BenchServer.start(0, "127.0.0.1", null, "protobuf", serverMetrics)) {
			Map<?, ?> json = run(dir, server.port(), "--metrics-port", String.valueOf(freePort()));
			assertEquals(Boolean.TRUE, MarkdownReport.path(json, "environment", "metrics_enabled"));
			assertEquals("true", MarkdownReport.path(json, "server_environment", "metrics_enabled"));
			assertEquals(Boolean.TRUE, MarkdownReport.path(json, "run", "target", "metrics_filter"));
			Timer echo = serverMetrics.find("rpc.server.calls").tag("method", "echo").tag("status", "OK").timer();
			assertTrue(echo != null && echo.count() > 0, "server-side calls were recorded");
		}
	}

	@Test
	void withoutMetricsPortTheReportSaysMetricsWereOff(@TempDir Path dir) throws Exception {
		try (BenchServer server = BenchServer.start(0, "127.0.0.1", null, "protobuf")) {
			Map<?, ?> json = run(dir, server.port());
			assertEquals(Boolean.FALSE, MarkdownReport.path(json, "environment", "metrics_enabled"));
			assertEquals("false", MarkdownReport.path(json, "server_environment", "metrics_enabled"));
			assertEquals(Boolean.FALSE, MarkdownReport.path(json, "run", "target", "metrics_filter"));
		}
	}

	@Test
	void metricsPortMustBeAnInteger() {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		PrintStream o = new PrintStream(sink, true, StandardCharsets.UTF_8);
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "server", "--port", "0", "--no-registry", "--metrics-port", "x" }, o, o));
		assertTrue(sink.toString(StandardCharsets.UTF_8).contains("--metrics-port must be an integer"));
	}
}
