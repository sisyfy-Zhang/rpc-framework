package com.feynman.rpc.bench.dubbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.StatusRpcException;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.feynman.rpc.bench.Bench;
import com.feynman.rpc.bench.BenchCallException;
import com.feynman.rpc.bench.CallTarget;
import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.Profile;

/**
 * Dubbo 对比对象：回显往返（逐字节）、Environment / Stats、错误分桶、序列化核对、整条 {@code run} 流水线与仓库里的 Dubbo 卡。
 */
class DubboCallTargetTest {

	/** Dubbo 3.3 Java 接口模式 triple 的缺省序列化（与仓库卡片一致）。 */
	static final String SERIALIZER = "hessian2";

	static String card(String id, String serializer, String timeout) {
		return """
				profile: %s
				question: Dubbo 对比对象的测试卡
				target:
				  transport: dubbo
				  service: BenchService#echo
				  payload: { kind: bytes, size: 1024 }
				  serializer: %s
				  providers: 1
				load:
				  model: closed
				  concurrency: 4
				  duration: 1s
				  warmup: 200ms
				  steady_window: [200ms, 1s]
				  rounds: 1
				  timeout: %s
				isolation:
				  client_process: same_jvm
				  server_process: same_jvm
				  same_host: true
				  registry: none (--direct)
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p99, errors_by_status]
				""".formatted(id, serializer, timeout);
	}

	static Profile profile(Path dir, String id, String serializer, String timeout) throws Exception {
		Path file = dir.resolve(id + ".yaml");
		Files.writeString(file, card(id, serializer, timeout), StandardCharsets.UTF_8);
		return Profile.load(file);
	}

	static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	private static final HarnessOptions DEFAULTS = new HarnessOptions(null, null, null, null, null, null, null);

	private static CallTarget connect(Profile profile, int port) {
		return new DubboCallTargetProvider().create(profile, new CallTarget.Endpoint(CallTarget.Endpoint.DIRECT, "127.0.0.1:" + port), DEFAULTS);
	}

	@Test
	void echoRoundTripsTheExactBytesAndExposesTheServerMetadata(@TempDir Path dir) throws Exception {
		Profile profile = profile(dir, "dubbo-echo", SERIALIZER, "1s");
		try (DubboBenchServer server = DubboBenchServer.start(freePort(), 2); CallTarget target = connect(profile, server.port())) {
			target.verify();
			for (int i = 0; i < 200; i++) {
				target.call();
			}
			Map<String, String> env = target.serverEnvironment();
			assertEquals("dubbo", env.get("transport"));
			assertEquals("2", env.get("server_io_threads"));
			assertTrue(env.containsKey("jvm_args") && env.containsKey("cpus_allowed"), env.keySet().toString());
			Map<String, String> stats = target.serverStats();
			assertTrue(stats.containsKey("collectors") && stats.containsKey("gc_pause_ms"), stats.toString());
			Map<String, Object> d = target.describe();
			assertEquals("dubbo", d.get("transport"));
			assertTrue(String.valueOf(d.get("serializer")).startsWith(SERIALIZER), String.valueOf(d.get("serializer")));
			assertEquals(0, d.get("retries"));
		}
	}

	/** target.connections: 2 → 两个独立的客户端实例（两条连接），轮询取用，全部调用成功。 */
	@Test
	void connectionsCreatesIndependentClientInstances(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("dubbo-c2.yaml");
		Files.writeString(file, card("dubbo-c2", SERIALIZER, "1s").replace("  providers: 1\n", "  providers: 1\n  connections: 2\n"),
				StandardCharsets.UTF_8);
		Profile profile = Profile.load(file);
		try (DubboBenchServer server = DubboBenchServer.start(freePort(), 2); CallTarget target = connect(profile, server.port())) {
			target.verify();
			for (int i = 0; i < 100; i++) {
				target.call();
			}
			assertEquals(2, target.describe().get("connections_per_node"));
			assertTrue(String.valueOf(target.describe().get("connection_model")).startsWith("2 independent Dubbo client instances"));
			assertEquals("target.connections", target.describe().get("connections_source"));
		}
	}

	@Test
	void aCardWhoseSerializerDiffersFromDubbosIsRejectedBeforeLoad(@TempDir Path dir) throws Exception {
		String other = SERIALIZER.equals("hessian2") ? "fastjson2" : "hessian2";
		Profile profile = profile(dir, "dubbo-wrong-serializer", other, "1s");
		try (DubboBenchServer server = DubboBenchServer.start(freePort(), 1)) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> connect(profile, server.port()));
			assertTrue(e.getMessage().contains("uses \"" + SERIALIZER + "\""), e.getMessage());
		}
	}

	@Test
	void ioThreadsIsRejectedBecauseTheDubboClientHasNoSetting(@TempDir Path dir) throws Exception {
		Profile profile = profile(dir, "dubbo-io", SERIALIZER, "1s");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new DubboCallTargetProvider().create(profile,
				new CallTarget.Endpoint(CallTarget.Endpoint.DIRECT, "127.0.0.1:1"), new HarnessOptions(null, null, null, 2, null, null, null)));
		assertTrue(e.getMessage().contains("--io-threads does not apply to transport: dubbo"), e.getMessage());
	}

	@Test
	void errorsAreBucketed() {
		assertEquals("timeout", DubboCallTarget.classify(new RpcException(RpcException.TIMEOUT_EXCEPTION, "slow")).bucket());
		assertEquals("dubbo_rpc:NETWORK", DubboCallTarget.classify(new RpcException(RpcException.NETWORK_EXCEPTION, "down")).bucket());
		assertEquals("dubbo_rpc:SERIALIZATION", DubboCallTarget.classify(new RpcException(RpcException.SERIALIZATION_EXCEPTION, "x")).bucket());
		assertEquals("tri_status:UNAVAILABLE",
				DubboCallTarget.classify(new StatusRpcException(TriRpcStatus.UNAVAILABLE.withDescription("x"))).bucket());
		assertEquals("timeout", DubboCallTarget.classify(new StatusRpcException(TriRpcStatus.DEADLINE_EXCEEDED)).bucket());
	}

	@Test
	void callsAgainstAStoppedServerFailWithABucket(@TempDir Path dir) throws Exception {
		Profile profile = profile(dir, "dubbo-down", SERIALIZER, "2s");
		int port = freePort();
		try (DubboBenchServer server = DubboBenchServer.start(port, 1); CallTarget target = connect(profile, server.port())) {
			target.call();
			server.close();
			BenchCallException e = assertThrows(BenchCallException.class, target::call);
			assertTrue(e.bucket().startsWith("tri_status:") || e.bucket().startsWith("dubbo_rpc:") || e.bucket().equals("timeout"), e.bucket());
		}
	}

	@Test
	void harnessRunsADubboCardEndToEnd(@TempDir Path dir) throws Exception {
		String id = "dubbo-e2e";
		Path cardFile = dir.resolve(id + ".yaml");
		Files.writeString(cardFile, card(id, SERIALIZER, "1s"), StandardCharsets.UTF_8);
		Path out = dir.resolve("out");
		try (DubboBenchServer server = DubboBenchServer.start(freePort(), 0)) {
			ByteArrayOutputStream stdout = new ByteArrayOutputStream();
			ByteArrayOutputStream stderr = new ByteArrayOutputStream();
			int code = Bench.execute(new String[] { "run", "--profile", cardFile.toString(), "--direct", "127.0.0.1:" + server.port(), "--out",
					out.toString(), "--label", "it" }, new PrintStream(stdout, true, StandardCharsets.UTF_8),
					new PrintStream(stderr, true, StandardCharsets.UTF_8));
			System.out.println(stdout.toString(StandardCharsets.UTF_8));
			assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
		}
		Path json;
		try (Stream<Path> files = Files.list(out)) {
			json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
		}
		String text = Files.readString(json, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"transport\": \"dubbo\""), text);
		assertTrue(text.contains("\"errors_by_type\": {}"), "no errors in the window");
	}

	@Test
	void serverCommandLineRejectsBadArguments() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		assertEquals(1, DubboBenchServer.run(new String[] { "--port", "1", "--registry", "zk://127.0.0.1:2181" }, out));
		assertEquals(1, DubboBenchServer.run(new String[] { "--io-threads", "2" }, out));
		assertEquals(1, DubboBenchServer.run(new String[] { "--port", "0" }, out), "Dubbo needs a concrete port");
		String text = bytes.toString(StandardCharsets.UTF_8);
		assertTrue(text.contains("--registry is not supported") && text.contains("--port is required"), text);
	}

	@Test
	void repositoryDubboCardsLoadThroughTheProvider() throws Exception {
		Path profiles = Path.of("..", "bench", "profiles");
		for (String id : List.of("compare-closed64-dubbo-v1", "compare-open10k-dubbo-v1")) {
			Profile profile = Profile.load(profiles.resolve(id + ".yaml"));
			assertEquals("dubbo", profile.target().transport());
			assertEquals(SERIALIZER, profile.target().serializer());
			Profile rpc = Profile.load(profiles.resolve(id.replace("-dubbo-", "-rpc-") + ".yaml"));
			assertEquals(rpc.load(), profile.load());
			assertEquals(rpc.isolation(), profile.isolation());
		}
	}
}
