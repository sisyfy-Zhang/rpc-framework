package com.feynman.rpc.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code bench server --http-port} 的对照端点：回显往返、ping、环境、错误路径与 keep-alive。 */
class BenchHttpServerTest {

	private static BenchHttpServer server;
	private static HttpClient client;

	@BeforeAll
	static void start() throws Exception {
		server = BenchHttpServer.start(0, Map.of("pid", "4242", "metrics_enabled", "false", "endpoint", "http"));
		client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterAll
	static void stop() {
		server.close();
	}

	private static URI uri(String path) {
		return URI.create("http://127.0.0.1:" + server.port() + path);
	}

	@Test
	void postEchoReturnsTheBodyUnchanged() throws Exception {
		for (int size : new int[] { 1, 1024, 1024 * 1024 }) {
			byte[] body = Payloads.bytes(size);
			HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri(BenchHttpServer.ECHO_PATH))
					.POST(HttpRequest.BodyPublishers.ofByteArray(body)).header("Content-Type", "application/octet-stream").build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertEquals(200, response.statusCode());
			assertArrayEquals(body, response.body(), "echo of " + size + " bytes");
			assertEquals("application/octet-stream", response.headers().firstValue("content-type").orElse(null));
			assertEquals(String.valueOf(size), response.headers().firstValue("content-length").orElse(null));
		}
	}

	/** GET /counters（故障注入时间线的服务端计数通道）：缺省 HTTP 端点返回空对象；rpc bench server 接上的是 ServerCounters。 */
	@Test
	void countersPathAnswersOnTheIoThread() throws Exception {
		HttpResponse<String> empty = client.send(HttpRequest.newBuilder(uri(BenchHttpServer.COUNTERS_PATH)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, empty.statusCode());
		assertEquals("{}", empty.body().trim());
		try (BenchServer rpc = BenchServer.start(0, "127.0.0.1", null, "protobuf");
				BenchHttpServer http = BenchHttpServer.start(0, rpc.environment(), rpc.statsSupplier(), rpc.countersSupplier())) {
			HttpResponse<String> counters = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port()
					+ BenchHttpServer.COUNTERS_PATH)).GET().build(), HttpResponse.BodyHandlers.ofString());
			Map<?, ?> json = (Map<?, ?>) Json.parse(counters.body());
			assertEquals("0", json.get(ServerCounters.EXECUTOR_REJECTIONS));
			assertEquals("0", json.get(ServerCounters.SHUTDOWN_REJECTIONS));
			assertTrue(json.get(ServerCounters.QUEUE_WAIT) instanceof String);
			HttpResponse<String> stats = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port()
					+ BenchHttpServer.STATS_PATH)).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertTrue(((Map<?, ?>) Json.parse(stats.body())).containsKey(BackpressureStats.AUTO_READ_PAUSES_KEY),
					"the rpc server's /stats carries the same keys as BenchService.stats()");
		}
	}

	@Test
	void pingEnvironmentAndErrorPaths() throws Exception {
		HttpResponse<String> ping = client.send(HttpRequest.newBuilder(uri(BenchHttpServer.PING_PATH)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, ping.statusCode());
		assertEquals("ok", ping.body());

		HttpResponse<String> env = client.send(HttpRequest.newBuilder(uri(BenchHttpServer.ENVIRONMENT_PATH)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, env.statusCode());
		Map<?, ?> parsed = (Map<?, ?>) Json.parse(env.body());
		assertEquals("4242", parsed.get("pid"));
		assertEquals("http", parsed.get("endpoint"));

		HttpResponse<String> stats = client.send(HttpRequest.newBuilder(uri(BenchHttpServer.STATS_PATH)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, stats.statusCode());
		Map<String, String> flat = new java.util.LinkedHashMap<>();
		((Map<?, ?>) Json.parse(stats.body())).forEach((k, v) -> flat.put(String.valueOf(k), String.valueOf(v)));
		GcSampler.Snapshot snapshot = GcSampler.Snapshot.fromStrings(flat);
		assertFalse(snapshot.collectors().isEmpty(), stats.body());
		assertTrue(snapshot.heapUsedBytes() > 0, stats.body());
		assertEquals(405, client.send(HttpRequest.newBuilder(uri(BenchHttpServer.STATS_PATH))
				.POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());

		assertEquals(404, client.send(HttpRequest.newBuilder(uri("/nope")).GET().build(), HttpResponse.BodyHandlers.discarding())
				.statusCode());
		assertEquals(405, client.send(HttpRequest.newBuilder(uri(BenchHttpServer.ECHO_PATH)).GET().build(),
				HttpResponse.BodyHandlers.discarding()).statusCode());
		assertEquals(405, client.send(HttpRequest.newBuilder(uri(BenchHttpServer.PING_PATH))
				.POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
	}

	/** keep-alive：同一条连接上连发两个请求，两个回包都从这条连接回来；带 Connection: close 时回包后关闭。 */
	@Test
	void keepAliveServesSeveralRequestsOnOneConnectionAndHonoursConnectionClose() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", server.port())) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			String request = "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\n";
			out.write((request + "first" + request + "again").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			String received = readUntil(socket.getInputStream(), text -> text.contains("first") && text.endsWith("again"));
			assertEquals(2, received.split("HTTP/1.1 200 OK", -1).length - 1, received);

			out.write("POST /echo HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: 4\r\n\r\nlast".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			String tail = readToEof(socket.getInputStream());
			assertTrue(tail.contains("connection: close") && tail.endsWith("last"), tail);
		}
	}

	private static String readUntil(InputStream in, java.util.function.Predicate<String> done) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		while (!done.test(buffer.toString(StandardCharsets.US_ASCII))) {
			int n = in.read(chunk);
			if (n < 0) {
				break;
			}
			buffer.write(chunk, 0, n);
		}
		return buffer.toString(StandardCharsets.US_ASCII);
	}

	private static String readToEof(InputStream in) throws Exception {
		return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
	}
}
