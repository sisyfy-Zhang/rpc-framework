package com.feynman.rpc.bench;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code transport = http} 的另一种客户端（{@code --http-client blocking}）：每条 keep-alive 连接是一个阻塞的
 * {@link Socket}，调用方线程自己 {@code write} 请求、自己阻塞在 {@code read} 上等响应——没有事件循环，也就没有
 * "调用方 → IO 线程（写出）→ 调用方（完成）"两次跨线程交接；与 wrk 在自己的线程里收发同构。配平台线程工作者
 * （{@code --open-workers pool-platform}）时，响应到达由内核直接唤醒等在 {@code read} 上的线程。
 *
 * <p>只为交叉验证用：它让 harness 的驱动与计时（调度线程、工作者、记录器）在 HTTP 目标上与 wrk2 对比时，客户端适配器本身
 * 的开销尽量小。连接模型、在途语义与 {@link HttpCallTarget} 相同：连接池（公平信号量 + 空闲队列）、每条连接同一时刻一个在途请求、
 * 不做 pipelining、超时后关掉这条连接下次重连。超时用 {@link Socket#setSoTimeout} 判定（每次 read 的上限 = 本次调用剩余的
 * {@code load.timeout}），不另起扫描任务。
 *
 * <p>HTTP/1.1 响应只解析本 harness 的回显端点会用到的子集：状态行、头部里的 {@code Content-Length}（必须有，不支持 chunked）、
 * {@code Connection: close}。
 */
final class BlockingHttpCallTarget implements CallTarget {

	private final URI uri;
	private final String host;
	private final int port;
	private final String path;
	private final String hostHeader;
	private final byte[] payload;
	/** 压测热路径的整个请求（头 + 1KB 体）：构造时拼好，每次调用一次 write。 */
	private final byte[] echoRequest;
	private final long timeoutNanos;
	private final int poolSize;
	private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
	private final Semaphore permits;
	private final List<Connection> all = new ArrayList<>();
	private final Map<String, Object> description;

	private BlockingHttpCallTarget(Profile profile) {
		this.uri = URI.create(profile.target().httpUrl());
		this.host = uri.getHost();
		this.port = uri.getPort() > 0 ? uri.getPort() : 80;
		this.path = uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
		this.hostHeader = uri.getPort() > 0 ? host + ":" + port : host;
		this.payload = Payloads.bytes(profile.target().payloadSize());
		this.echoRequest = request("POST", path, payload);
		this.timeoutNanos = profile.load().timeout().toNanos();
		boolean open = !profile.load().closed();
		this.poolSize = open ? profile.target().httpConnections() : profile.load().concurrency();
		this.permits = new Semaphore(poolSize, true);
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("transport", "http");
		d.put("mode", CallTarget.Endpoint.HTTP);
		d.put("address", profile.target().httpUrl());
		d.put("providers", List.of(host + ":" + port));
		d.put("connections", poolSize);
		d.put("connection_model", open
				? "open: pool of target.http.connections; one in-flight request per connection; no idle connection at the scheduled time -> "
						+ "queue (FIFO) for the next idle one, latency still from the scheduled time; in-flight cap (queued + sent) = rate x timeout x 2"
				: "closed: one connection per virtual user (pool = load.concurrency); one in-flight request per connection");
		d.put("http_client", "blocking java.net.Socket per connection (TCP_NODELAY), keep-alive, no pipelining; the calling thread writes and reads");
		d.put("io_transport", "blocking socket (no event loop)");
		d.put("io_threads", 0);
		d.put("io_busy_poll", false);
		d.put("timeout_check", "Socket.setSoTimeout = remaining load.timeout per read");
		d.put("retries", 0);
		d.put("pre_warmup_calls_per_connection", HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION);
		d.put("call_style", "sync: the calling thread writes one HTTP/1.1 request and blocks reading its response");
		d.put("metrics_filter", false);
		this.description = d;
	}

	/** 建好全部连接、做完预热再返回（都不算进任何一轮）；任一步失败则关闭已建的并抛出。 */
	static BlockingHttpCallTarget connect(Profile profile) {
		BlockingHttpCallTarget target = new BlockingHttpCallTarget(profile);
		try {
			for (int i = 0; i < target.poolSize; i++) {
				Connection connection = target.new Connection();
				connection.open();
				target.all.add(connection);
			}
			target.preWarmUp();
			target.idle.addAll(target.all);
			return target;
		} catch (IOException | RuntimeException e) {
			target.close();
			throw new IllegalStateException("cannot connect to " + target.uri + ": " + e, e);
		}
	}

	private byte[] request(String method, String requestPath, byte[] body) {
		StringBuilder head = new StringBuilder();
		head.append(method).append(' ').append(requestPath).append(" HTTP/1.1\r\n");
		head.append("host: ").append(hostHeader).append("\r\n");
		if (body != null) {
			head.append("content-type: application/octet-stream\r\n");
		}
		head.append("content-length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
		byte[] headBytes = head.toString().getBytes(StandardCharsets.US_ASCII);
		if (body == null) {
			return headBytes;
		}
		byte[] all = Arrays.copyOf(headBytes, headBytes.length + body.length);
		System.arraycopy(body, 0, all, headBytes.length, body.length);
		return all;
	}

	/** 与 {@link HttpCallTarget} 相同：每条连接串行 {@value HttpCallTarget#PRE_WARMUP_CALLS_PER_CONNECTION} 次回显（各连接并行）。 */
	private void preWarmUp() {
		List<Future<?>> futures = new ArrayList<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Connection connection : all) {
				futures.add(executor.submit(() -> {
					for (int i = 0; i < HttpCallTarget.PRE_WARMUP_CALLS_PER_CONNECTION; i++) {
						HttpCallTarget.Response response = connection.exchange(echoRequest, HttpCallTarget.ENVIRONMENT_TIMEOUT.toNanos(), false);
						if (response.status() != 200 || response.length() != payload.length) {
							throw new IllegalStateException("pre-warm-up call to " + uri + " returned HTTP " + response.status() + " with "
									+ response.length() + " bytes (sent " + payload.length + ")");
						}
					}
					return null;
				}));
			}
		}
		for (Future<?> future : futures) {
			try {
				future.get();
			} catch (ExecutionException e) {
				throw new IllegalStateException("pre-warm-up against " + uri + " failed: " + e.getCause(), e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted during pre-warm-up", e);
			}
		}
	}

	private Connection acquire(long timeout) throws InterruptedException {
		if (!permits.tryAcquire(timeout, TimeUnit.NANOSECONDS)) {
			return null;
		}
		Connection connection = idle.poll();
		if (connection == null) {
			permits.release();
			throw new IllegalStateException("HTTP connection pool out of sync (permit without an idle connection)");
		}
		return connection;
	}

	private void release(Connection connection) {
		idle.offer(connection);
		permits.release();
	}

	@Override
	public void call() throws Exception {
		long start = System.nanoTime();
		Connection connection = acquire(timeoutNanos);
		if (connection == null) {
			throw new TimeoutException("no idle HTTP connection within " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms (all "
					+ poolSize + " connections busy)");
		}
		try {
			long remaining = timeoutNanos - (System.nanoTime() - start);
			if (remaining <= 0) {
				throw new TimeoutException("load.timeout spent waiting for an idle HTTP connection");
			}
			HttpCallTarget.Response response = connection.exchange(echoRequest, remaining, false);
			if (response.status() != 200) {
				throw new ErrorClassifier.HttpStatusException(response.status());
			}
			if (response.length() != payload.length) {
				throw new ErrorClassifier.PayloadMismatchException("echo returned " + response.length() + " bytes, sent " + payload.length);
			}
		} finally {
			release(connection);
		}
	}

	@Override
	public void verify() {
		Connection connection;
		try {
			connection = acquire(HttpCallTarget.ENVIRONMENT_TIMEOUT.toNanos());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for an HTTP connection", e);
		}
		if (connection == null) {
			throw new IllegalStateException("no idle HTTP connection for the verification call");
		}
		try {
			HttpCallTarget.Response response = connection.exchange(echoRequest, timeoutNanos, true);
			if (response.status() != 200) {
				throw new ErrorClassifier.HttpStatusException(response.status());
			}
			if (!Arrays.equals(payload, response.body())) {
				throw new ErrorClassifier.PayloadMismatchException("POST " + path + " did not return the payload unchanged");
			}
		} catch (ErrorClassifier.PayloadMismatchException | ErrorClassifier.HttpStatusException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("verification call failed: " + e, e);
		} finally {
			release(connection);
		}
	}

	@Override
	public Map<String, String> serverEnvironment() {
		return getStringMap(BenchHttpServer.ENVIRONMENT_PATH);
	}

	@Override
	public Map<String, String> serverStats() {
		return getStringMap(BenchHttpServer.STATS_PATH);
	}

	private Map<String, String> getStringMap(String requestPath) {
		Connection connection = new Connection();
		try {
			connection.open();
			HttpCallTarget.Response response = connection.exchange(request("GET", requestPath, null),
					HttpCallTarget.ENVIRONMENT_TIMEOUT.toNanos(), true);
			if (response.status() != 200) {
				throw new IllegalStateException("GET " + requestPath + " returned HTTP " + response.status());
			}
			Object parsed = Json.parse(new String(response.body(), StandardCharsets.UTF_8));
			if (!(parsed instanceof Map<?, ?> map)) {
				throw new IllegalStateException("GET " + requestPath + " did not return a JSON object");
			}
			Map<String, String> values = new LinkedHashMap<>();
			map.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
			return values;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e.toString(), e);
		} finally {
			connection.closeSocket();
		}
	}

	@Override
	public Map<String, Object> describe() {
		return description;
	}

	@Override
	public void close() {
		for (Connection connection : all) {
			connection.closeSocket();
		}
	}

	/** 一条 keep-alive 连接；同一时刻只被取出它的那个线程使用。出错或超时后关闭，下次使用时重连。 */
	final class Connection {

		private Socket socket;
		private InputStream in;
		private OutputStream out;
		private final byte[] line = new byte[8192];
		/** 压测热路径不保留响应体：读进这块复用的缓冲区丢掉，只核对长度。 */
		private final byte[] scratch = new byte[16 * 1024];

		void open() throws IOException {
			Socket s = new Socket();
			s.setTcpNoDelay(true);
			s.connect(new InetSocketAddress(host, port), (int) HttpCallTarget.CONNECT_TIMEOUT.toMillis());
			socket = s;
			in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
			out = s.getOutputStream();
		}

		HttpCallTarget.Response exchange(byte[] request, long timeoutNanos, boolean captureBody) throws Exception {
			if (socket == null || socket.isClosed()) {
				open();
			}
			try {
				socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(timeoutNanos)));
				out.write(request);
				out.flush();
				int status = statusCode(readLine());
				int contentLength = -1;
				boolean close = false;
				for (String header = readLine(); !header.isEmpty(); header = readLine()) {
					int colon = header.indexOf(':');
					if (colon <= 0) {
						continue;
					}
					String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
					String value = header.substring(colon + 1).trim();
					if (name.equals("content-length")) {
						contentLength = Integer.parseInt(value);
					} else if (name.equals("connection") && value.equalsIgnoreCase("close")) {
						close = true;
					}
				}
				if (contentLength < 0) {
					throw new IOException("HTTP response from " + uri + " has no content-length (chunked responses are not supported)");
				}
				byte[] body = captureBody ? new byte[contentLength] : null;
				int read = 0;
				while (read < contentLength) {
					int n = captureBody ? in.read(body, read, contentLength - read)
							: in.read(scratch, 0, Math.min(scratch.length, contentLength - read));
					if (n < 0) {
						throw new IOException("HTTP connection closed by the peer");
					}
					read += n;
				}
				if (close) {
					closeSocket();
				}
				return new HttpCallTarget.Response(status, contentLength, body);
			} catch (SocketTimeoutException e) {
				closeSocket();
				throw new TimeoutException("no HTTP response from " + uri + " within " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms");
			} catch (Exception e) {
				closeSocket();
				throw e;
			}
		}

		/** 读一行（去掉 CRLF），ASCII。 */
		private String readLine() throws IOException {
			int n = 0;
			for (;;) {
				int b = in.read();
				if (b < 0) {
					throw new IOException("HTTP connection closed by the peer");
				}
				if (b == '\n') {
					break;
				}
				if (b != '\r') {
					if (n == line.length) {
						throw new IOException("HTTP header line longer than " + line.length + " bytes");
					}
					line[n++] = (byte) b;
				}
			}
			return new String(line, 0, n, StandardCharsets.US_ASCII);
		}

		private int statusCode(String statusLine) throws IOException {
			// HTTP/1.1 200 OK
			int space = statusLine.indexOf(' ');
			if (!statusLine.startsWith("HTTP/1.") || space < 0 || statusLine.length() < space + 4) {
				throw new IOException("malformed HTTP status line from " + uri + ": " + statusLine);
			}
			return Integer.parseInt(statusLine.substring(space + 1, space + 4));
		}

		void closeSocket() {
			Socket s = socket;
			socket = null;
			if (s != null) {
				try {
					s.close();
				} catch (IOException ignored) {
					// 关闭失败无所谓，下次重连
				}
			}
		}
	}
}
