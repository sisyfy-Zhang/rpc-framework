package com.feynman.rpc.bench.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.feynman.rpc.bench.Bench;
import com.feynman.rpc.bench.Profile;

/**
 * 整条流水线：harness 的 {@code run} 经 ServiceLoader 找到 {@code transport: grpc} 的提供者，压 {@link GrpcBenchServer}，
 * 写出 JSON / Markdown；{@code server --transport grpc} 把参数交给 {@link GrpcBenchServer}；仓库里的 gRPC 对比卡按提供者校验通过。
 */
class GrpcEndToEndTest {

	@Test
	void harnessRunsAGrpcCardEndToEnd(@TempDir Path dir) throws Exception {
		String id = "grpc-e2e";
		Path card = dir.resolve(id + ".yaml");
		Files.writeString(card, GrpcCallTargetTest.card(id, 1024, "1s"), StandardCharsets.UTF_8);
		Path out = dir.resolve("out");
		try (GrpcBenchServer server = GrpcBenchServer.start(0, 0, GrpcBenchServer.EXECUTOR_GRPC_DEFAULT)) {
			ByteArrayOutputStream stdout = new ByteArrayOutputStream();
			ByteArrayOutputStream stderr = new ByteArrayOutputStream();
			int code = Bench.execute(new String[] { "run", "--profile", card.toString(), "--direct", "127.0.0.1:" + server.port(), "--out",
					out.toString(), "--label", "it", "--io-threads", "2" }, new PrintStream(stdout, true, StandardCharsets.UTF_8),
					new PrintStream(stderr, true, StandardCharsets.UTF_8));
			System.out.println(stdout.toString(StandardCharsets.UTF_8));
			assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
		}
		Path json;
		try (Stream<Path> files = Files.list(out)) {
			json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
		}
		String text = Files.readString(json, StandardCharsets.UTF_8);
		assertTrue(text.contains("\"transport\": \"grpc\""), "run.target.transport");
		// 服务端环境经 gRPC 取回，执行器同时记进 run.target（两处）
		assertEquals(2, text.split("\"server_executor\": \"grpc default", -1).length - 1, "server_environment and run.target");
		assertTrue(text.contains("\"io_threads\": 2"), "--io-threads reached the gRPC client");
		assertTrue(text.contains("\"errors_by_type\": {}"), "no errors in the window");
		String md = Files.readString(Path.of(json.toString().replace(".json", ".md")), StandardCharsets.UTF_8);
		assertTrue(md.contains("1 ManagedChannel = 1 HTTP/2 connection"), "connection model in the card table");
		assertTrue(md.contains("| 序列化 / 提供者 | protobuf / 1 个 |"), md);
	}

	@Test
	void benchServerHandsTheArgumentsToTheGrpcLauncher() {
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		int code = Bench.execute(new String[] { "server", "--transport", "grpc", "--port", "0", "--executor", "bogus" },
				new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
		assertEquals(1, code);
		assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("--executor must be direct, fixed or grpc-default"),
				stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void repositoryGrpcCardsLoadThroughTheProvider() throws Exception {
		Path profiles = Path.of("..", "bench", "profiles");
		for (String id : List.of("compare-closed64-grpc-v1", "compare-open10k-grpc-v1")) {
			Profile profile = Profile.load(profiles.resolve(id + ".yaml"));
			assertEquals("grpc", profile.target().transport());
			assertEquals("protobuf", profile.target().serializer());
			assertEquals(1024, profile.target().payloadSize());
			Profile rpc = Profile.load(profiles.resolve(id.replace("-grpc-", "-rpc-") + ".yaml"));
			assertEquals(rpc.load(), profile.load(), id + ": same load as the rpc card");
			assertEquals(rpc.isolation(), profile.isolation());
			assertEquals(rpc.measure(), profile.measure());
			assertEquals(rpc.assertions(), profile.assertions());
		}
	}
}
