package com.feynman.rpc.bench.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.feynman.rpc.bench.BenchCallException;
import com.feynman.rpc.bench.CallTarget;
import com.feynman.rpc.bench.CallTargetProvider;
import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.Profile;

import io.grpc.Status;

/**
 * gRPC 对比对象：回显往返（逐字节校验）、Environment / Stats 元数据、错误分桶、服务端的执行器与参数。
 */
class GrpcCallTargetTest {

	static String card(String id, int size, String timeout) {
		return """
				profile: %s
				question: gRPC 对比对象的测试卡
				target:
				  transport: grpc
				  service: BenchService#echo
				  payload: { kind: bytes, size: %d }
				  serializer: protobuf
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
				""".formatted(id, size, timeout);
	}

	static Profile profile(Path dir, String id, int size, String timeout) throws Exception {
		Path file = dir.resolve(id + ".yaml");
		Files.writeString(file, card(id, size, timeout), StandardCharsets.UTF_8);
		return Profile.load(file);
	}

	private static CallTarget connect(Profile profile, int port, Integer ioThreads) {
		CallTargetProvider provider = new GrpcCallTargetProvider();
		return provider.create(profile, endpoint("127.0.0.1:" + port), new HarnessOptions(null, null, null, ioThreads, null, null, null));
	}

	/** {@code --direct host:port}：Endpoint 的工厂方法是包内的，这里用公开的规范构造器。 */
	static CallTarget.Endpoint endpoint(String address) {
		return new CallTarget.Endpoint(CallTarget.Endpoint.DIRECT, address);
	}

	@Test
	void echoRoundTripsTheExactBytesAndExposesTheServerMetadata(@TempDir Path dir) throws Exception {
		Profile profile = profile(dir, "grpc-echo", 1024, "1s");
		try (GrpcBenchServer server = GrpcBenchServer.start(0, 2, GrpcBenchServer.EXECUTOR_DIRECT);
				CallTarget target = connect(profile, server.port(), 2)) {
			target.verify(); // 逐字节比对
			for (int i = 0; i < 200; i++) {
				target.call();
			}
			Map<String, String> env = target.serverEnvironment();
			assertEquals("grpc", env.get("transport"));
			assertEquals("2", env.get("server_io_threads"));
			assertTrue(env.get("server_executor").startsWith("directExecutor()"), env.get("server_executor"));
			assertTrue(env.containsKey("jvm_args") && env.containsKey("cpus_allowed") && env.containsKey("pid"),
					"same fields as the rpc bench server (ServerProbes.environment): " + env.keySet());
			assertEquals(server.environment(), env);
			Map<String, String> stats = target.serverStats();
			assertTrue(stats.containsKey("collectors") && stats.containsKey("gc_pause_ms") && stats.containsKey("heap_used_bytes"), stats.toString());

			Map<String, Object> d = target.describe();
			assertEquals("grpc", d.get("transport"));
			assertEquals(2, d.get("io_threads"));
			assertEquals(0, d.get("retries"));
			assertTrue(String.valueOf(d.get("connection_model")).startsWith("1 ManagedChannel = 1 HTTP/2 connection"));
			assertEquals(java.util.List.of("127.0.0.1:" + server.port()), d.get("providers"));
		}
	}

	/** target.connections: 2 → 两个 ManagedChannel（两条 HTTP/2 连接），调用轮询取用；缺省 1 个。 */
	@Test
	void connectionsCreatesThatManyChannels(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("grpc-c2.yaml");
		Files.writeString(file, card("grpc-c2", 64, "1s").replace("  providers: 1\n", "  providers: 1\n  connections: 2\n"), StandardCharsets.UTF_8);
		Profile two = Profile.load(file);
		try (GrpcBenchServer server = GrpcBenchServer.start(0, 2, GrpcBenchServer.EXECUTOR_DIRECT); CallTarget target = connect(two, server.port(), 2)) {
			target.verify();
			for (int i = 0; i < 100; i++) {
				target.call();
			}
			assertEquals(2, target.describe().get("connections_per_node"));
			assertTrue(String.valueOf(target.describe().get("connection_model")).startsWith("2 ManagedChannels"));
			assertEquals("target.connections", target.describe().get("connections_source"));
		}
		try (GrpcBenchServer server = GrpcBenchServer.start(0, 1, GrpcBenchServer.EXECUTOR_DIRECT);
				CallTarget target = connect(profile(dir, "grpc-c1", 64, "1s"), server.port(), 1)) {
			target.call();
			assertEquals(1, target.describe().get("connections_per_node"));
			assertEquals("default", target.describe().get("connections_source"));
		}
	}

	@Test
	void fixedAndGrpcDefaultExecutorsServeTheSameContract(@TempDir Path dir) throws Exception {
		Profile profile = profile(dir, "grpc-executors", 64, "1s");
		for (String kind : new String[] { GrpcBenchServer.EXECUTOR_FIXED, GrpcBenchServer.EXECUTOR_GRPC_DEFAULT }) {
			try (GrpcBenchServer server = GrpcBenchServer.start(0, 0, kind); CallTarget target = connect(profile, server.port(), null)) {
				target.verify();
				target.call();
				String executor = target.serverEnvironment().get("server_executor");
				assertTrue(kind.equals(GrpcBenchServer.EXECUTOR_FIXED) ? executor.startsWith("fixed pool (") : executor.startsWith("grpc default"),
						executor);
				assertEquals(HarnessOptions.nettyDefaultIoThreadsText(), target.describe().get("io_threads"));
			}
		}
		assertThrows(IllegalArgumentException.class, () -> GrpcBenchServer.start(0, 0, "virtual"));
		assertThrows(IllegalArgumentException.class, () -> GrpcBenchServer.start(0, -1, GrpcBenchServer.EXECUTOR_DIRECT));
	}

	@Test
	void errorsAreBucketedByStatusCode(@TempDir Path dir) throws Exception {
		// 超时：卡片时长的最小单位是 ms，这里把 load.timeout 换成 1µs——这样的 deadline 一定来不及
		Profile tiny = profile(dir, "grpc-deadline", 1024, "1ms");
		Profile tight = new Profile(tiny.id(), tiny.question(), tiny.target(),
				new Profile.Load(tiny.load().model(), tiny.load().concurrency(), tiny.load().rate(), tiny.load().duration(), tiny.load().warmup(),
						tiny.load().windowStart(), tiny.load().windowEnd(), tiny.load().rounds(), java.time.Duration.ofNanos(1_000)),
				tiny.isolation(), tiny.measure(), tiny.assertions(), tiny.rawYaml());
		try (GrpcBenchServer server = GrpcBenchServer.start(0, 1, GrpcBenchServer.EXECUTOR_DIRECT);
				CallTarget target = connect(tight, server.port(), 1)) {
			BenchCallException timeout = assertThrows(BenchCallException.class, target::call);
			assertEquals("timeout", timeout.bucket());
		}
		// 连不上：UNAVAILABLE
		int closedPort;
		try (GrpcBenchServer server = GrpcBenchServer.start(0, 1, GrpcBenchServer.EXECUTOR_DIRECT)) {
			closedPort = server.port();
		}
		try (CallTarget target = connect(profile(dir, "grpc-down", 16, "2s"), closedPort, 1)) {
			BenchCallException down = assertThrows(BenchCallException.class, target::call);
			assertEquals("grpc_status:UNAVAILABLE", down.bucket());
			assertThrows(BenchCallException.class, target::serverEnvironment, "metadata calls fail the same way (recorded as unknown)");
		}
		assertEquals("grpc_status:INTERNAL", GrpcCallTarget.classify(Status.INTERNAL.asRuntimeException()).bucket());
		assertEquals("grpc_status:RESOURCE_EXHAUSTED", GrpcCallTarget.classify(Status.RESOURCE_EXHAUSTED.asRuntimeException()).bucket());
		assertEquals("timeout", GrpcCallTarget.classify(Status.DEADLINE_EXCEEDED.asRuntimeException()).bucket());
	}

	@Test
	void onlyOneDirectProviderIsAccepted(@TempDir Path dir) throws Exception {
		Profile profile = profile(dir, "grpc-two", 16, "1s");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new GrpcCallTargetProvider().create(profile,
				endpoint("127.0.0.1:1,127.0.0.1:2"), new HarnessOptions(null, null, null, null, null, null, null)));
		assertTrue(e.getMessage().contains("exactly one provider"), e.getMessage());
	}

	@Test
	void serverCommandLineRejectsUnknownOptionsAndRegistries() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
		assertEquals(1, GrpcBenchServer.run(new String[] { "--port", "0", "--registry", "zk://127.0.0.1:2181" }, out));
		assertEquals(1, GrpcBenchServer.run(new String[] { "--io-threads", "2" }, out), "--port is required");
		assertEquals(1, GrpcBenchServer.run(new String[] { "--port", "0", "--executor", "bogus" }, out));
		assertEquals(1, GrpcBenchServer.run(new String[] { "--port" }, out));
		String text = bytes.toString(StandardCharsets.UTF_8);
		assertTrue(text.contains("--registry is not supported"), text);
		assertTrue(text.contains("--port is required"), text);
		assertInstanceOf(GrpcCallTargetProvider.class, java.util.ServiceLoader.load(CallTargetProvider.class).findFirst().orElseThrow());
	}
}
