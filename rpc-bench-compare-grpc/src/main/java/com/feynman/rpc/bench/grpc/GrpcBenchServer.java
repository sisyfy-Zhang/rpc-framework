package com.feynman.rpc.bench.grpc;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.ServerProbes;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel;
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 对比对象 gRPC 的压测服务端（独立进程）：{@link BenchEchoService} 跑在 grpc-java 的 Netty 服务端上。
 *
 * <pre>
 *   GrpcBenchServer --port &lt;p&gt; [--host &lt;h&gt;] [--io-threads &lt;n&gt;] [--executor direct|fixed|grpc-default]
 *                   [--no-registry] [--metrics-port &lt;p&gt;（忽略）]
 * </pre>
 * 也经 {@code bench server --transport grpc ...} 启动（{@link GrpcCallTargetProvider#serverLauncher()}，参数相同）。
 *
 * <p>与本框架 bench server 的对等设置：
 * <ul>
 *   <li><b>IO 线程</b>：boss 1 个、worker 事件循环 {@code --io-threads} 个（缺省 0 = Netty 缺省 2 × 可用核数），与 RpcServer 的
 *       {@code NioEventLoopGroup(1)} + {@code NioEventLoopGroup(ioThreads)} 相同；传输显式用 NIO（grpc-netty-shaded 在 Linux 上缺省会用
 *       epoll，本框架是 NIO，不让传输实现成为变量）。</li>
 *   <li><b>执行器</b>：缺省 {@code grpc-default}（不调 {@code executor(...)}：gRPC 自带的共享缓存线程池，业务回调离开 IO 线程）——
 *       与本框架 bench server 的缺省同一种模型（业务方法在固定池上执行、不在 IO 线程上），对比卡都用它。
 *       {@code direct}（{@code directExecutor()}：回调直接在 IO 线程上执行，少一次线程交接）只作<b>gRPC 的上界对照点，不是缺省用法</b>；
 *       {@code fixed} 是与本框架缺省同样大小的固定池（{@code ExecutorConfig.defaults()} 的对等物）。</li>
 *   <li><b>消息上限</b>：入站 8 MiB，与本框架的帧上限一致（gRPC 缺省 4 MiB）。</li>
 *   <li>不注册任何注册中心（{@code --registry} 是用法错误）；{@code --host} 只记录，监听全部地址（与 bench server 相同）。</li>
 * </ul>
 * 就绪后打印 {@code bench-server ready port=... pid=... transport=grpc ...}，收到 SIGTERM / Ctrl-C 后关闭。
 */
public final class GrpcBenchServer implements AutoCloseable {

	public static final String EXECUTOR_DIRECT = "direct";
	public static final String EXECUTOR_FIXED = "fixed";
	public static final String EXECUTOR_GRPC_DEFAULT = "grpc-default";
	/** 与本框架帧上限（8 MiB）一致。 */
	static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;
	/** {@code fixed} 执行器的排队上限，与 {@code ExecutorConfig.DEFAULT_QUEUE_CAPACITY} 相同。 */
	static final int FIXED_QUEUE_CAPACITY = 1000;

	private final Server server;
	private final EventLoopGroup boss;
	private final EventLoopGroup workers;
	private final ExecutorService executor;
	private final Map<String, String> environment;

	private GrpcBenchServer(Server server, EventLoopGroup boss, EventLoopGroup workers, ExecutorService executor,
			Map<String, String> environment) {
		this.server = server;
		this.boss = boss;
		this.workers = workers;
		this.executor = executor;
		this.environment = environment;
	}

	public static void main(String[] args) throws Exception {
		System.exit(run(args, System.out));
	}

	/**
	 * 起服务端并绑定端口后返回。
	 *
	 * @param ioThreads worker 事件循环线程数，0 = Netty 缺省（2 × 可用核数）
	 * @param executorKind {@link #EXECUTOR_DIRECT} | {@link #EXECUTOR_FIXED} | {@link #EXECUTOR_GRPC_DEFAULT}
	 */
	public static GrpcBenchServer start(int port, int ioThreads, String executorKind) throws IOException {
		if (ioThreads < 0 || ioThreads > 1024) {
			throw new IllegalArgumentException("--io-threads must be 0 (Netty default) .. 1024, got " + ioThreads);
		}
		if (!List.of(EXECUTOR_DIRECT, EXECUTOR_FIXED, EXECUTOR_GRPC_DEFAULT).contains(executorKind)) {
			throw new IllegalArgumentException(
					"--executor must be " + EXECUTOR_DIRECT + ", " + EXECUTOR_FIXED + " or " + EXECUTOR_GRPC_DEFAULT + ", got \"" + executorKind + "\"");
		}
		Map<String, String> environment = new LinkedHashMap<>(ServerProbes.environment());
		environment.put("metrics_enabled", "false");
		environment.put("transport", "grpc");
		environment.put("grpc_version", grpcVersion());
		environment.put("grpc_netty_version", shadedNettyVersion());
		EventLoopGroup boss = new NioEventLoopGroup(1, new DefaultThreadFactory("grpc-server-boss", true));
		EventLoopGroup workers = new NioEventLoopGroup(ioThreads, new DefaultThreadFactory("grpc-server-worker", true));
		environment.put("server_io_threads", String.valueOf(ioThreadsText(workers, ioThreads)));
		ExecutorService executor = null;
		try {
			NettyServerBuilder builder = NettyServerBuilder.forAddress(new InetSocketAddress(port))
					.bossEventLoopGroup(boss)
					.workerEventLoopGroup(workers)
					.channelType(NioServerSocketChannel.class)
					.maxInboundMessageSize(MAX_MESSAGE_BYTES);
			switch (executorKind) {
				case EXECUTOR_DIRECT -> {
					builder.directExecutor();
					environment.put("server_executor", "directExecutor(): handlers run on the IO (event loop) threads (upper-bound reference, not the default)");
				}
				case EXECUTOR_FIXED -> {
					int threads = Runtime.getRuntime().availableProcessors() * 2;
					executor = fixedPool(threads);
					builder.executor(executor);
					environment.put("server_executor", "fixed pool (" + threads + " threads, queue " + FIXED_QUEUE_CAPACITY
							+ "): handlers run off the IO threads, same size as the rpc bench server's default");
				}
				case EXECUTOR_GRPC_DEFAULT -> environment.put("server_executor",
						"grpc default (no executor(...): shared cached thread pool, handlers run off the IO threads)");
				default -> throw new IllegalArgumentException(
						"--executor must be " + EXECUTOR_DIRECT + ", " + EXECUTOR_FIXED + " or " + EXECUTOR_GRPC_DEFAULT + ", got \"" + executorKind + "\"");
			}
			Map<String, String> frozen = Collections.unmodifiableMap(environment);
			Server server = builder.addService(new BenchEchoService(frozen)).build().start();
			return new GrpcBenchServer(server, boss, workers, executor, frozen);
		} catch (IOException | RuntimeException e) {
			boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
			workers.shutdownGracefully(0, 1, TimeUnit.SECONDS);
			if (executor != null) {
				executor.shutdownNow();
			}
			throw e;
		}
	}

	/** 与 {@code ExecutorConfig.defaults()} 对等：固定线程数、有界队列，满了拒绝（gRPC 把拒绝映射成调用失败）。 */
	private static ExecutorService fixedPool(int threads) {
		AtomicInteger counter = new AtomicInteger();
		return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(FIXED_QUEUE_CAPACITY), r -> {
			Thread t = new Thread(r, "grpc-server-biz-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	public int port() {
		return server.getPort();
	}

	/** 启动时采集的服务端环境（{@code Environment} 返回的同一份）。 */
	public Map<String, String> environment() {
		return environment;
	}

	@Override
	public void close() {
		server.shutdown();
		try {
			if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
				server.shutdownNow();
				server.awaitTermination(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			server.shutdownNow();
			Thread.currentThread().interrupt();
		}
		boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
		workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	/** 命令行入口（也是 {@code bench server --transport grpc} 的 {@code ServerLauncher}）：阻塞到关闭。 */
	static int run(String[] args, PrintStream out) throws Exception {
		Integer port = null;
		int ioThreads = 0;
		String executor = EXECUTOR_GRPC_DEFAULT;
		String host = null;
		List<String> ignored = new ArrayList<>();
		try {
			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				switch (arg) {
					case "--port" -> port = integer(arg, value(args, ++i, arg));
					case "--io-threads" -> ioThreads = integer(arg, value(args, ++i, arg));
					case "--executor" -> executor = value(args, ++i, arg);
					case "--host" -> host = value(args, ++i, arg);
					case "--no-registry" -> {
						// 对比对象不经注册中心：接受这个开关，便于与 bench server 共用脚本参数
					}
					case "--metrics-port" -> ignored.add(arg + " " + value(args, ++i, arg));
					default -> throw new IllegalArgumentException("unknown option " + arg + " (usage: --port <p> [--host <h>] [--io-threads <n>]"
							+ " [--executor direct|fixed|grpc-default] [--no-registry]; --registry is not supported: comparison targets are addressed with --direct)");
				}
			}
		} catch (IllegalArgumentException e) {
			out.println("error: " + e.getMessage());
			return 1;
		}
		if (port == null) {
			out.println("error: --port is required");
			return 1;
		}
		GrpcBenchServer server;
		try {
			server = start(port, ioThreads, executor);
		} catch (IllegalArgumentException e) {
			out.println("error: " + e.getMessage());
			return 1;
		}
		if (!ignored.isEmpty()) {
			out.println("warning: ignored " + ignored + " (the gRPC bench server has no metrics endpoint)");
		}
		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			out.println("bench-server stopping");
			server.close();
			out.println("bench-server stopped");
			out.flush();
			stopped.countDown();
		}, "grpc-bench-server-shutdown"));
		out.println("bench-server ready port=" + server.port() + " pid=" + ProcessHandle.current().pid() + " transport=grpc registry=none"
				+ " executor=" + executor + " io_threads=" + server.environment().get("server_io_threads")
				+ (host == null ? "" : " host=" + host) + " grpc=" + server.environment().get("grpc_version"));
		out.flush();
		stopped.await();
		return 0;
	}

	private static String value(String[] args, int i, String option) {
		if (i >= args.length || args[i].startsWith("--")) {
			throw new IllegalArgumentException(option + " needs a value");
		}
		return args[i];
	}

	private static int integer(String option, String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(option + " must be an integer, got \"" + text + "\"");
		}
	}

	static String grpcVersion() {
		try {
			return io.grpc.internal.GrpcUtil.IMPLEMENTATION_VERSION;
		} catch (RuntimeException | LinkageError e) {
			return "unknown";
		}
	}

	/**
	 * 事件循环组的实际线程数，写法与 {@link HarnessOptions#nettyDefaultIoThreadsText()} 一致：显式给了就是那个数，
	 * 0（Netty 缺省）时写成 {@code default (Netty: 2 x available cores = N)}，N 取线程组自己的 executorCount（重定位的 Netty 有自己的核数缓存）。
	 */
	static Object ioThreadsText(EventLoopGroup group, int configured) {
		int actual = ((io.grpc.netty.shaded.io.netty.channel.MultithreadEventLoopGroup) group).executorCount();
		return configured == 0 ? "default (Netty: 2 x available cores = " + actual + ")" : (Object) actual;
	}

	/** grpc-netty-shaded 里重定位过的 Netty 的版本（与 harness 的 netty-all 无关）。 */
	static String shadedNettyVersion() {
		try {
			var versions = io.grpc.netty.shaded.io.netty.util.Version.identify(GrpcBenchServer.class.getClassLoader());
			var common = versions.get("netty-common");
			if (common != null) {
				return common.artifactVersion();
			}
			return versions.values().stream().findFirst().map(v -> v.artifactVersion()).orElse("unknown");
		} catch (RuntimeException | LinkageError e) {
			return "unknown";
		}
	}
}
