package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code transport: http}：闭环 2 秒冒烟（与命令行同一入口，目标是进程内的 HTTP 对照端点）；开环"没有空闲连接 → 排队"
 * 与在途上限；超时由扫描任务判定并归为 timeout。
 */
class HttpCallTargetTest {

	private static String card(String id, String model, String loadLine, String url, String httpExtra, String duration, String warmup,
			String window, String timeout, int size) {
		return """
				profile: %s
				question: HTTP 目标的测试卡
				target:
				  transport: http
				  service: BenchService#echo
				  payload: { kind: bytes, size: %d }
				  http: { url: "%s"%s }
				load:
				  model: %s
				  %s
				  duration: %s
				  warmup: %s
				  steady_window: %s
				  rounds: 1
				  timeout: %s
				isolation:
				  client_process: same_jvm
				  server_process: same_jvm
				  same_host: true
				  registry: none (in-process test)
				measure:
				  histogram: { significant_digits: 3, max: 10s }
				  report: [throughput, p50, p99, errors_by_status]
				assert:
				  errors_max: 0
				""".formatted(id, size, url, httpExtra, model, loadLine, duration, warmup, window, timeout);
	}

	/** 验收：HTTP CallTarget 闭环 2s 冒烟——经 {@link Bench#execute} 跑完整流水线，JSON / Markdown 都对。 */
	@Test
	void closedLoopTwoSecondSmokeThroughTheCommandLine(@TempDir Path dir) throws Exception {
		try (BenchServer rpc = BenchServer.start(0, "127.0.0.1", null, "protobuf");
				BenchHttpServer http = BenchHttpServer.start(0, withHttpMarker(rpc.environment()))) {
			String id = "it-http-closed";
			Path cardFile = dir.resolve(id + ".yaml");
			Files.writeString(cardFile, card(id, "closed", "concurrency: 4", "http://127.0.0.1:" + http.port() + "/echo", "", "2s",
					"500ms", "[500ms, 2s]", "1s", 1024), StandardCharsets.UTF_8);
			Path out = dir.resolve("out");
			ByteArrayOutputStream stdout = new ByteArrayOutputStream();
			ByteArrayOutputStream stderr = new ByteArrayOutputStream();
			int code = Bench.execute(new String[] { "run", "--profile", cardFile.toString(), "--out", out.toString(), "--label", "it" },
					new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
			String log = stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8);
			assertEquals(Bench.OK, code, log);

			Path jsonFile;
			try (Stream<Path> files = Files.list(out)) {
				jsonFile = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
			}
			Map<?, ?> json = (Map<?, ?>) Json.parse(Files.readString(jsonFile, StandardCharsets.UTF_8));
			assertEquals("http", MarkdownReport.path(json, "profile", "parsed", "target", "transport"));
			assertEquals("http://127.0.0.1:" + http.port() + "/echo", MarkdownReport.path(json, "profile", "parsed", "target", "http", "url"));
			assertEquals("http", MarkdownReport.path(json, "run", "target", "mode"));
			assertEquals(4, ((Number) MarkdownReport.path(json, "run", "target", "connections")).intValue(),
					"closed loop: one connection per virtual user");
			assertEquals("http", MarkdownReport.path(json, "server_environment", "endpoint"), "server environment via GET /environment");
			assertEquals("false", MarkdownReport.path(json, "server_environment", "metrics_enabled"));
			Map<?, ?> round = (Map<?, ?>) ((List<?>) json.get("rounds")).get(0);
			assertTrue(((Number) MarkdownReport.path(round, "window", "successes")).longValue() > 100, "window successes: " + round);
			assertEquals(0L, ((Number) MarkdownReport.path(round, "window", "errors")).longValue());
			assertNotNull(MarkdownReport.path(round, "latency_ms", "p99"));
			assertEquals(Boolean.TRUE, MarkdownReport.path(json, "validity", "passed"));

			String md = Files.readString(out.resolve(jsonFile.getFileName().toString().replace(".json", ".md")), StandardCharsets.UTF_8);
			assertTrue(md.contains("| 序列化 / 提供者 | —（HTTP 请求体就是原始字节）/ 1 个 URL |"), md);
			assertTrue(md.contains("4 条 keep-alive 连接、每条同一时刻 1 个在途请求"), md);
			assertTrue(md.contains("HTTP 客户端 netty · HTTP IO 线程 "), "the harness options row names the HTTP client: " + md);
		}
	}

	/** 口径表"harness 选项"一行单列 HTTP 客户端：http 开环卡缺省是阻塞客户端（没有 IO 线程，不列 IO 线程数）。 */
	@Test
	void harnessOptionsRowNamesTheBlockingClient() {
		Profile profile = openCard(1, "1s");
		Map<String, Object> options = HarnessOptions.DEFAULTS.toJson(profile);
		Map<String, Object> json = new java.util.LinkedHashMap<>();
		json.put("profile", Map.of("id", "x", "parsed", profile.toJson()));
		json.put("run", Map.of("target", Map.of("address", "u", "connections", 1, "connection_model", "m"), "harness_options", options,
				"pre_warmup", "off"));
		json.put("environment", Map.of());
		json.put("rounds", List.of());
		json.put("summary", Map.of());
		json.put("validity", Map.of("statements", List.of()));
		String md = MarkdownReport.render((Map<?, ?>) Json.parse(Json.write(json)), "x.json");
		assertTrue(md.contains("| harness 选项 | 开环执行者 pool-platform（1 个平台线程工作者，有界 FIFO 队列） · HTTP 客户端 blocking · 调度线程绑核 off"), md);
	}

	private static Map<String, String> withHttpMarker(Map<String, String> environment) {
		Map<String, String> copy = new java.util.LinkedHashMap<>(environment);
		copy.put("endpoint", "http");
		copy.put("metrics_enabled", "false");
		return copy;
	}

	private static Profile openCard(int port, String timeout) {
		return ProfileLoader.parse(card("it-http-open", "open", "rate: 100", "http://127.0.0.1:" + port + "/echo", ", connections: 1", "2s",
				"500ms", "[500ms, 2s]", timeout, 16), null, "test");
	}

	private static CompletableFuture<long[]> callAsync(CallTarget target) {
		return CompletableFuture.supplyAsync(() -> {
			long start = System.nanoTime();
			try {
				target.call();
			} catch (Exception e) {
				throw new java.util.concurrent.CompletionException(e);
			}
			return new long[] { start, System.nanoTime() };
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
	}

	private static void awaitReceived(ScriptedHttpServer server, int count) throws InterruptedException {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (server.received() < count && System.nanoTime() < deadline) {
			Thread.sleep(2);
		}
		assertTrue(server.received() >= count, "server received " + server.received() + " < " + count);
	}

	/** {@code --io-threads 1 --io-busy-poll on}：忙轮询的事件循环照样完成写出与收包（调用方提交写出时不唤醒它）。 */
	@Test
	void busyPollingEventLoopCompletesCalls() throws Exception {
		try (BenchServer rpc = BenchServer.start(0, "127.0.0.1", null, "protobuf");
				BenchHttpServer http = BenchHttpServer.start(0, withHttpMarker(rpc.environment()));
				HttpCallTarget target = HttpCallTarget.connect(openCard(http.port(), "2s"), 1, true)) {
			assertEquals(Boolean.TRUE, target.describe().get("io_busy_poll"));
			assertEquals(1, target.describe().get("io_threads"));
			target.verify();
			try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
				List<java.util.concurrent.Future<?>> calls = new java.util.ArrayList<>();
				for (int i = 0; i < 200; i++) {
					calls.add(callers.submit(() -> {
						target.call();
						return null;
					}));
				}
				for (java.util.concurrent.Future<?> call : calls) {
					call.get(10, java.util.concurrent.TimeUnit.SECONDS);
				}
			}
		}
	}

	/** {@code --http-client blocking}：阻塞 Socket 客户端完成回显校验、并发调用（排队等唯一的连接）与取服务端环境。 */
	@Test
	void blockingClientCompletesCallsAndReadsTheServerEnvironment() throws Exception {
		try (BenchServer rpc = BenchServer.start(0, "127.0.0.1", null, "protobuf");
				BenchHttpServer http = BenchHttpServer.start(0, withHttpMarker(rpc.environment()));
				BlockingHttpCallTarget target = BlockingHttpCallTarget.connect(openCard(http.port(), "2s"))) {
			assertEquals("blocking java.net.Socket per connection (TCP_NODELAY), keep-alive, no pipelining; the calling thread writes and reads",
					target.describe().get("http_client"));
			target.verify();
			assertEquals("http", target.serverEnvironment().get("endpoint"));
			assertTrue(!target.serverStats().isEmpty(), "GET /stats through a temporary connection");
			try (var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
				List<java.util.concurrent.Future<?>> calls = new java.util.ArrayList<>();
				for (int i = 0; i < 200; i++) {
					calls.add(callers.submit(() -> {
						target.call();
						return null;
					}));
				}
				for (java.util.concurrent.Future<?> call : calls) {
					call.get(10, java.util.concurrent.TimeUnit.SECONDS);
				}
			}
		}
	}

	/** 阻塞客户端的超时：卡住的目标在 load.timeout 后以 TimeoutException 结束（按 SO_TIMEOUT 判定），连接关掉、下次重连。 */
	@Test
	void blockingClientTimesOutAStuckCall() throws Exception {
		try (ScriptedHttpServer server = new ScriptedHttpServer(HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION, -1);
				BlockingHttpCallTarget target = BlockingHttpCallTarget.connect(openCard(server.port(), "500ms"))) {
			assertEquals(HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION, server.answered(), "pre-warm-up is answered");
			long start = System.nanoTime();
			Throwable error = assertThrows(TimeoutException.class, target::call);
			assertEquals(ErrorClassifier.TIMEOUT, ErrorClassifier.classify(error));
			long elapsed = (System.nanoTime() - start) / 1_000_000;
			assertTrue(elapsed >= 400 && elapsed < 2000, "timeout 500ms, took " + elapsed + "ms");
		}
	}

	/**
	 * 开环排队语义：只有一条连接、每个响应要 150ms 时，紧挨着的第二个请求不丢弃，而是排队等那条连接，
	 * 拿到后立即发出；它的延迟 ≥ 排队时长（+ 自己的 150ms）。
	 */
	@Test
	void openLoopQueuesForTheNextIdleConnectionInsteadOfDropping() throws Exception {
		int warm = HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION;
		try (ScriptedHttpServer server = new ScriptedHttpServer(warm, 150)) {
			try (HttpCallTarget target = HttpCallTarget.connect(openCard(server.port(), "2s"))) {
				assertEquals(warm, server.answered(), "pre-warm-up is answered immediately");
				CompletableFuture<long[]> first = callAsync(target);
				awaitReceived(server, warm + 1); // 第一条已经占住唯一的连接
				CompletableFuture<long[]> second = callAsync(target);
				long[] a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
				long[] b = second.get(10, java.util.concurrent.TimeUnit.SECONDS); // 没有异常 = 没被丢弃、也没超时
				long queuedNanos = a[1] - b[0];
				long secondNanos = b[1] - b[0];
				assertTrue(queuedNanos > 0, "the second call started while the first still held the connection");
				assertTrue(secondNanos >= queuedNanos, "latency " + secondNanos / 1e6 + "ms >= queueing " + queuedNanos / 1e6 + "ms");
				assertTrue(secondNanos >= queuedNanos + 140_000_000L, "queueing plus its own 150ms response: " + secondNanos / 1e6 + "ms");
				assertEquals(warm + 2, server.answered());
			}
		}
	}

	/** 卡住的目标：占着连接的调用在 load.timeout 后由扫描任务以 TimeoutException 结束；排队等连接的调用同样按 timeout 失败。 */
	@Test
	void aStuckCallAndACallQueuedBehindItBothTimeOut() throws Exception {
		try (ScriptedHttpServer server = new ScriptedHttpServer(HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION, -1)) {
			try (HttpCallTarget target = HttpCallTarget.connect(openCard(server.port(), "500ms"))) {
				CompletableFuture<long[]> stuck = callAsync(target);
				awaitReceived(server, HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION + 1);
				CompletableFuture<long[]> queued = callAsync(target);
				for (CompletableFuture<long[]> call : List.of(stuck, queued)) {
					long start = System.nanoTime();
					Throwable error = assertThrows(java.util.concurrent.ExecutionException.class, () -> call.get(5, java.util.concurrent.TimeUnit.SECONDS))
							.getCause();
					assertInstanceOf(TimeoutException.class, error);
					assertEquals(ErrorClassifier.TIMEOUT, ErrorClassifier.classify(error));
					assertTrue((System.nanoTime() - start) / 1_000_000 < 2000, "timeout 500ms (+ sweep interval)");
				}
			}
		}
	}

	/**
	 * 在途上限：排队不改变驱动的上限——在途（排队 + 已发出）达到 rate × timeout × 2 时才 backlog_dropped。
	 * 用一个无视超时、每次占 300ms 的替身目标：rate 100、timeout 10ms → 上限 2，必然触顶。
	 */
	@Test
	void onlyInFlightAboveTheCapIsBacklogDropped() {
		Profile profile = ProfileLoader.parse(card("it-cap", "open", "rate: 100", "http://127.0.0.1:1/echo", ", connections: 1", "1s",
				"400ms", "[400ms, 1s]", "10ms", 16), null, "test");
		assertEquals(2, profile.load().inflightCap());
		CallTarget slow = new CallTarget() {
			@Override
			public void call() throws Exception {
				Thread.sleep(300);
			}

			@Override
			public void verify() {
			}

			@Override
			public Map<String, String> serverEnvironment() {
				return Map.of();
			}

			@Override
			public Map<String, Object> describe() {
				return Map.of();
			}

			@Override
			public void close() {
			}
		};
		Map<String, Object> round = LoadDriver.runRound(1, profile, slow).toJson();
		assertTrue(((Number) round.get("backlog_dropped")).longValue() > 0, "in-flight above the cap is dropped: " + round.get("backlog_dropped"));
		assertTrue(((Number) MarkdownReport.path(round, "scheduler", "max_inflight")).longValue() <= 2, "never more than the cap in flight");
		assertEquals(List.of("backlog_dropped: 在途调用达到上限 rate × timeout × 2，压测端已跟不上计划"), round.get("invalid_reasons"));
	}

	@Test
	void httpErrorsAreBucketedByKind() {
		assertEquals("http_status:503", ErrorClassifier.classify(new ErrorClassifier.HttpStatusException(503)));
		assertEquals(ErrorClassifier.TIMEOUT, ErrorClassifier.classify(new java.util.concurrent.ExecutionException(new TimeoutException())));
	}

	/**
	 * 最小的 HTTP/1.1 服务端：前 {@code fastRequests} 个请求立即回显；之后每个请求延迟 {@code slowDelayMs} 回显，
	 * {@code slowDelayMs < 0} 时只收不回（模拟卡住的目标）。每条连接串行处理（与 keep-alive、无 pipelining 的客户端一致）。
	 */
	static final class ScriptedHttpServer implements AutoCloseable {
		private final ServerSocket socket;
		private final int fastRequests;
		private final long slowDelayMs;
		private final AtomicInteger received = new AtomicInteger();
		private final AtomicInteger answered = new AtomicInteger();
		private final Thread acceptor;

		ScriptedHttpServer(int fastRequests, long slowDelayMs) throws Exception {
			this.socket = new ServerSocket(0);
			this.fastRequests = fastRequests;
			this.slowDelayMs = slowDelayMs;
			this.acceptor = Thread.ofVirtual().start(this::acceptLoop);
		}

		int port() {
			return socket.getLocalPort();
		}

		int received() {
			return received.get();
		}

		int answered() {
			return answered.get();
		}

		private void acceptLoop() {
			while (!socket.isClosed()) {
				try {
					Socket client = socket.accept();
					Thread.ofVirtual().start(() -> serve(client));
				} catch (Exception e) {
					return;
				}
			}
		}

		private void serve(Socket client) {
			try (client; InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream()) {
				while (true) {
					String head = readHead(in);
					if (head == null) {
						return;
					}
					int length = 0;
					for (String line : head.split("\r\n")) {
						if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
							length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
						}
					}
					byte[] body = in.readNBytes(length);
					int n = received.incrementAndGet();
					if (n > fastRequests) {
						if (slowDelayMs < 0) {
							continue; // 只收不回
						}
						Thread.sleep(slowDelayMs);
					}
					answered.incrementAndGet();
					out.write(("HTTP/1.1 200 OK\r\ncontent-length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
					out.write(body);
					out.flush();
				}
			} catch (Exception e) {
				// 连接被关闭
			}
		}

		private static String readHead(InputStream in) throws Exception {
			ByteArrayOutputStream head = new ByteArrayOutputStream();
			int matched = 0;
			int b;
			while ((b = in.read()) >= 0) {
				head.write(b);
				matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : 0;
				if (matched == 4) {
					return head.toString(StandardCharsets.US_ASCII);
				}
			}
			return null;
		}

		@Override
		public void close() throws Exception {
			socket.close();
			acceptor.join(2000);
		}
	}
}
