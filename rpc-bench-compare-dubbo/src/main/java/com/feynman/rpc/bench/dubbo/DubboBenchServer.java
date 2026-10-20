package com.feynman.rpc.bench.dubbo;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.model.FrameworkModel;

import com.feynman.rpc.bench.BenchService;
import com.feynman.rpc.bench.BenchServiceImpl;
import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.ServerProbes;

/**
 * 对比对象 Dubbo 3 的压测服务端（独立进程）：triple 协议、<b>Java 接口模式</b>（不写 IDL），直接导出与 rpc bench server
 * <b>同一个</b> {@link BenchService} 接口与 {@link BenchServiceImpl} 实现——"业务接口直接导出"的缺省用法。
 *
 * <pre>
 *   DubboBenchServer --port &lt;p&gt; [--host &lt;h&gt;] [--io-threads &lt;n&gt;] [--no-registry]
 * </pre>
 * 也经 {@code bench server --transport dubbo ...} 启动。
 *
 * <ul>
 *   <li>不注册（{@code RegistryConfig.NO_AVAILABLE}），客户端直连 {@code tri://host:port}；关掉 QoS 端口。每个服务端一个独立的
 *       {@link FrameworkModel}，不碰 Dubbo 的进程级单例。</li>
 *   <li><b>IO 线程</b>：{@code iothreads} = {@code --io-threads}，缺省对齐本框架（Netty 缺省 2 × 可用核数；Dubbo 自己的缺省是 min(核数 + 1, 32)）。</li>
 *   <li><b>业务线程</b>：Dubbo 缺省（{@code threadpool=fixed, threads=200}），业务方法不在 IO 线程上执行——与本框架"固定池、离开 IO 线程"
 *       同一种模型，池更大（本框架缺省 2 × 可用核数）。</li>
 *   <li><b>序列化</b>：不指定，用 Dubbo 3.3 的缺省（{@code prefer.serialization}；实际生效的值由客户端从调用 URL 读回、写进报告）。</li>
 *   <li><b>指标关闭</b>（{@link #metricsOff()}，客户端同样）：Dubbo 发现 classpath 上有 Micrometer（harness 为 {@code --metrics-port} 带着它）
 *       就自动开启指标采集，包括 RPC 热路径上的 MetricsFilter；本框架压测时指标 Filter 缺省关闭，对比时两边一致。</li>
 * </ul>
 */
public final class DubboBenchServer implements AutoCloseable {

	private final DubboBootstrap bootstrap;
	private final FrameworkModel framework;
	private final int port;
	private final Map<String, String> environment;

	private DubboBenchServer(DubboBootstrap bootstrap, FrameworkModel framework, int port, Map<String, String> environment) {
		this.bootstrap = bootstrap;
		this.framework = framework;
		this.port = port;
		this.environment = environment;
	}

	public static void main(String[] args) throws Exception {
		System.exit(run(args, System.out));
	}

	/**
	 * @param port      监听端口（Dubbo 不支持 0 = 随机端口时，测试先找一个空闲端口传进来）
	 * @param ioThreads worker 事件循环线程数；0 = 对齐本框架的 Netty 缺省（2 × 可用核数）
	 */
	public static DubboBenchServer start(int port, int ioThreads) {
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("--port must be 1..65535 (Dubbo needs a concrete port), got " + port);
		}
		if (ioThreads < 0 || ioThreads > 1024) {
			throw new IllegalArgumentException("--io-threads must be 0 (2 x available cores) .. 1024, got " + ioThreads);
		}
		int iothreads = ioThreads == 0 ? HarnessOptions.nettyDefaultIoThreads() : ioThreads;
		Map<String, String> environment = new LinkedHashMap<>(ServerProbes.environment());
		environment.put("metrics_enabled", "false");
		environment.put("transport", "dubbo");
		environment.put("dubbo_version", Version.getVersion());
		environment.put("server_io_threads", ioThreads == 0 ? "default (aligned with Netty: 2 x available cores = " + iothreads + ")"
				: String.valueOf(iothreads));
		environment.put("server_executor", "dubbo default (threadpool=fixed, threads=" + ProtocolConfigDefaults.THREADS
				+ "): business methods run off the IO threads");
		Map<String, String> frozen = Collections.unmodifiableMap(environment);

		FrameworkModel framework = new FrameworkModel();
		DubboBootstrap bootstrap = DubboBootstrap.newInstance(framework);
		try {
			ApplicationConfig application = new ApplicationConfig("rpc-bench-dubbo-server");
			application.setQosEnable(false);
			ProtocolConfig protocol = new ProtocolConfig("tri", port);
			protocol.setIothreads(iothreads);
			ServiceConfig<BenchService> service = new ServiceConfig<>();
			service.setInterface(BenchService.class);
			service.setRef(new BenchServiceImpl(frozen));
			bootstrap.application(application)
					.metrics(metricsOff())
					.registry(new RegistryConfig(RegistryConfig.NO_AVAILABLE))
					.protocol(protocol)
					.service(service)
					.start();
			return new DubboBenchServer(bootstrap, framework, port, frozen);
		} catch (RuntimeException e) {
			bootstrap.destroy();
			framework.destroy();
			throw e;
		}
	}

	/**
	 * 关掉 Dubbo 的全部指标采集（RPC / 线程池 / Netty / JVM / 注册中心 / 元数据、采集同步任务、指标服务导出）。
	 * 与本框架的口径一致：压测路径上不挂指标 Filter（本框架开了 {@code --metrics-port} 才挂，且要在 label 里写明）。
	 */
	static org.apache.dubbo.config.MetricsConfig metricsOff() {
		org.apache.dubbo.config.MetricsConfig metrics = new org.apache.dubbo.config.MetricsConfig();
		metrics.setEnableRpc(false);
		metrics.setEnableThreadpool(false);
		metrics.setEnableNetty(false);
		metrics.setEnableJvm(false);
		metrics.setEnableRegistry(false);
		metrics.setEnableMetadata(false);
		metrics.setEnableMetricsInit(false);
		metrics.setEnableCollectorSync(false);
		metrics.setExportMetricsService(false);
		return metrics;
	}

	/** Dubbo 的业务线程池缺省值（{@code CommonConstants.DEFAULT_THREADS}），只用于写进服务端环境。 */
	static final class ProtocolConfigDefaults {
		static final int THREADS = org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREADS;

		private ProtocolConfigDefaults() {
		}
	}

	public int port() {
		return port;
	}

	public Map<String, String> environment() {
		return environment;
	}

	@Override
	public void close() {
		bootstrap.stop();
		bootstrap.destroy();
		framework.destroy();
	}

	/** 命令行入口（也是 {@code bench server --transport dubbo} 的 {@code ServerLauncher}）：阻塞到关闭。 */
	static int run(String[] args, PrintStream out) throws Exception {
		Integer port = null;
		int ioThreads = 0;
		String host = null;
		List<String> ignored = new ArrayList<>();
		try {
			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				switch (arg) {
					case "--port" -> port = integer(arg, value(args, ++i, arg));
					case "--io-threads" -> ioThreads = integer(arg, value(args, ++i, arg));
					case "--host" -> host = value(args, ++i, arg);
					case "--no-registry" -> {
						// 对比对象不经注册中心：接受这个开关，便于与 bench server 共用脚本参数
					}
					case "--metrics-port" -> ignored.add(arg + " " + value(args, ++i, arg));
					default -> throw new IllegalArgumentException("unknown option " + arg + " (usage: --port <p> [--host <h>] [--io-threads <n>]"
							+ " [--no-registry]; --registry is not supported: comparison targets are addressed with --direct)");
				}
			}
			if (port == null) {
				throw new IllegalArgumentException("--port is required");
			}
		} catch (IllegalArgumentException e) {
			out.println("error: " + e.getMessage());
			return 1;
		}
		DubboBenchServer server;
		try {
			server = start(port, ioThreads);
		} catch (IllegalArgumentException e) {
			out.println("error: " + e.getMessage());
			return 1;
		}
		if (!ignored.isEmpty()) {
			out.println("warning: ignored " + ignored + " (the Dubbo bench server has no metrics endpoint)");
		}
		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			out.println("bench-server stopping");
			server.close();
			out.println("bench-server stopped");
			out.flush();
			stopped.countDown();
		}, "dubbo-bench-server-shutdown"));
		out.println("bench-server ready port=" + server.port() + " pid=" + ProcessHandle.current().pid() + " transport=dubbo registry=none"
				+ " io_threads=" + server.environment().get("server_io_threads") + (host == null ? "" : " host=" + host) + " dubbo="
				+ server.environment().get("dubbo_version"));
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
}
