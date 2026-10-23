package io.github.sisyfyzhang.steadyrpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 对比对象的 SPI（{@link CallTargetProvider}）：classpath 上的提供者被发现、卡片按它校验、{@code run} 用它建目标、
 * {@code server --transport} 把参数交给它的 {@link ServerLauncher}；不认识的 transport 报错并列出可用的全部 transport。
 * 用测试替身 {@link FakeCallTargetProvider}（{@code src/test/resources/META-INF/services}）。
 */
class CallTargetSpiTest {

	static String card(String id, String transport, String serializer, String extraTarget) {
		return """
				profile: %s
				question: SPI 测试用的临时卡
				target:
				  transport: %s
				  service: BenchService#echo
				  payload: { kind: bytes, size: 64 }
				  serializer: %s
				  providers: 1
				%s
				load:
				  model: closed
				  concurrency: 2
				  duration: 1s
				  warmup: 200ms
				  steady_window: [200ms, 1s]
				  rounds: 1
				  timeout: 1s
				isolation:
				  client_process: separate_jvm
				  server_process: separate_jvm
				  same_host: true
				  registry: none (--direct)
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p99, errors_by_status]
				""".formatted(id, transport, serializer, extraTarget);
	}

	@Test
	void providersOnTheClasspathAreDiscoveredNextToTheBuiltIns() {
		assertEquals(List.of("rpc", "http", FakeCallTargetProvider.NAME), List.copyOf(CallTargets.available()));
		assertInstanceOf(FakeCallTargetProvider.class, CallTargets.provider(FakeCallTargetProvider.NAME));
		IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> CallTargets.provider("grcp"));
		assertTrue(unknown.getMessage().contains("unknown transport \"grcp\"; available: [rpc, http, fake-echo]"), unknown.getMessage());
		assertTrue(unknown.getMessage().contains("BENCH_EXTRA_CP"), "the message says how to add a comparison module");
	}

	@Test
	void cardsAreValidatedAgainstTheProvider() {
		Profile ok = ProfileLoader.parse(card("spi-ok", FakeCallTargetProvider.NAME, "identity", ""), null, "test");
		assertEquals(FakeCallTargetProvider.NAME, ok.target().transport());
		assertTrue(ok.target().plugin());
		assertEquals("identity", ok.target().serializer());

		ProfileException unknown = assertThrows(ProfileException.class,
				() -> ProfileLoader.parse(card("spi-x", "grcp", "protobuf", ""), null, "test"));
		assertTrue(unknown.getMessage().contains("target.transport: unknown transport \"grcp\"; available: [rpc, http, fake-echo]"),
				unknown.getMessage());

		ProfileException serializer = assertThrows(ProfileException.class,
				() -> ProfileLoader.parse(card("spi-s", FakeCallTargetProvider.NAME, "protobuf", ""), null, "test"));
		assertTrue(serializer.getMessage().contains("target.serializer: \"protobuf\" is not one of [identity]"), serializer.getMessage());

		ProfileException http = assertThrows(ProfileException.class, () -> ProfileLoader.parse(
				card("spi-h", FakeCallTargetProvider.NAME, "identity", "  http: { url: \"http://127.0.0.1:1/echo\" }"), null, "test"));
		assertTrue(http.getMessage().contains("target.http: only applies to transport = http"), http.getMessage());
	}

	@Test
	void runCreatesThePluginTargetAndRecordsItsDescription(@TempDir Path dir) throws Exception {
		String id = "spi-run";
		Path cardFile = dir.resolve(id + ".yaml");
		Files.writeString(cardFile, card(id, FakeCallTargetProvider.NAME, "identity", ""), StandardCharsets.UTF_8);
		Path out = dir.resolve("out");
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = Bench.execute(new String[] { "run", "--profile", cardFile.toString(), "--direct", "127.0.0.1:1", "--out", out.toString(),
				"--io-threads", "3" }, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
		assertEquals(Bench.OK, code, stderr.toString(StandardCharsets.UTF_8));
		Path json;
		try (Stream<Path> files = Files.list(out)) {
			json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
		}
		Map<?, ?> result = (Map<?, ?>) Json.parse(Files.readString(json, StandardCharsets.UTF_8));
		assertEquals(FakeCallTargetProvider.NAME, MarkdownReport.path(result, "run", "target", "transport"));
		assertEquals(3, ((Number) MarkdownReport.path(result, "run", "target", "io_threads")).intValue(), "--io-threads reaches the provider");
		assertEquals(3, ((Number) MarkdownReport.path(result, "run", "harness_options", "io_threads")).intValue());
		assertEquals("n/a (fake-echo)", MarkdownReport.path(result, "run", "harness_options", "http_client"));
		assertEquals(FakeCallTargetProvider.NAME, MarkdownReport.path(result, "server_environment", "transport"));
		assertTrue(((Number) MarkdownReport.path(result, "summary", "official", "throughput_rps")).doubleValue() > 0);
		String md = Files.readString(Path.of(json.toString().replace(".json", ".md")), StandardCharsets.UTF_8);
		assertTrue(md.contains("in-process copy (test double)"), "the plugin's connection_model is shown in the card table");
		assertTrue(md.contains("| 序列化 / 提供者 | identity / 1 个 |"), md);
	}

	@Test
	void pluginCardsAreAddressedWithDirectOnly(@TempDir Path dir) throws Exception {
		String id = "spi-registry";
		Path cardFile = dir.resolve(id + ".yaml");
		Files.writeString(cardFile, card(id, FakeCallTargetProvider.NAME, "identity", ""), StandardCharsets.UTF_8);
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
		PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", cardFile.toString(), "--registry", "zk://127.0.0.1:1" }, out, err));
		assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("are addressed with --direct host:port"), stderr.toString(StandardCharsets.UTF_8));
		assertEquals(Bench.ERROR, Bench.execute(new String[] { "run", "--profile", cardFile.toString(), "--direct", "127.0.0.1:1",
				"--io-busy-poll", "on" }, out, err), "--io-busy-poll is an http-client option");
	}

	@Test
	void serverWithTransportDelegatesToTheProvidersLauncher() {
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
		PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
		assertEquals(Bench.OK, Bench.execute(new String[] { "server", "--transport", FakeCallTargetProvider.NAME, "--port", "7", "--executor",
				"direct" }, out, err), stderr.toString(StandardCharsets.UTF_8));
		assertEquals(List.of("--port", "7", "--executor", "direct"), FakeCallTargetProvider.LAST_SERVER_ARGS.get(),
				"everything except --transport is handed over unchanged");
		assertTrue(stdout.toString(StandardCharsets.UTF_8).startsWith("bench-server ready port=0"));

		assertEquals(Bench.ERROR, Bench.execute(new String[] { "server", "--transport", "grcp", "--port", "7" }, out, err));
		assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("available: [rpc, http, fake-echo]"), stderr.toString(StandardCharsets.UTF_8));
	}

	@Test
	void benchCallExceptionsCarryTheirOwnErrorBucket() {
		assertEquals("grpc_status:UNAVAILABLE",
				ErrorClassifier.classify(new BenchCallException("grpc_status:UNAVAILABLE", "connection refused", null)));
		assertEquals(ErrorClassifier.TIMEOUT, ErrorClassifier.classify(BenchCallException.timeout("deadline", null)));
		BenchCallException mismatch = BenchCallException.payloadMismatch("differs");
		assertEquals(ErrorClassifier.PAYLOAD_MISMATCH, ErrorClassifier.classify(mismatch));
		assertInstanceOf(ErrorClassifier.PayloadMismatchException.class, mismatch, "pre-run verification aborts on it");
		assertEquals("grpc_status:INTERNAL", ErrorClassifier.classify(
				new java.util.concurrent.ExecutionException(new BenchCallException("grpc_status:INTERNAL", "x", null))));
	}
}
