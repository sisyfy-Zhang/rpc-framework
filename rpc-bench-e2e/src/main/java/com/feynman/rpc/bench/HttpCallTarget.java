package com.feynman.rpc.bench;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * {@code transport = http}：用 Netty 写的最小 HTTP/1.1 客户端，压 {@code target.http.url}（{@code bench server --http-port}
 * 的 {@code POST /echo}）。目的是交叉验证 harness 本身：同一个目标分别用 harness 与 wrk / wrk2 打，比较两个发压工具测出的数字。
 *
 * <p><b>连接模型与 wrk 一致</b>：keep-alive 长连接，<b>每条连接同一时刻只有一个在途请求</b>（不做 pipelining）。
 * <ul>
 *   <li>闭环：连接数 = {@code load.concurrency}，每个虚拟用户在一次调用期间独占一条连接（从空闲队列取、用完放回；
 *       用户数等于连接数，所以永远取得到）——与 {@code wrk -c N} 的"N 条连接各自串行收发"同构。</li>
 *   <li>开环：连接池大小 = {@code target.http.connections}（缺省 64）。计划时刻到了就取一条空闲连接发出；
 *       <b>没有空闲连接时排队</b>（公平信号量，按到达先后），下一条空闲的连接立即发出，延迟仍从计划时刻算——
 *       与 RPC 开环（请求排在客户端里）和 wrk2（请求推迟到该连接空闲）同一语义：十几毫秒的整机停顿只表现为尾延迟，
 *       不会让一轮无效。在途（排队 + 已发出）的上限仍是驱动的 {@code rate × timeout × 2}，超过才 {@code backlog_dropped}。
 *       排队 + 收发合计受 {@code load.timeout} 约束（等不到连接也按 timeout 失败）。{@code connections} 因此决定的是
 *       突发时排队的长短。</li>
 * </ul>
 *
 * <p>每次 {@link #call()} 在调用它的虚拟线程上写出请求、等响应（与 RPC 的同步代理同一种调用方式）。为了让交叉验证比较的是
 * harness 的驱动与计时、而不是这个适配器的额外开销，热路径刻意做轻：
 * <ul>
 *   <li>IO 线程数 = 可用核数，Linux 上用 Netty 的 epoll 原生传输（netty-all 自带，没有新依赖），其它平台 NIO；</li>
 *   <li>等待响应用不带超时的等待，超时由每个目标一个的扫描任务（每 {@value #TIMEOUT_SWEEP_MILLIS}ms 一次）判定：
 *       虚拟线程上的限时 park 每次都要在共享的调度器里登记、撤销一个定时任务，闭环高并发下这是可观的开销；
 *       代价是超时最多晚 {@value #TIMEOUT_SWEEP_MILLIS}ms 触发（{@code load.timeout} 是秒级）；</li>
 *   <li>写出用 void promise（失败经 exceptionCaught 交给在途请求），不为每次写出分配监听器。</li>
 * </ul>
 * 超时后该连接上可能还有迟到的响应，所以直接关闭这条连接，下次使用时重连（不重试本次调用）。
 */
final class HttpCallTarget implements CallTarget {

	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	/** {@code GET /environment} 与预热的超时：一次性的调用，与压测超时无关。 */
	static final Duration ENVIRONMENT_TIMEOUT = Duration.ofSeconds(30);
	static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;
	static final long TIMEOUT_SWEEP_MILLIS = 10;
	/**
	 * 建连后、第一轮之前，每条连接串行做这么多次回显（各连接并行），把 HTTP 编解码与调用路径 JIT 预热。
	 * 原因：冷 JVM 的第一秒里单次调用慢，连接（缺省 64 条）很快被占满，后面的请求排队，第一轮预热期的延迟与排队测的是 JIT，
	 * 不是目标（早期"无空闲连接即丢弃"的语义下这会直接让第一轮无效）。预热不计入任何一轮，
	 * 次数写进 {@code run.target.pre_warmup_calls_per_connection}。
	 */
	static final int PRE_WARMUP_CALLS_PER_CONNECTION = 200;

	private static final AttributeKey<Connection> CONNECTION = AttributeKey.valueOf("bench.http.connection");

	private final URI uri;
	private final String host;
	private final int port;
	private final String path;
	private final String hostHeader;
	private final EventLoopGroup group;
	private final Bootstrap bootstrap;
	private final byte[] payload;
	private final long timeoutNanos;
	private final boolean open;
	private final int poolSize;
	private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
	/** 空闲连接的许可：公平模式，等连接的请求按到达先后拿到连接（tryAcquire 带超时的形式遵守公平性）。 */
	private final java.util.concurrent.Semaphore permits;
	private final List<Connection> all = new ArrayList<>();
	/** 超时扫描的对象：池里的连接与临时连接（取环境用）。 */
	private final Set<Connection> tracked = ConcurrentHashMap.newKeySet();
	private final ScheduledFuture<?> sweeper;
	private final Map<String, Object> description;

	private HttpCallTarget(Profile profile) {
		this.uri = URI.create(profile.target().httpUrl());
		this.host = uri.getHost();
		this.port = uri.getPort() > 0 ? uri.getPort() : 80;
		this.path = uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();
		this.hostHeader = uri.getPort() > 0 ? host + ":" + port : host;
		this.payload = Payloads.bytes(profile.target().payloadSize());
		this.timeoutNanos = profile.load().timeout().toNanos();
		this.open = !profile.load().closed();
		this.poolSize = open ? profile.target().httpConnections() : profile.load().concurrency();
		this.permits = new java.util.concurrent.Semaphore(poolSize, true);
		int ioThreads = Runtime.getRuntime().availableProcessors();
		boolean epoll = Epoll.isAvailable();
		DefaultThreadFactory threads = new DefaultThreadFactory("bench-http-client");
		this.group = epoll ? new EpollEventLoopGroup(ioThreads, threads) : new NioEventLoopGroup(ioThreads, threads);
		this.bootstrap = new Bootstrap()
				.group(group)
				.channel(epoll ? EpollSocketChannel.class : NioSocketChannel.class)
				.option(ChannelOption.TCP_NODELAY, true)
				.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(MAX_CONTENT_BYTES), new ResponseHandler());
					}
				});
		this.sweeper = group.next().scheduleAtFixedRate(this::expireOverdue, TIMEOUT_SWEEP_MILLIS, TIMEOUT_SWEEP_MILLIS,
				TimeUnit.MILLISECONDS);
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
		d.put("http_client", "netty HttpClientCodec + HttpObjectAggregator(8MiB), keep-alive, no pipelining");
		d.put("io_transport", epoll ? "epoll" : "nio");
		d.put("io_threads", ioThreads);
		d.put("timeout_check", "sweep every " + TIMEOUT_SWEEP_MILLIS + "ms");
		d.put("retries", 0);
		d.put("pre_warmup_calls_per_connection", PRE_WARMUP_CALLS_PER_CONNECTION);
		d.put("call_style", "sync: the calling virtual thread writes one HTTP/1.1 request and waits for its response");
		d.put("metrics_filter", false);
		this.description = d;
	}

	/** 建好全部连接、做完预热再返回（都不算进任何一轮）；任一步失败则关闭已建的并抛出。 */
	static HttpCallTarget connect(Profile profile) {
		HttpCallTarget target = new HttpCallTarget(profile);
		try {
			List<ChannelFuture> pending = new ArrayList<>();
			for (int i = 0; i < target.poolSize; i++) {
				Connection connection = target.new Connection();
				target.all.add(connection);
				target.tracked.add(connection);
				pending.add(connection.startConnect());
			}
			for (int i = 0; i < pending.size(); i++) {
				target.all.get(i).finishConnect(pending.get(i));
			}
			target.preWarmUp();
			target.idle.addAll(target.all);
			return target;
		} catch (RuntimeException e) {
			target.close();
			throw e;
		}
	}

	/** 见 {@link #PRE_WARMUP_CALLS_PER_CONNECTION}。任何一次失败都说明目标不健康，直接抛出（不开压）。 */
	private void preWarmUp() {
		List<Future<?>> futures = new ArrayList<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Connection connection : all) {
				futures.add(executor.submit(() -> {
					for (int i = 0; i < PRE_WARMUP_CALLS_PER_CONNECTION; i++) {
						Response response = connection.exchange(HttpMethod.POST, path, payload, ENVIRONMENT_TIMEOUT.toNanos(), false);
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

	/** 按到达先后等一条空闲连接，最多等 {@code timeout}；等不到返回 null。 */
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
			Response response = connection.exchange(HttpMethod.POST, path, payload, remaining, false);
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

	/** 开压前校验一次完整回显（逐字节）；压测循环里只比状态码与长度。 */
	@Override
	public void verify() {
		Connection connection;
		try {
			connection = acquire(ENVIRONMENT_TIMEOUT.toNanos());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for an HTTP connection", e);
		}
		if (connection == null) {
			throw new IllegalStateException("no idle HTTP connection for the verification call");
		}
		try {
			Response response = connection.exchange(HttpMethod.POST, path, payload, timeoutNanos, true);
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

	/**
	 * {@code GET /environment}（同一 host:port），用一条单独的临时连接，不占压测连接池。返回 JSON 对象（值为字符串）；
	 * 目标不是 {@code bench server} 时（404 等）抛异常，由调用方记 unknown。
	 */
	@Override
	public Map<String, String> serverEnvironment() {
		Connection connection = new Connection();
		tracked.add(connection);
		try {
			connection.finishConnect(connection.startConnect());
			Response response = connection.exchange(HttpMethod.GET, BenchHttpServer.ENVIRONMENT_PATH, null,
					ENVIRONMENT_TIMEOUT.toNanos(), true);
			if (response.status() != 200) {
				throw new IllegalStateException("GET " + BenchHttpServer.ENVIRONMENT_PATH + " returned HTTP " + response.status());
			}
			Object parsed = Json.parse(new String(response.body(), StandardCharsets.UTF_8));
			if (!(parsed instanceof Map<?, ?> map)) {
				throw new IllegalStateException("GET " + BenchHttpServer.ENVIRONMENT_PATH + " did not return a JSON object");
			}
			Map<String, String> environment = new LinkedHashMap<>();
			map.forEach((k, v) -> environment.put(String.valueOf(k), String.valueOf(v)));
			return environment;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e.toString(), e);
		} finally {
			tracked.remove(connection);
			connection.closeChannel();
		}
	}

	@Override
	public Map<String, Object> describe() {
		return description;
	}

	@Override
	public void close() {
		sweeper.cancel(false);
		for (Connection connection : all) {
			connection.closeChannel();
		}
		group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
	}

	/** 在一个 IO 线程上周期运行：超过截止时间仍未完成的在途请求以 {@link TimeoutException} 结束。 */
	private void expireOverdue() {
		long now = System.nanoTime();
		for (Connection connection : tracked) {
			Pending pending = connection.pending;
			if (pending != null && !pending.future().isDone() && now - pending.deadline() > 0) {
				pending.future().completeExceptionally(new TimeoutException("no HTTP response from " + uri + " within "
						+ TimeUnit.NANOSECONDS.toMillis(pending.timeoutNanos()) + "ms"));
			}
		}
	}

	record Response(int status, int length, byte[] body) {
	}

	/** 一个在途请求：结果、截止时刻（nanoTime）、是否保留响应体（只有校验与取环境时需要）。 */
	private record Pending(CompletableFuture<Response> future, long deadline, long timeoutNanos, boolean capture) {
	}

	/**
	 * 一条 keep-alive 连接，同一时刻至多一个在途请求（由连接池保证：取出期间只有取出者使用它）。
	 * 连接断开或超时后 {@link #channel} 置空，下次 {@link #exchange} 时同步重连。
	 */
	final class Connection {

		private volatile Channel channel;
		private volatile Pending pending;

		ChannelFuture startConnect() {
			return bootstrap.connect(host, port);
		}

		void finishConnect(ChannelFuture future) {
			future.awaitUninterruptibly();
			if (!future.isSuccess()) {
				throw new IllegalStateException("cannot connect to " + uri + ": " + future.cause(), future.cause());
			}
			Channel ch = future.channel();
			ch.attr(CONNECTION).set(this);
			channel = ch;
		}

		Response exchange(HttpMethod method, String requestPath, byte[] body, long timeout, boolean captureBody) throws Exception {
			Channel ch = channel;
			if (ch == null || !ch.isActive()) {
				finishConnect(startConnect());
				ch = channel;
			}
			CompletableFuture<Response> future = new CompletableFuture<>();
			pending = new Pending(future, System.nanoTime() + timeout, timeout, captureBody);
			ByteBuf content = body == null ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body);
			FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, requestPath, content);
			request.headers().set(HttpHeaderNames.HOST, hostHeader);
			if (body != null) {
				request.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_OCTET_STREAM);
			}
			request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
			ch.writeAndFlush(request, ch.voidPromise()); // 写失败经 exceptionCaught → onFailure 结束 future
			try {
				return future.get();
			} catch (ExecutionException e) {
				// 超时或连接出错：迟到的响应会被当成下一个请求的，这条连接不能再用，关掉，下次使用时重连
				closeChannel();
				Throwable cause = e.getCause();
				throw cause instanceof Exception ex ? ex : e;
			}
		}

		/** 只处理当前 channel 的事件：超时后被替换掉的旧 channel 上迟到的响应 / 断开事件一律忽略。 */
		void onResponse(Channel source, FullHttpResponse response) {
			Pending p = pending;
			if (p == null || source != channel) {
				return;
			}
			ByteBuf content = response.content();
			byte[] body = p.capture() ? ByteBufUtil.getBytes(content) : null;
			p.future().complete(new Response(response.status().code(), content.readableBytes(), body));
		}

		void onFailure(Channel source, Throwable cause) {
			Pending p = pending;
			if (p != null && source == channel) {
				p.future().completeExceptionally(cause instanceof Exception ? cause : new IOException(cause));
			}
		}

		void closeChannel() {
			Channel ch = channel;
			channel = null;
			if (ch != null) {
				ch.close();
			}
		}
	}

	/** 每条连接一个：把响应交给该连接当前的在途请求。 */
	private static final class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
			Connection connection = ctx.channel().attr(CONNECTION).get();
			if (connection != null) {
				connection.onResponse(ctx.channel(), response);
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			Connection connection = ctx.channel().attr(CONNECTION).get();
			if (connection != null) {
				connection.onFailure(ctx.channel(), new IOException("HTTP connection closed by the peer"));
			}
			super.channelInactive(ctx);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			Connection connection = ctx.channel().attr(CONNECTION).get();
			if (connection != null) {
				connection.onFailure(ctx.channel(), cause);
			}
			ctx.close();
		}
	}
}
